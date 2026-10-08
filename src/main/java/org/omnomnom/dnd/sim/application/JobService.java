package org.omnomnom.dnd.sim.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import org.omnomnom.dnd.sim.domain.opt.BuildClass;
import org.omnomnom.dnd.sim.domain.opt.Campaign;
import org.omnomnom.dnd.sim.domain.opt.Nsga2;
import org.omnomnom.dnd.sim.domain.opt.Reports;
import org.omnomnom.dnd.sim.domain.opt.RolePresets;
import org.omnomnom.dnd.sim.domain.rng.LabeledRandom;
import org.omnomnom.dnd.sim.domain.rng.Seeds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the long work (an optimization, or the adventuring-day annotation of a saved report) on the simulation executor
 * and tracks it. Jobs live in memory: a restart loses queued and running jobs, while finished work lives in the report
 * store. Cancellation is cooperative, honored at generation boundaries.
 */
public final class JobService {

    private static final Logger LOG = LoggerFactory.getLogger(JobService.class);

    /** Effort presets: population, generations, evaluation runs per scenario, mutation rate. */
    record Effort(int populationSize, int generations, int evalRuns, double mutationRate) {}

    private static final Map<String, Effort> PRESETS = Map.of(
            "quick", new Effort(16, 6, 8, 0.3),
            "standard", new Effort(32, 12, 12, 0.3),
            "thorough", new Effort(64, 24, 20, 0.3));

    /** The campaign pass of an optimization and the report annotation job use this many simulated days by default. */
    public static final int DEFAULT_CAMPAIGN_DAYS = 12;

    /** How many finished jobs are remembered before the oldest are forgotten. */
    private static final int RETAINED_FINISHED_JOBS = 200;

    private final ContentCatalogs catalogs;
    private final SimulationExecutor executor;
    private final ReportStore store;
    private final Clock clock;
    private final SimLimits limits;
    private final Map<String, Job> jobs = new LinkedHashMap<>();

    public JobService(ContentCatalogs catalogs, SimulationExecutor executor, ReportStore store, Clock clock) {
        this(catalogs, executor, store, clock, SimLimits.defaults());
    }

    public JobService(ContentCatalogs catalogs, SimulationExecutor executor, ReportStore store, Clock clock, SimLimits limits) {
        this.catalogs = catalogs;
        this.executor = executor;
        this.store = store;
        this.clock = clock;
        this.limits = limits;
    }

    /** The mutable state of one job; every access goes through the job's monitor. */
    private final class Job {
        private final String id = UUID.randomUUID().toString();
        private final JobView.Kind kind;
        private final Instant createdAt = clock.instant();
        private final Long seed;
        private final List<String> warnings;
        private JobView.Status status = JobView.Status.QUEUED;
        private Instant startedAt;
        private Instant finishedAt;
        private JobView.Progress progress;
        private String reportId;
        private JobView.Problem error;
        private boolean cancelRequested;
        private Future<?> future;

        Job(JobView.Kind kind, Long seed, List<String> warnings) {
            this.kind = kind;
            this.seed = seed;
            this.warnings = List.copyOf(warnings);
        }

        synchronized JobView view() {
            return new JobView(id, kind, status, createdAt, startedAt, finishedAt, seed, progress, reportId, warnings, error);
        }

        synchronized boolean start() {
            if (cancelRequested || status != JobView.Status.QUEUED) {
                return false;
            }
            status = JobView.Status.RUNNING;
            startedAt = clock.instant();
            return true;
        }

        synchronized void progress(String phase, int completed, int total) {
            progress = new JobView.Progress(phase, completed, total);
        }

        synchronized boolean cancelRequested() {
            return cancelRequested;
        }

        synchronized void succeed(String report) {
            reportId = report;
            finish(JobView.Status.SUCCEEDED);
        }

        synchronized void fail(JobView.Problem problem) {
            error = problem;
            finish(JobView.Status.FAILED);
        }

        synchronized void cancelled() {
            finish(JobView.Status.CANCELLED);
        }

        private void finish(JobView.Status end) {
            if (!status.finished()) {
                status = end;
                finishedAt = clock.instant();
            }
        }

