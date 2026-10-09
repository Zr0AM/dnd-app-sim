package org.omnomnom.dnd.sim.application.job;

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
import org.omnomnom.dnd.sim.application.content.ContentCatalogs;
import org.omnomnom.dnd.sim.application.error.BusyException;
import org.omnomnom.dnd.sim.application.error.ConflictException;
import org.omnomnom.dnd.sim.application.error.NotFoundException;
import org.omnomnom.dnd.sim.application.error.UnprocessableException;
import org.omnomnom.dnd.sim.application.execution.SimulationExecutor;
import org.omnomnom.dnd.sim.domain.opt.genome.BuildClass;
import org.omnomnom.dnd.sim.domain.opt.report.Reports;
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
        // A tiny job can finish before its view is returned.
        assertThat(queued.status()).isIn(JobView.Status.QUEUED, JobView.Status.RUNNING, JobView.Status.SUCCEEDED);

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
        assertThat(report.config())
                .containsEntry("classes", List.of("fighter", "wizard")) // duplicates removed
                .containsEntry("ga", java.util.Map.of("populationSize", 4, "generations", 2, "evalRuns", 1, "mutationRate", 0.3));
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
        assertThat(done.progress()).isEqualTo(new JobView.Progress("campaign", 1, 1));
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
        executor.call(() -> 0); // everything queued before this has now been dequeued and run (or skipped)
        assertThat(jobs.get(queued.id()).startedAt()).isNull();
        assertThat(jobs.get(queued.id()).status()).isEqualTo(JobView.Status.CANCELLED);
        assertThat(jobs.get(queued.id()).progress()).isNull();
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
        OptimizeCommand overflow = tiny(3L, false);
        assertThatThrownBy(() -> jobs.startOptimization(overflow)).isInstanceOf(BusyException.class);
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
        // The exception text stays in the log; clients get a generic detail naming the job.
        assertThat(done.error().detail()).doesNotContain("disk on fire").contains(done.id());
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
        assertThat(done.progress()).isEqualTo(new JobView.Progress("campaign", 1, 1));
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

    // ---- adversarial review fixes ----------------------------------------------------------------

    private static JobView awaitWith(JobService service, String id) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            JobView v = service.get(id);
            if (v.status().finished()) {
                return v;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for job " + id);
    }

    @Test
    void anOperatorCanExposeFailureDetail() throws Exception {
        JobService verbose = new JobService(catalogs, executor, store, Clock.systemUTC(), new JobService.Settings(200, 12, true));
        store.failOnSave.set(true);
        JobView done = awaitWith(verbose, verbose.startOptimization(tiny(1L, false)).id());
        assertThat(done.status()).isEqualTo(JobView.Status.FAILED);
        assertThat(done.error().detail()).isEqualTo("disk on fire");
    }

    private List<Double> annotate(String reportId, Long seed) throws InterruptedException {
        JobView job = jobs.startReportCampaign(reportId, 8, seed);
        assertThat(job.seed()).isEqualTo(seed);
        assertThat(awaitFinished(job.id()).status()).isEqualTo(JobView.Status.SUCCEEDED);
        return store.find(reportId).orElseThrow().report().leaderboard().stream().map(Reports.Entry::campaignDayWinRate).toList();
    }

    @Test
    void theReportCampaignSeedChoosesTheDaysAndIsEchoed() throws Exception {
        // Level 5 barbarians clear some days and lose others, so the rates move with the days sampled.
        String reportId = awaitFinished(jobs.startOptimization(
                new OptimizeCommand(5, null, List.of(BuildClass.BARBARIAN), null, TINY, false, false, 9L)).id()).reportId();
        List<Double> first = annotate(reportId, 1L);
        assertThat(annotate(reportId, 1L)).isEqualTo(first); // the same seed reproduces the annotation
        boolean differs = false;
        for (long seed = 2; seed <= 8 && !differs; seed++) {
            differs = !annotate(reportId, seed).equals(first);
        }
        assertThat(differs).as("other seeds sample other days").isTrue();
        // Without a seed one is drawn and echoed, like every other endpoint.
        JobView drawn = jobs.startReportCampaign(reportId, 2, null);
        assertThat(drawn.seed()).isNotNull().isBetween(0L, 4294967295L);
        awaitFinished(drawn.id());
    }

    @Test
    void campaignDaysAreAnOperatorSetting() throws Exception {
        InMemoryReportStore own = new InMemoryReportStore();
        JobService twoDays = new JobService(catalogs, executor, own, Clock.systemUTC(), new JobService.Settings(200, 2, false));
        String reportId = awaitWith(twoDays, twoDays.startOptimization(tiny(4L, true)).id()).reportId();
        // A two-day campaign can only score 0, 0.5 or 1.
        assertThat(own.find(reportId).orElseThrow().report().leaderboard())
                .allSatisfy(e -> assertThat(e.campaignDayWinRate()).isIn(0.0, 0.5, 1.0));
        // An annotation job without days uses the setting too.
        JobView job = twoDays.startReportCampaign(reportId, null, 3L);
        awaitWith(twoDays, job.id());
        assertThat(own.find(reportId).orElseThrow().report().leaderboard())
                .allSatisfy(e -> assertThat(e.campaignDayWinRate()).isIn(0.0, 0.5, 1.0));
    }

    @Test
    void finishedJobRetentionIsAnOperatorSetting() throws Exception {
        JobService keepTwo = new JobService(catalogs, executor, store, Clock.systemUTC(), new JobService.Settings(2, 12, false));
        List<String> ids = new java.util.ArrayList<>();
        for (long seed = 1; seed <= 3; seed++) {
            ids.add(awaitWith(keepTwo, keepTwo.startOptimization(tiny(seed, false)).id()).id());
        }
        keepTwo.startOptimization(tiny(4L, false));
        String forgotten = ids.get(0);
        assertThatThrownBy(() -> keepTwo.get(forgotten)).isInstanceOf(NotFoundException.class);
        assertThat(keepTwo.get(ids.get(1)).status()).isEqualTo(JobView.Status.SUCCEEDED);
        assertThat(JobService.Settings.defaults()).isEqualTo(new JobService.Settings(200, 12, false));
    }
}
