package org.omnomnom.dnd.sim.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.adapter.out.content.SqliteContentSource;
import org.omnomnom.dnd.sim.application.content.ContentCatalogs;
import org.omnomnom.dnd.sim.application.encounter.EncounterCommand;
import org.omnomnom.dnd.sim.application.encounter.EncounterService;
import org.omnomnom.dnd.sim.application.encounter.PartyMemberSpec;
import org.omnomnom.dnd.sim.application.error.UnprocessableException;
import org.omnomnom.dnd.sim.application.evaluation.EvaluationService;
import org.omnomnom.dnd.sim.application.evaluation.GenomeInput;
import org.omnomnom.dnd.sim.application.execution.SimLimits;
import org.omnomnom.dnd.sim.application.execution.SimulationExecutor;
import org.omnomnom.dnd.sim.application.job.JobService;
import org.omnomnom.dnd.sim.application.job.JobView;
import org.omnomnom.dnd.sim.application.job.OptimizeCommand;
import org.omnomnom.dnd.sim.domain.content.build.Role;
import org.omnomnom.dnd.sim.testsupport.InMemoryReportStore;

/** Operator limits turn too-large requests into {@code 422 limit-exceeded} before any work is queued. */
class LimitsTest {

    static ContentCatalogs catalogs;
    static SimulationExecutor executor;

    static final SimLimits TIGHT = new SimLimits(5, 4, 3, 8, 6, 2, 2_000);

    @BeforeAll
    static void load() {
        try (SqliteContentSource source = SqliteContentSource.open()) {
            catalogs = ContentCatalogs.load(source);
        }
        executor = new SimulationExecutor(1, 4);
    }

    @AfterAll
    static void close() {
        executor.close();
    }