        /** Request cancellation; a job that has not started is cancelled at once. */
        synchronized JobView.Status requestCancel() {
            if (status.finished()) {
                return status;
            }
            cancelRequested = true;
            if (status == JobView.Status.QUEUED) {
                if (future != null) {
                    future.cancel(false);
                }
                finish(JobView.Status.CANCELLED);
            }
            return status;
        }
    }

    // ---- starting jobs -------------------------------------------------------------------------

    public JobView startOptimization(OptimizeCommand cmd) {
        ContentCatalogs.LevelContent level = catalogs.level(cmd.level());
        if (cmd.party()) {
            throw new UnprocessableException("unsupported-context", "party-context optimization is not supported yet; use context solo", "context");
        }
        String role = cmd.role() == null ? "equal" : cmd.role();
        EvaluationService.requireKnownRole(role);
        List<BuildClass> classes = cmd.classes() == null ? List.of() : cmd.classes().stream().distinct().toList();
        Effort effort = effort(cmd.level(), cmd.preset(), cmd.ga());
        SimLimits.require(effort.populationSize(), limits.optimizePopulation(), "ga.populationSize", "the population size");
        SimLimits.require(effort.generations(), limits.optimizeGenerations(), "ga.generations", "the number of generations");
        SimLimits.require(effort.evalRuns(), limits.optimizeEvalRuns(), "ga.evalRuns", "evaluation runs per scenario");
        long fights = (long) effort.populationSize() * (effort.generations() + 1) * effort.evalRuns() * level.martial().scenarios().size();
        SimLimits.require(fights, limits.optimizeMaxFights(), "ga", "the optimization (up to " + fights + " fights)");
        long seed = cmd.seed() != null ? cmd.seed() : Seeds.randomSeed();

        List<String> warnings = new ArrayList<>();
        if (RolePresets.PARTY_ONLY.contains(role)) {
            warnings.add("role '" + role + "' draws on control and support, which only carry signal in a party context; a solo run ranks it by "
                    + "the remaining axes");
        }

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("level", cmd.level());
        config.put("role", role);
        config.put("classes", classes.stream().map(BuildClass::code).toList());
        Map<String, Object> ga = new LinkedHashMap<>();
        ga.put("populationSize", effort.populationSize());
        ga.put("generations", effort.generations());
        ga.put("evalRuns", effort.evalRuns());
        ga.put("mutationRate", effort.mutationRate());
        config.put("ga", ga);
        config.put("campaign", cmd.campaign());
        config.put("seed", seed);

        Job job = new Job(JobView.Kind.OPTIMIZE, seed, warnings);
        return submit(job, () -> {
            Nsga2.Options opts = Nsga2.Options.defaults()
                    .withPopulation(effort.populationSize())
                    .withGenerations(effort.generations())
                    .withMutationRate(effort.mutationRate())
                    .withEvalRuns(effort.evalRuns())
                    .withClasses(classes)
                    .withCancelled(job::cancelRequested)
                    .withProgress(new Nsga2.ProgressListener() {
                        @Override
                        public void onInitialPopulation() {
                            job.progress("initial-population", 1, 1);
                        }

                        @Override
                        public void onGeneration(int completed, int total) {
                            job.progress("generation", completed, total);
                        }
                    });
            Nsga2.Result result = Nsga2.run(level.martial(), new LabeledRandom(seed).child("nsga2"), opts);
            Map<String, Double> weights = role.equals("equal") ? Reports.equalWeights() : RolePresets.weights(role);
            Reports.Report report = Reports.build(result, config, cmd.level(), weights, 20);
            if (cmd.campaign()) {
                job.progress("campaign", 0, 1);
                report = Campaign.annotate(report, level.martial(), DEFAULT_CAMPAIGN_DAYS, Campaign.DEFAULT_SHORT_REST_HEAL_FRACTION);
                job.progress("campaign", 1, 1);
            }
            if (job.cancelRequested()) {
                throw new Nsga2.CancelledException();
            }
            store.save(report);
            return report.runKey();
        });
    }

