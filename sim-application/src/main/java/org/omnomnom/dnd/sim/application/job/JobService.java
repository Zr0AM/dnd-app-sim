package org.omnomnom.dnd.sim.application.job;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import org.omnomnom.dnd.sim.application.content.ContentCatalogs;
import org.omnomnom.dnd.sim.application.error.BusyException;
import org.omnomnom.dnd.sim.application.error.ConflictException;
import org.omnomnom.dnd.sim.application.error.NotFoundException;
import org.omnomnom.dnd.sim.application.error.UnprocessableException;
import org.omnomnom.dnd.sim.application.evaluation.EvaluationService;
import org.omnomnom.dnd.sim.application.execution.SimLimits;
import org.omnomnom.dnd.sim.application.execution.SimulationExecutor;
import org.omnomnom.dnd.sim.application.report.ReportStore;
import org.omnomnom.dnd.sim.domain.core.Tiers;
import org.omnomnom.dnd.sim.domain.opt.campaign.Campaign;
import org.omnomnom.dnd.sim.domain.opt.genome.BuildClass;
import org.omnomnom.dnd.sim.domain.opt.report.Reports;
import org.omnomnom.dnd.sim.domain.opt.report.RolePresets;
import org.omnomnom.dnd.sim.domain.opt.search.Nsga2;
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

    /** The progress stage reported while a campaign (adventuring-day) pass runs. */
    private static final String STAGE_CAMPAIGN = "campaign";

    /** The run-configuration key that records whether the campaign pass was asked for. */
    private static final String CONFIG_CAMPAIGN = "campaign";

    /** Effort presets: population, generations, evaluation runs per scenario, mutation rate. */
    record Effort(int populationSize, int generations, int evalRuns, double mutationRate) {}

    private static final Map<String, Effort> PRESETS = Map.of(
            "quick", new Effort(16, 6, 8, 0.3),
            "standard", new Effort(32, 12, 12, 0.3),
            "thorough", new Effort(64, 24, 20, 0.3));

    /** The campaign pass of an optimization and the report annotation job use this many simulated days by default. */
    public static final int DEFAULT_CAMPAIGN_DAYS = 12;

    /**
     * Operator settings for jobs.
     *
     * @param retainedFinishedJobs how many finished jobs are remembered before the oldest are forgotten
     * @param campaignDays simulated days for the campaign pass of an optimization and the default for report annotation
     * @param exposeErrorDetail put a failed job's exception message in its problem detail; off by default because the
     *     message can name internal paths or upstream responses
     */
    public record Settings(int retainedFinishedJobs, int campaignDays, boolean exposeErrorDetail) {

        public static Settings defaults() {
            return new Settings(200, DEFAULT_CAMPAIGN_DAYS, false);
        }
    }

    private final ContentCatalogs catalogs;
    private final SimulationExecutor executor;
    private final ReportStore store;
    private final Clock clock;
    private final SimLimits limits;
    private final Settings settings;
    private final Map<String, Job> jobs = new LinkedHashMap<>();

    public JobService(ContentCatalogs catalogs, SimulationExecutor executor, ReportStore store, Clock clock) {
        this(catalogs, executor, store, clock, Settings.defaults(), SimLimits.defaults());
    }

    public JobService(ContentCatalogs catalogs, SimulationExecutor executor, ReportStore store, Clock clock, Settings settings) {
        this(catalogs, executor, store, clock, settings, SimLimits.defaults());
    }

    public JobService(ContentCatalogs catalogs, SimulationExecutor executor, ReportStore store, Clock clock, SimLimits limits) {
        this(catalogs, executor, store, clock, Settings.defaults(), limits);
    }

    public JobService(ContentCatalogs catalogs, SimulationExecutor executor, ReportStore store, Clock clock, Settings settings,
            SimLimits limits) {
        this.catalogs = catalogs;
        this.executor = executor;
        this.store = store;
        this.clock = clock;
        this.limits = limits;
        this.settings = settings;
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

        /** Fail the job unless it already ended; true if it did. The backstop for a job that ended without being settled. */
        synchronized boolean failUnlessFinished(JobView.Problem problem) {
            if (status.finished()) {
                return false;
            }
            fail(problem);
            return true;
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
        config.put(CONFIG_CAMPAIGN, cmd.campaign());
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
                job.progress(STAGE_CAMPAIGN, 0, 1);
                report = Campaign.annotate(report, level.martial(), settings.campaignDays(), Campaign.DEFAULT_SHORT_REST_HEAL_FRACTION, seed);
                job.progress(STAGE_CAMPAIGN, 1, 1);
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
     * overwritten, as the CLI did. The days are seeded from {@code seed} (drawn and echoed when omitted), and every build
     * in the report faces the same days.
     */
    public JobView startReportCampaign(String reportId, Integer days, Long seed) {
        Reports.Report report = store.find(reportId).map(ReportStore.Stored::report)
                .orElseThrow(() -> new NotFoundException("report-not-found", "no report with id " + reportId));
        int level = report.config().get("level") instanceof Number n ? n.intValue() : -1;
        ContentCatalogs.LevelContent content = catalogs.level(level);
        int simulatedDays = days != null ? days : settings.campaignDays();
        SimLimits.require(simulatedDays, limits.campaignDays(), "days", "simulated days");
        long daySeed = seed != null ? seed : Seeds.randomSeed();

        Job job = new Job(JobView.Kind.CAMPAIGN, daySeed, List.of());
        return submit(job, () -> {
            job.progress(STAGE_CAMPAIGN, 0, 1);
            Reports.Report annotated = Campaign.annotate(report, content.martial(), simulatedDays, Campaign.DEFAULT_SHORT_REST_HEAL_FRACTION, daySeed);
            if (job.cancelRequested()) {
                throw new Nsga2.CancelledException();
            }
            store.save(annotated);
            job.progress(STAGE_CAMPAIGN, 1, 1);
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
            base = PRESETS.get(Tiers.pick(level, "standard", Tiers.from(11, "thorough")));
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

    private JobView submit(Job job, Callable<String> work) {
        synchronized (this) {
            jobs.put(job.id, job);
            forgetOldFinishedJobs();
        }
        Future<?> future;
        try {
            future = executor.submit(() -> run(job, work));
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

    /** Run a job's work on an executor thread and settle the job whichever way it ends. */
    private Void run(Job job, Callable<String> work) {
        if (!job.start()) {
            return null; // cancelled while queued
        }
        try {
            job.succeed(work.call());
        } catch (Nsga2.CancelledException e) {
            job.cancelled();
        } catch (Exception e) {
            LOG.error("job {} failed", job.id, e);
            job.fail(failure(detailFor(job, e)));
        } finally {
            // An Error (out of memory, a stack overflow) is not caught above and ends the thread's task; the job must
            // still reach a final state rather than stay RUNNING forever.
            if (job.failUnlessFinished(failure(genericDetail(job)))) {
                LOG.error("job {} ended without being settled; it was failed", job.id);
            }
        }
        return null;
    }

    private static JobView.Problem failure(String detail) {
        return new JobView.Problem("urn:dnd-app-sim:problem:job-failed", "Job failed", 500, detail, "job-failed");
    }

    /** The detail a failed job reports: the exception's message if the operator allows it, else a generic note. */
    private String detailFor(Job job, Exception e) {
        if (!settings.exposeErrorDetail()) {
            return genericDetail(job);
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static String genericDetail(Job job) {
        return "The job could not be completed; the details are in the service log (job " + job.id + ").";
    }

    private void forgetOldFinishedJobs() {
        long finished = jobs.values().stream().filter(j -> j.view().status().finished()).count();
        if (finished <= settings.retainedFinishedJobs()) {
            return;
        }
        var it = jobs.values().iterator();
        long excess = finished - settings.retainedFinishedJobs();
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
