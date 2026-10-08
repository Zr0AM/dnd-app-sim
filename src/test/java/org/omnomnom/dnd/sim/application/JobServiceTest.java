package org.omnomnom.dnd.sim.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.adapter.out.content.SqliteContentSource;
import org.omnomnom.dnd.sim.domain.opt.BuildClass;
import org.omnomnom.dnd.sim.domain.opt.Reports;
import org.omnomnom.dnd.sim.testsupport.InMemoryReportStore;

class JobServiceTest {

    static ContentCatalogs catalogs;

    InMemoryReportStore store;
    SimulationExecutor executor;
    JobService jobs;

    @BeforeAll
    static void load() {
        try (SqliteContentSource source = SqliteContentSource.open()) {
            catalogs = ContentCatalogs.load(source);
        }
    }

    @BeforeEach
    void setUp() {
        store = new InMemoryReportStore();
        executor = new SimulationExecutor(1, 1);
        jobs = new JobService(catalogs, executor, store, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    private static final OptimizeCommand.GaParams TINY = new OptimizeCommand.GaParams(4, 2, 1, null);

    private static OptimizeCommand tiny(Long seed, boolean campaign) {
        return new OptimizeCommand(3, null, List.of(BuildClass.FIGHTER, BuildClass.WIZARD), null, TINY, false, campaign, seed);
    }

    private JobView await(String id, Predicate<JobView> done) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            JobView v = jobs.get(id);
            if (done.test(v)) {
                return v;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for job " + id + ": " + jobs.get(id));
    }

    private JobView awaitFinished(String id) throws InterruptedException {
        return await(id, v -> v.status().finished());
    }

    // ---- effort resolution ---------------------------------------------------------------------

    @Test
    void effortDefaultsToTheLevelsPreset() {
        assertThat(JobService.effort(3, null, null)).isEqualTo(new JobService.Effort(32, 12, 12, 0.3));
        assertThat(JobService.effort(5, null, null)).isEqualTo(new JobService.Effort(32, 12, 12, 0.3));
        assertThat(JobService.effort(11, null, null)).isEqualTo(new JobService.Effort(64, 24, 20, 0.3));
        assertThat(JobService.effort(17, null, null)).isEqualTo(new JobService.Effort(64, 24, 20, 0.3));
    }

    @Test
    void anExplicitPresetBeatsTheLevelDefaultAndGaFieldsOverrideItFieldByField() {
        assertThat(JobService.effort(17, "quick", null)).isEqualTo(new JobService.Effort(16, 6, 8, 0.3));
        assertThat(JobService.effort(3, "thorough", new OptimizeCommand.GaParams(null, 5, null, 0.5)))
                .isEqualTo(new JobService.Effort(64, 5, 20, 0.5));
        assertThat(JobService.effort(3, null, new OptimizeCommand.GaParams(10, null, 3, null))).isEqualTo(new JobService.Effort(10, 12, 3, 0.3));
        assertThatThrownBy(() -> JobService.effort(3, "ludicrous", null)).isInstanceOfSatisfying(UnprocessableException.class,
                e -> assertThat(e.code()).isEqualTo("unknown-preset"));
    }

    // ---- an optimization job -------------------------------------------------------------------

    @Test
    void anOptimizationRunsToACompletedSavedReport() throws Exception {
        JobView queued = jobs.startOptimization(new OptimizeCommand(3, "tank", List.of(BuildClass.FIGHTER, BuildClass.WIZARD, BuildClass.FIGHTER),
                null, TINY, false, false, 42L));
        assertThat(queued.kind()).isEqualTo(JobView.Kind.OPTIMIZE);
        assertThat(queued.seed()).isEqualTo(42L);
        assertThat(queued.status()).isIn(JobView.Status.QUEUED, JobView.Status.RUNNING);

        JobView done = awaitFinished(queued.id());
        assertThat(done.status()).isEqualTo(JobView.Status.SUCCEEDED);
        assertThat(done.error()).isNull();
        assertThat(done.startedAt()).isNotNull();
        assertThat(done.finishedAt()).isAfterOrEqualTo(done.startedAt());
        assertThat(done.progress().phase()).isEqualTo("generation");
        assertThat(done.progress().completed()).isEqualTo(2);
        assertThat(done.progress().total()).isEqualTo(2);

        Reports.Report report = store.find(done.reportId()).orElseThrow().report();
        assertThat(report.runKey()).isEqualTo(done.reportId());
        assertThat(report.config()).containsEntry("level", 3).containsEntry("role", "tank").containsEntry("campaign", false).containsEntry("seed", 42L);
        assertThat(report.config().get("classes")).isEqualTo(List.of("fighter", "wizard")); // duplicates removed
        assertThat(report.config().get("ga")).isEqualTo(java.util.Map.of("populationSize", 4, "generations", 2, "evalRuns", 1, "mutationRate", 0.3));
        assertThat(report.weights()).isEqualTo(java.util.Map.of("reliability", 2.0, "offense", 1.0, "survival", 3.0, "efficiency", 1.0));
        assertThat(report.paretoFront()).isNotEmpty();
        assertThat(report.paretoFront()).allSatisfy(e -> assertThat(e.campaignDayWinRate()).isNull());
        assertThat(done.warnings()).isEmpty();
    }

    @Test
    void theSameSeedGivesTheSameReport() throws Exception {
        JobView a = awaitFinished(jobs.startOptimization(tiny(5L, false)).id());
        JobView b = awaitFinished(jobs.startOptimization(tiny(5L, false)).id());
        assertThat(a.reportId()).isEqualTo(b.reportId());
        JobView c = awaitFinished(jobs.startOptimization(tiny(6L, false)).id());
        assertThat(c.reportId()).isNotEqualTo(a.reportId());
        assertThat(store.size()).isEqualTo(2);
    }

    @Test
    void aCampaignPassAnnotatesTheBuilds() throws Exception {
        JobView done = awaitFinished(jobs.startOptimization(tiny(3L, true)).id());
        assertThat(done.status()).isEqualTo(JobView.Status.SUCCEEDED);
        assertThat(done.progress().phase()).isEqualTo("campaign");
        Reports.Report report = store.find(done.reportId()).orElseThrow().report();
        assertThat(report.config()).containsEntry("campaign", true);
        assertThat(report.leaderboard()).allSatisfy(e -> assertThat(e.campaignDayWinRate()).isBetween(0.0, 1.0));
    }

    @Test
    void aPartyOnlyRoleCarriesAWarning() {
        JobView job = jobs.startOptimization(new OptimizeCommand(3, "healer", List.of(BuildClass.CLERIC), null, TINY, false, false, 1L));
        assertThat(job.warnings()).singleElement().asString().contains("healer");
        jobs.cancel(job.id());
    }

    @Test
    void rejectsWhatItCannotRun() {
        assertThatThrownBy(() -> jobs.startOptimization(new OptimizeCommand(3, null, null, null, null, true, false, null)))
                .isInstanceOfSatisfying(UnprocessableException.class, e -> assertThat(e.code()).isEqualTo("unsupported-context"));
        assertThatThrownBy(() -> jobs.startOptimization(new OptimizeCommand(4, null, null, null, null, false, false, null)))
                .isInstanceOfSatisfying(UnprocessableException.class, e -> assertThat(e.code()).isEqualTo("unsupported-level"));
        assertThatThrownBy(() -> jobs.startOptimization(new OptimizeCommand(3, "jester", null, null, null, false, false, null)))
                .isInstanceOfSatisfying(UnprocessableException.class, e -> assertThat(e.code()).isEqualTo("unknown-role"));
        assertThatThrownBy(() -> jobs.startOptimization(new OptimizeCommand(3, null, null, "ludicrous", null, false, false, null)))
                .isInstanceOfSatisfying(UnprocessableException.class, e -> assertThat(e.code()).isEqualTo("unknown-preset"));
    }

    // ---- cancellation, failure, saturation ------------------------------------------------------

    private static final OptimizeCommand.GaParams LONG = new OptimizeCommand.GaParams(12, 100, 4, null);

    private OptimizeCommand longRun(long seed) {
        return new OptimizeCommand(3, null, null, null, LONG, false, false, seed);
    }

    @Test
    void aRunningJobStopsAtAGenerationBoundaryWhenCancelled() throws Exception {
        JobView job = jobs.startOptimization(longRun(1L));
        await(job.id(), v -> v.status() == JobView.Status.RUNNING && v.progress() != null);
        JobView requested = jobs.cancel(job.id());
        assertThat(requested.status()).isIn(JobView.Status.RUNNING, JobView.Status.CANCELLED);
        JobView done = awaitFinished(job.id());
        assertThat(done.status()).isEqualTo(JobView.Status.CANCELLED);
        assertThat(done.reportId()).isNull();
        assertThat(done.progress().completed()).isLessThan(100);
        assertThat(store.size()).isZero();
    }

    @Test
    void aQueuedJobIsCancelledAtOnceAndNeverRuns() throws Exception {
        JobView running = jobs.startOptimization(longRun(1L));
        await(running.id(), v -> v.status() == JobView.Status.RUNNING);
        JobView queued = jobs.startOptimization(tiny(2L, false));
        assertThat(queued.status()).isEqualTo(JobView.Status.QUEUED);
        assertThat(jobs.cancel(queued.id()).status()).isEqualTo(JobView.Status.CANCELLED);
        jobs.cancel(running.id());
        awaitFinished(running.id());
        assertThat(jobs.get(queued.id()).startedAt()).isNull();
        assertThat(store.size()).isZero();
    }

    @Test
    void cancellingAFinishedOrUnknownJobIsAnError() throws Exception {
        JobView done = awaitFinished(jobs.startOptimization(tiny(1L, false)).id());
        assertThatThrownBy(() -> jobs.cancel(done.id())).isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.code()).isEqualTo("job-finished"));
        assertThatThrownBy(() -> jobs.cancel("nope")).isInstanceOfSatisfying(NotFoundException.class, e -> assertThat(e.code()).isEqualTo("job-not-found"));
        assertThatThrownBy(() -> jobs.get("nope")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aFullQueueRejectsNewJobsWithoutTrackingThem() throws Exception {
        JobView running = jobs.startOptimization(longRun(1L));
        await(running.id(), v -> v.status() == JobView.Status.RUNNING);
        JobView queued = jobs.startOptimization(tiny(2L, false)); // takes the single queue slot
        assertThatThrownBy(() -> jobs.startOptimization(tiny(3L, false))).isInstanceOf(BusyException.class);
        jobs.cancel(queued.id());
        jobs.cancel(running.id());
        awaitFinished(running.id());
    }

    @Test
    void aFailureIsRecordedAsAProblem() throws Exception {
        store.failOnSave.set(true);
        JobView done = awaitFinished(jobs.startOptimization(tiny(1L, false)).id());
        assertThat(done.status()).isEqualTo(JobView.Status.FAILED);
        assertThat(done.reportId()).isNull();
        assertThat(done.error().code()).isEqualTo("job-failed");
        assertThat(done.error().status()).isEqualTo(500);
        assertThat(done.error().detail()).isEqualTo("disk on fire");
    }

    // ---- annotating a saved report -------------------------------------------------------------

    @Test
    void aSavedReportCanBeAnnotatedWithAdventuringDays() throws Exception {
        String reportId = awaitFinished(jobs.startOptimization(tiny(9L, false)).id()).reportId();
        JobView job = jobs.startReportCampaign(reportId, 2, null);
        assertThat(job.kind()).isEqualTo(JobView.Kind.CAMPAIGN);
        JobView done = awaitFinished(job.id());
        assertThat(done.status()).isEqualTo(JobView.Status.SUCCEEDED);
        assertThat(done.reportId()).isEqualTo(reportId);
        assertThat(done.progress().completed()).isEqualTo(1);
        Reports.Report report = store.find(reportId).orElseThrow().report();
        assertThat(report.leaderboard()).allSatisfy(e -> assertThat(e.campaignDayWinRate()).isBetween(0.0, 1.0));
        assertThat(report.paretoFront()).allSatisfy(e -> assertThat(e.campaignDayWinRate()).isBetween(0.0, 1.0));
        assertThat(store.size()).isEqualTo(1); // overwritten, not duplicated
    }

    @Test
    void annotatingAnUnknownReportIs404() {
        assertThatThrownBy(() -> jobs.startReportCampaign("deadbeef", null, null))
                .isInstanceOfSatisfying(NotFoundException.class, e -> assertThat(e.code()).isEqualTo("report-not-found"));
    }
}