    /**
     * Annotate a saved report with each build's adventuring-day win rate; on success the report with the same id is
     * overwritten, as the CLI did.
     */
    public JobView startReportCampaign(String reportId, Integer days, Long seed) {
        Reports.Report report = store.find(reportId).map(ReportStore.Stored::report)
                .orElseThrow(() -> new NotFoundException("report-not-found", "no report with id " + reportId));
        int level = report.config().get("level") instanceof Number n ? n.intValue() : -1;
        ContentCatalogs.LevelContent content = catalogs.level(level);
        int simulatedDays = days != null ? days : DEFAULT_CAMPAIGN_DAYS;
        SimLimits.require(simulatedDays, limits.campaignDays(), "days", "simulated days");

        Job job = new Job(JobView.Kind.CAMPAIGN, seed, List.of());
        return submit(job, () -> {
            job.progress("campaign", 0, 1);
            Reports.Report annotated = Campaign.annotate(report, content.martial(), simulatedDays, Campaign.DEFAULT_SHORT_REST_HEAL_FRACTION);
            if (job.cancelRequested()) {
                throw new Nsga2.CancelledException();
            }
            store.save(annotated);
            job.progress("campaign", 1, 1);
            return annotated.runKey();
        });
    }

    /**
     * The effort a request asks for: the named preset, else the level's default (thorough from level 11, otherwise
     * standard), with any explicit field overriding the preset field by field.
     */
    static Effort effort(int level, String preset, OptimizeCommand.GaParams ga) {
        Effort base;
        if (preset != null) {
            base = PRESETS.get(preset);
            if (base == null) {
                throw new UnprocessableException("unknown-preset", "preset must be quick, standard or thorough", "preset");
            }
        } else {
            base = PRESETS.get(level >= 11 ? "thorough" : "standard");
        }
        if (ga == null) {
            return base;
        }
        return new Effort(
                ga.populationSize() != null ? ga.populationSize() : base.populationSize(),
                ga.generations() != null ? ga.generations() : base.generations(),
                ga.evalRuns() != null ? ga.evalRuns() : base.evalRuns(),
                ga.mutationRate() != null ? ga.mutationRate() : base.mutationRate());
    }

    private JobView submit(Job job, java.util.concurrent.Callable<String> work) {
        synchronized (this) {
            jobs.put(job.id, job);
            forgetOldFinishedJobs();
        }
        Future<?> future;
        try {
            future = executor.submit(() -> {
                if (!job.start()) {
                    return null; // cancelled while queued
                }
                try {
                    job.succeed(work.call());
                } catch (Nsga2.CancelledException e) {
                    job.cancelled();
                } catch (Throwable t) {
                    LOG.error("job {} failed", job.id, t);
                    job.fail(new JobView.Problem("urn:dnd-app-sim:problem:job-failed", "Job failed", 500,
                            t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(), "job-failed"));
                }
                return null;
            });
        } catch (BusyException e) {
            synchronized (this) {
                jobs.remove(job.id);
            }
            throw e;
        }
        synchronized (job) {
            job.future = future;
        }
        return job.view();
    }

    private void forgetOldFinishedJobs() {
        long finished = jobs.values().stream().filter(j -> j.view().status().finished()).count();
        if (finished <= RETAINED_FINISHED_JOBS) {
            return;
        }
        var it = jobs.values().iterator();
        long excess = finished - RETAINED_FINISHED_JOBS;
        while (it.hasNext() && excess > 0) {
            if (it.next().view().status().finished()) {
                it.remove();
                excess--;
            }
        }
    }

    // ---- inspecting and cancelling ---------------------------------------------------------------

    private synchronized Job find(String id) {
        Job job = jobs.get(id);
        if (job == null) {
            throw new NotFoundException("job-not-found", "no job with id " + id);
        }
        return job;
    }

    public JobView get(String id) {
        return find(id).view();
    }

    /**
     * Request cancellation. A queued job is cancelled at once; a running one stops at the next generation boundary.
     * Throws {@link ConflictException} if the job already finished.
     */
    public JobView cancel(String id) {
        Job job = find(id);
        if (job.view().status().finished()) {
            throw new ConflictException("job-finished", "job " + id + " already " + job.view().status().code());
        }
        job.requestCancel();
        return job.view();
    }
}
