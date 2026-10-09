package org.omnomnom.dnd.sim.config;

import org.omnomnom.dnd.sim.adapter.json.SimJacksonModule;
import org.omnomnom.dnd.sim.adapter.out.SimProperties;
import org.omnomnom.dnd.sim.adapter.out.content.SqliteContentSource;
import org.omnomnom.dnd.sim.application.ContentCatalogs;
import org.omnomnom.dnd.sim.application.ContentService;
import org.omnomnom.dnd.sim.application.EncounterService;
import org.omnomnom.dnd.sim.application.EvaluationService;
import org.omnomnom.dnd.sim.application.JobService;
import org.omnomnom.dnd.sim.application.ReportService;
import org.omnomnom.dnd.sim.application.ReportStore;
import org.omnomnom.dnd.sim.application.SimLimits;
import org.omnomnom.dnd.sim.application.SimulationExecutor;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The composition root: builds the immutable catalogs once at startup (the seed database is opened, read and closed),
 * the bounded simulation executor, and the use cases. Application classes carry no framework annotations; this is the
 * only place they meet Spring.
 */
@Configuration
class SimConfig {

    @Bean
    ContentCatalogs contentCatalogs() {
        try (SqliteContentSource source = SqliteContentSource.open()) {
            return ContentCatalogs.load(source);
        }
    }

    @Bean(destroyMethod = "close")
    SimulationExecutor simulationExecutor(SimProperties props) {
        return new SimulationExecutor(props.executor().threads(), props.executor().queueCapacity(),
                (int) props.executor().busyRetryAfter().toSeconds());
    }

    @Bean
    SimLimits simLimits(SimProperties props) {
        SimProperties.Limits l = props.limits();
        return new SimLimits(l.encounterRuns(), l.evalRunsPerScenario(), l.campaignDays(), l.optimizePopulation(), l.optimizeGenerations(),
                l.optimizeEvalRuns(), l.optimizeMaxFights());
    }

    @Bean
    EncounterService encounterService(ContentCatalogs catalogs, SimulationExecutor executor, SimLimits limits) {
        return new EncounterService(catalogs, executor, limits);
    }

    @Bean
    EvaluationService evaluationService(ContentCatalogs catalogs, SimulationExecutor executor, SimLimits limits) {
        return new EvaluationService(catalogs, executor, limits);
    }

    @Bean
    MeterBinder executorMetrics(SimulationExecutor executor) {
        return registry -> Gauge.builder("sim.executor.queued", executor, SimulationExecutor::queued)
                .description("Simulation tasks waiting for a worker")
                .register(registry);
    }

    @Bean
    ReportService reportService(ReportStore store) {
        return new ReportService(store);
    }

    @Bean
    JobService jobService(ContentCatalogs catalogs, SimulationExecutor executor, ReportStore store, Clock clock, SimLimits limits,
            SimProperties props) {
        SimProperties.Jobs jobs = props.jobs();
        return new JobService(catalogs, executor, store, clock,
                new JobService.Settings(jobs.retainedFinished(), jobs.campaignDays(), jobs.exposeErrorDetail()), limits);
    }

    @Bean
    ContentService contentService(ContentCatalogs catalogs) {
        return new ContentService(catalogs);
    }

    @Bean
    SimJacksonModule simJacksonModule() {
        return new SimJacksonModule();
    }
}
