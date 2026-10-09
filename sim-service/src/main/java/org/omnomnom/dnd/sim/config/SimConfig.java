package org.omnomnom.dnd.sim.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import org.omnomnom.dnd.sim.adapter.json.SimJacksonModule;
import org.omnomnom.dnd.sim.adapter.out.content.SqliteContentSource;
import org.omnomnom.dnd.sim.application.content.ContentCatalogs;
import org.omnomnom.dnd.sim.application.content.ContentService;
import org.omnomnom.dnd.sim.application.encounter.EncounterService;
import org.omnomnom.dnd.sim.application.evaluation.EvaluationService;
import org.omnomnom.dnd.sim.application.execution.SimLimits;
import org.omnomnom.dnd.sim.application.execution.SimulationExecutor;
import org.omnomnom.dnd.sim.application.job.JobService;
import org.omnomnom.dnd.sim.application.report.ReportService;
import org.omnomnom.dnd.sim.application.report.ReportStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The composition root: builds the immutable catalogs once at startup (the seed database is opened, read and closed),
 * the bounded simulation pool, the use cases and their settings, each as its own bean. Application classes carry no
 * framework annotations; the {@code config} package is the only place they meet Spring.
 */
@Configuration
class SimConfig {

    @Bean
    ContentCatalogs contentCatalogs() {
        try (SqliteContentSource source = SqliteContentSource.open()) {
            return ContentCatalogs.load(source);
        }
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The worker pool, owned by the container: it is started with the context and shut down when the context closes,
     * interrupting running simulations as a restart does. Daemon workers never hold the JVM open. The pool is not a
     * default autowiring candidate, so it neither stands in for Boot's {@code applicationTaskExecutor} nor stops that
     * from being created.
     */
    @Bean(defaultCandidate = false)
    ThreadPoolTaskExecutor simulationPool(SimProperties props) {
        int workers = SimulationExecutor.workerCount(props.executor().threads());
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(workers);
        pool.setMaxPoolSize(workers);
        pool.setQueueCapacity(Math.max(1, props.executor().queueCapacity()));
        pool.setThreadNamePrefix("sim-worker-");
        pool.setDaemon(true);
        pool.setWaitForTasksToCompleteOnShutdown(false);
        return pool;
    }

    /** The pool is closed by its own bean, so this wrapper has no destroy method. */
    @Bean(destroyMethod = "")
    SimulationExecutor simulationExecutor(@Qualifier("simulationPool") ThreadPoolTaskExecutor pool, SimProperties props) {
        return new SimulationExecutor(pool.getThreadPoolExecutor(), (int) props.executor().busyRetryAfter().toSeconds());
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
    JobService.Settings jobSettings(SimProperties props) {
        SimProperties.Jobs jobs = props.jobs();
        return new JobService.Settings(jobs.retainedFinished(), jobs.campaignDays(), jobs.exposeErrorDetail());
    }

    @Bean
    JobService jobService(ContentCatalogs catalogs, SimulationExecutor executor, ReportStore store, Clock clock,
            JobService.Settings settings, SimLimits limits) {
        return new JobService(catalogs, executor, store, clock, settings, limits);
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
