package org.omnomnom.dnd.sim.application.evaluation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.omnomnom.dnd.sim.application.content.ContentCatalogs;
import org.omnomnom.dnd.sim.application.error.UnprocessableException;
import org.omnomnom.dnd.sim.application.execution.SimLimits;
import org.omnomnom.dnd.sim.application.execution.SimulationExecutor;
import org.omnomnom.dnd.sim.domain.content.build.Role;
import org.omnomnom.dnd.sim.domain.core.Coded;
import org.omnomnom.dnd.sim.domain.opt.campaign.Campaign;
import org.omnomnom.dnd.sim.domain.opt.evaluation.EvalResult;
import org.omnomnom.dnd.sim.domain.opt.evaluation.Interval;
import org.omnomnom.dnd.sim.domain.opt.evaluation.Objectives;
import org.omnomnom.dnd.sim.domain.opt.evaluation.PartyEvalResult;
import org.omnomnom.dnd.sim.domain.opt.evaluation.PartyEvaluator;
import org.omnomnom.dnd.sim.domain.opt.evaluation.SoloEvaluator;
import org.omnomnom.dnd.sim.domain.opt.genome.Genome;
import org.omnomnom.dnd.sim.domain.opt.genome.Genomes;
import org.omnomnom.dnd.sim.domain.opt.report.Reports;
import org.omnomnom.dnd.sim.domain.opt.report.RolePresets;
import org.omnomnom.dnd.sim.domain.rng.Seeds;
import org.omnomnom.dnd.sim.domain.scenario.PartyTemplate;

/**
 * Evaluates one build across the level's scenario library (the CLI {@code eval}) and scores its adventuring day (the
 * CLI {@code campaign} for a single build).
 *
 * <p>The evaluators' random seeds depend only on the scenario and run index (common random numbers), so a request
 * {@code seed} only drives the completion of an under-specified genome; it is echoed so the exact build can be
 * reproduced.
 */
public final class EvaluationService {

    public enum Context implements Coded {
        SOLO("solo"),
        PARTY("party");

        private final String code;

        Context(String code) {
            this.code = code;
        }

        @Override
        public String code() {
            return code;
        }
    }

    /**
     * @param role labels the result and picks the party slot; null or {@code equal} for none
     * @param runsPerScenario null for the default (16 solo, 12 party)
     */
    public record EvalCommand(int level, GenomeInput genome, Context context, String role, Integer runsPerScenario, Long seed) {}

    /**
     * @param winRate 95% interval (Wilson)
     * @param objectives the named objective vector, higher is better
     * @param ci present for the solo context only
     */
    public record EvalOutcome(
            String description,
            int level,
            Context context,
            long seed,
            Genome genome,
            int runs,
            Interval winRate,
            Map<String, Double> objectives,
            Confidence ci,
            List<String> warnings) {}

    public record Confidence(Interval damage, Interval hpRetained) {}

    public record CampaignCommand(int level, GenomeInput genome, int days, double shortRestHealFraction, Long seed) {}

    public record CampaignOutcome(long seed, Genome genome, Interval dayWinRate, double avgEncountersCleared, int encountersPerDay, int days) {}

    private final ContentCatalogs catalogs;
    private final SimulationExecutor executor;
    private final SimLimits limits;

    public EvaluationService(ContentCatalogs catalogs, SimulationExecutor executor) {
        this(catalogs, executor, SimLimits.defaults());
    }

    public EvaluationService(ContentCatalogs catalogs, SimulationExecutor executor, SimLimits limits) {
        this.catalogs = catalogs;
        this.executor = executor;
        this.limits = limits;
    }

    /** The party slot a role's hero fills; roles with no slot (generalist, equal) fall back to the controller. */
    static Role heroRoleFor(String role) {
        for (Role r : Role.values()) {
            if (r.code().equals(role)) {
                return r;
            }
        }
        return Role.CONTROLLER;
    }

    /** Validate a role name: a preset or {@code equal}. */
    public static void requireKnownRole(String role) {
        if (role != null && !role.equals("equal") && !RolePresets.names().contains(role)) {
            throw new UnprocessableException("unknown-role", "unknown role: " + role, "role");
        }
    }

    private static int defaultRuns(Context context) {
        return context == Context.PARTY ? PartyEvaluator.DEFAULT_RUNS_PER_SCENARIO : SoloEvaluator.DEFAULT_RUNS_PER_SCENARIO;
    }

    public EvalOutcome evaluate(EvalCommand cmd) {
        ContentCatalogs.LevelContent level = catalogs.level(cmd.level());
        requireKnownRole(cmd.role());
        int runs = cmd.runsPerScenario() != null ? cmd.runsPerScenario() : defaultRuns(cmd.context());
        // The effective value is checked, defaults included, as campaign and optimize do.
        SimLimits.require(runs, limits.evalRunsPerScenario(), "runs", "runs per scenario");
        long seed = cmd.seed() != null ? cmd.seed() : Seeds.randomSeed();
        Genome genome = GenomeResolver.resolve(cmd.genome(), level.martial(), seed, "genome");
        String description = Reports.describe(genome, cmd.level());
        List<String> warnings = new ArrayList<>();

        if (cmd.context() == Context.PARTY) {
            PartyEvalResult r = executor.call(() -> PartyEvaluator.evaluate(
                    id -> Genomes.build(genome, level.martial(), id), level.partyHarness(), heroRoleFor(cmd.role()), PartyTemplate.ALL, runs));
            return new EvalOutcome(description, cmd.level(), Context.PARTY, seed, genome, r.runs(), r.winRateCi(),
                    named(Objectives.of(r)), null, warnings);
        }
        if (cmd.role() != null && RolePresets.PARTY_ONLY.contains(cmd.role())) {
            warnings.add("role '" + cmd.role() + "' draws on control and support, which only carry signal in the party context");
        }
        EvalResult r = executor.call(() -> SoloEvaluator.evaluate(id -> Genomes.build(genome, level.martial(), id), level.martial().scenarios(), runs));
        return new EvalOutcome(description, cmd.level(), Context.SOLO, seed, genome, r.runs(), r.ci().winRate(), named(Objectives.of(r)),
                new Confidence(r.ci().damage(), r.ci().hpRetained()), warnings);
    }

    public CampaignOutcome campaign(CampaignCommand cmd) {
        ContentCatalogs.LevelContent level = catalogs.level(cmd.level());
        SimLimits.require(cmd.days(), limits.campaignDays(), "days", "simulated days");
        long seed = cmd.seed() != null ? cmd.seed() : Seeds.randomSeed();
        Genome genome = GenomeResolver.resolve(cmd.genome(), level.martial(), seed, "genome");
        Campaign.Result r = executor.call(() -> Campaign.evaluateAdventuringDay(genome, level.martial(), cmd.days(), cmd.shortRestHealFraction(), seed));
        return new CampaignOutcome(seed, genome, r.dayWinRateCi(), r.avgEncountersCleared(), r.encountersPerDay(), r.days());
    }

    static Map<String, Double> named(double[] objectives) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (int i = 0; i < Objectives.NAMES.size(); i++) {
            out.put(Objectives.NAMES.get(i), objectives[i]);
        }
        return out;
    }
}