    private static void assertExceeded(Runnable call, String field) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(UnprocessableException.class, e -> {
            assertThat(e.code()).isEqualTo("limit-exceeded");
            assertThat(e.errors()).singleElement().satisfies(fe -> assertThat(fe.field()).isEqualTo(field));
        });
    }

    private static EncounterCommand encounter(int runs) {
        return new EncounterCommand(3, List.of(new PartyMemberSpec.Filler(null, Role.TANK)),
                List.of(new EncounterCommand.EnemyGroup("goblin-warrior", 1)), null, null, runs, 1L, 50, false, 0);
    }

    @Test
    void encounterRunsAreCapped() {
        EncounterService service = new EncounterService(catalogs, executor, TIGHT);
        assertExceeded(() -> service.simulate(encounter(6)), "runs");
        assertThatCode(() -> service.simulate(encounter(5))).doesNotThrowAnyException();
    }

    @Test
    void evaluationRunsAndCampaignDaysAreCapped() {
        EvaluationService service = new EvaluationService(catalogs, executor, TIGHT);
        GenomeInput fighter = GenomeInput.of(org.omnomnom.dnd.sim.domain.opt.genome.BuildClass.FIGHTER);
        assertExceeded(() -> service.evaluate(new EvaluationService.EvalCommand(3, fighter, EvaluationService.Context.SOLO, null, 5, 1L)), "runs");
        assertThatCode(() -> service.evaluate(new EvaluationService.EvalCommand(3, fighter, EvaluationService.Context.SOLO, null, 4, 1L)))
                .doesNotThrowAnyException();
        // The defaults count too: 16 solo and 12 party runs per scenario exceed a limit of 4.
        assertExceeded(() -> service.evaluate(new EvaluationService.EvalCommand(3, fighter, EvaluationService.Context.SOLO, null, null, 1L)), "runs");
        assertExceeded(() -> service.evaluate(new EvaluationService.EvalCommand(5, fighter, EvaluationService.Context.PARTY, null, null, 1L)), "runs");
        SimLimits twelve = new SimLimits(5, 12, 3, 8, 6, 2, 2_000);
        EvaluationService roomy = new EvaluationService(catalogs, executor, twelve);
        var partyWithinLimit = new EvaluationService.EvalCommand(5, fighter, EvaluationService.Context.PARTY, null, 1, 1L);
        var soloOverLimit = new EvaluationService.EvalCommand(3, fighter, EvaluationService.Context.SOLO, null, null, 1L);
        assertThatCode(() -> roomy.evaluate(partyWithinLimit)).doesNotThrowAnyException();
        assertThatThrownBy(() -> roomy.evaluate(soloOverLimit))
                .isInstanceOf(UnprocessableException.class);
        assertExceeded(() -> service.campaign(new EvaluationService.CampaignCommand(3, fighter, 4, 0.5, 1L)), "days");
        assertThatCode(() -> service.campaign(new EvaluationService.CampaignCommand(3, fighter, 3, 0.5, 1L))).doesNotThrowAnyException();
    }

    @Test
    void optimizationSizeIsCappedFieldByField() {
        JobService jobs = new JobService(catalogs, executor, new InMemoryReportStore(), Clock.systemUTC(), TIGHT);
        assertExceeded(() -> jobs.startOptimization(cmd(9, 2, 1)), "ga.populationSize");
        assertExceeded(() -> jobs.startOptimization(cmd(4, 7, 1)), "ga.generations");
        assertExceeded(() -> jobs.startOptimization(cmd(4, 2, 3)), "ga.evalRuns");
    }

    @Test
    void optimizationTotalFightsAreCapped() {
        JobService jobs = new JobService(catalogs, executor, new InMemoryReportStore(), Clock.systemUTC(), TIGHT);
        // 8 x (6 + 1) x 2 x 5 scenarios = 560 fights is fine; the same shape at the level-3 default effort is not.
        assertThatCode(() -> jobs.cancel(jobs.startOptimization(cmd(8, 6, 2)).id())).doesNotThrowAnyException();
        assertThatThrownBy(() -> jobs.startOptimization(new OptimizeCommand(3, null, null, "thorough", null, false, false, 1L)))
                .isInstanceOfSatisfying(UnprocessableException.class, e -> assertThat(e.code()).isEqualTo("limit-exceeded"));
        SimLimits fightsOnly = new SimLimits(5, 4, 3, 128, 100, 64, 559);
        JobService small = new JobService(catalogs, executor, new InMemoryReportStore(), Clock.systemUTC(), fightsOnly);
        assertThatThrownBy(() -> small.startOptimization(cmd(8, 6, 2)))
                .isInstanceOfSatisfying(UnprocessableException.class, e -> {
                    assertThat(e.code()).isEqualTo("limit-exceeded");
                    assertThat(e.getMessage()).contains("560 fights");
                });
    }

    @Test
    void theReportCampaignJobIsCappedByDays() {
        InMemoryReportStore store = new InMemoryReportStore();
        store.save(org.omnomnom.dnd.sim.testsupport.TestReports.report("0123abcd", 0.5));
        JobService jobs = new JobService(catalogs, executor, store, Clock.systemUTC(), TIGHT);
        assertExceeded(() -> jobs.startReportCampaign("0123abcd", 4, null), "days");
        JobView ok = jobs.startReportCampaign("0123abcd", 3, null);
        jobs.cancel(ok.id());
    }

    @Test
    void defaultsMatchTheDocumentedProposals() {
        SimLimits d = SimLimits.defaults();
        assertThat(d.encounterRuns()).isEqualTo(2000);
        assertThat(d.evalRunsPerScenario()).isEqualTo(200);
        assertThat(d.campaignDays()).isEqualTo(100);
        assertThat(d.optimizePopulation()).isEqualTo(128);
        assertThat(d.optimizeGenerations()).isEqualTo(100);
        assertThat(d.optimizeEvalRuns()).isEqualTo(64);
        assertThat(d.optimizeMaxFights()).isEqualTo(2_000_000L);
    }

    private static OptimizeCommand cmd(int population, int generations, int evalRuns) {
        return new OptimizeCommand(3, null, null, null, new OptimizeCommand.GaParams(population, generations, evalRuns, null), false, false, 1L);
    }
}
