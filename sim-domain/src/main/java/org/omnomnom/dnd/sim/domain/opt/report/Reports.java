package org.omnomnom.dnd.sim.domain.opt.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.AbilityScores;
import org.omnomnom.dnd.sim.domain.core.FloatOrder;
import org.omnomnom.dnd.sim.domain.opt.evaluation.EvalResult;
import org.omnomnom.dnd.sim.domain.opt.evaluation.Objectives;
import org.omnomnom.dnd.sim.domain.opt.genome.Genome;
import org.omnomnom.dnd.sim.domain.opt.genome.Genomes;
import org.omnomnom.dnd.sim.domain.opt.search.Nsga2;
import org.omnomnom.dnd.sim.domain.rng.Seeds;

/**
 * Run reports: turn an NSGA-II result into a plain object holding the Pareto front and the ranked leaderboard, each
 * build with its raw metrics, objective vector and a weighted scalar score.
 *
 * <p>Weighting is a post-hoc summary over the stored objectives: the report includes each objective's min and max, so
 * a client can reweight and re-rank without re-running the simulation. The default weights are equal. Normalization is
 * min-max across the population, a stand-in for the metrics spec's ratio-to-reference-party normalization.
 */
public final class Reports {

    private Reports() {}

    /** Report format version, bumped when the shape changes. */
    public static final int VERSION = 1;

    public record Metrics(double winRate, double avgDamageDealt, double avgHpFracRetained, double avgRounds, int runs) {}

    /**
     * @param campaignDayWinRate adventuring-day win rate (campaign viability), filled in by
     *     {@code Campaign.annotate}; null until annotated
     */
    public record Entry(
            String key,
            int rank,
            Genome genome,
            String description,
            Metrics metrics,
            Map<String, Double> objectives,
            double weightedScore,
            Double campaignDayWinRate) {}

    /**
     * @param config the run configuration, as supplied by the caller
     * @param objectiveBounds per-objective [min, max] over the population, for client-side reweighting
     */
    public record Report(
            int version,
            String runKey,
            Map<String, Object> config,
            List<String> objectiveNames,
            Map<String, double[]> objectiveBounds,
            Map<String, Double> weights,
            List<Entry> paretoFront,
            List<Entry> leaderboard) {}

    /** A short human-readable summary of a build at a level, for example {@code L3 fighter — Longsword, ... — STR 15, CON 14}. */
    public static String describe(Genome g, int level) {
        AbilityScores ab = Genomes.abilitiesFrom(g.abilityAssignment());
        List<Ability> order = new ArrayList<>(List.of(Ability.values()));
        order.sort((a, b) -> Integer.compare(ab.get(b), ab.get(a))); // stable: ties keep str..cha order
        String top = order.stream()
                .limit(2)
                .map(a -> a.code().toUpperCase(Locale.ROOT) + ' ' + ab.get(a))
                .collect(Collectors.joining(", "));
        List<String> gear = new ArrayList<>();
        gear.add(g.twoHanded() ? g.weaponName() + " (2H)" : g.weaponName());
        if (g.shield()) {
            gear.add("shield");
        }
        gear.add(g.armorName() != null ? g.armorName() : "unarmored");
        if (g.fightingStyle() != null) {
            gear.add(g.fightingStyle().code());
        }
        return "L" + level + " " + g.classSlug().code() + " — " + String.join(", ", gear) + " — " + top;
    }

    private static double normalize(double value, double min, double max) {
        return max > min ? (value - min) / (max - min) : 0.5;
    }

    /** The equal-weight default over the objectives. */
    public static Map<String, Double> equalWeights() {
        return Objectives.NAMES.stream().collect(Collectors.toMap(name -> name, name -> 1.0, (a, b) -> a, LinkedHashMap::new));
    }

    /**
     * Score one objective vector against the population bounds under weights. Each objective is min-max normalized
     * then weighted; the sum is divided by the total weight so the score stays in [0, 1].
     */
    public static double weightedScore(double[] objectives, Map<String, double[]> bounds, Map<String, Double> weights) {
        double sum = 0;
        double total = 0;
        for (int i = 0; i < Objectives.NAMES.size(); i++) {
            String name = Objectives.NAMES.get(i);
            double w = weights.getOrDefault(name, 0.0);
            double[] bound = bounds.get(name); // none when the objective has no bounds in this report; it is skipped
            if (w != 0 && bound != null) {
                sum += w * normalize(objectives[i], bound[0], bound[1]);
                total += w;
            }
        }
        return total > 0 ? sum / total : 0;
    }

    public static Report build(Nsga2.Result result, Map<String, Object> config, int level) {
        return build(result, config, level, equalWeights(), 20);
    }

    /** Build the report from an NSGA-II result. */
    public static Report build(Nsga2.Result result, Map<String, Object> config, int level, Map<String, Double> weights, int leaderboardSize) {
        List<Nsga2.Individual> pop = result.population();
        Map<String, double[]> bounds = objectiveBounds(pop);
        Function<Nsga2.Individual, Entry> toEntry = ind -> entryOf(ind, level, bounds, weights);
        Comparator<Entry> byScore = FloatOrder.descendingBy(Entry::weightedScore);

        // De-duplicate by genome key for the leaderboard (the population can repeat elites); the first occurrence wins.
        List<Entry> board = pop.stream()
                .map(toEntry)
                .collect(Collectors.toMap(Entry::key, Function.identity(), (first, repeat) -> first, LinkedHashMap::new))
                .values().stream()
                .sorted(byScore)
                .limit(leaderboardSize)
                .toList();
        List<Entry> front = result.front().stream().map(toEntry).sorted(byScore).toList();

        return new Report(VERSION, runKey(canonicalJson(config)), config, Objectives.NAMES, bounds, weights, List.copyOf(front), board);
    }

    /** Each objective's lowest and highest value across the population. */
    private static Map<String, double[]> objectiveBounds(List<Nsga2.Individual> pop) {
        Map<String, double[]> bounds = new LinkedHashMap<>();
        for (int i = 0; i < Objectives.NAMES.size(); i++) {
            int objective = i;
            double min = pop.stream().mapToDouble(ind -> ind.objective(objective)).min().orElse(Double.POSITIVE_INFINITY);
            double max = pop.stream().mapToDouble(ind -> ind.objective(objective)).max().orElse(Double.NEGATIVE_INFINITY);
            bounds.put(Objectives.NAMES.get(i), new double[] {min, max});
        }
        return bounds;
    }

    private static Entry entryOf(Nsga2.Individual ind, int level, Map<String, double[]> bounds, Map<String, Double> weights) {
        Map<String, Double> objectives = new LinkedHashMap<>();
        for (int i = 0; i < Objectives.NAMES.size(); i++) {
            objectives.put(Objectives.NAMES.get(i), ind.objective(i));
        }
        EvalResult r = ind.result();
        return new Entry(
                Genomes.key(ind.genome()),
                ind.rank(),
                ind.genome(),
                describe(ind.genome(), level),
                new Metrics(r.winRate(), r.avgDamageDealt(), r.avgHpFracRetained(), r.avgRounds(), r.runs()),
                objectives,
                weightedScore(ind.objectives(), bounds, weights),
                null);
    }

    /**
     * A short stable key for a run configuration, given its canonical JSON text (the domain holds no JSON library, so
     * the caller serializes it). Equal text gives an equal key.
     */
    public static String runKey(String configJson) {
        String hex = Long.toHexString(Seeds.seedFrom("run", configJson));
        return "0".repeat(Math.max(0, 8 - hex.length())) + hex;
    }

    /**
     * Compact JSON text for a run configuration: keys in insertion order, whole numbers without a fraction (as
     * JavaScript prints them). Only scalars, lists and nested maps are expected in a run configuration.
     */
    public static String canonicalJson(Map<String, Object> config) {
        StringBuilder sb = new StringBuilder();
        appendJson(sb, config);
        return sb.toString();
    }

    private static void appendJson(StringBuilder sb, Object v) {
        switch (v) {
            case null -> sb.append("null");
            case Map<?, ?> m -> appendMap(sb, m);
            case List<?> l -> appendList(sb, l);
            case String s -> appendString(sb, s);
            case Double d when d == Math.rint(d) && !d.isInfinite() -> sb.append(d.longValue()); // whole numbers print without a fraction
            default -> sb.append(v);
        }
    }

    private static void appendMap(StringBuilder sb, Map<?, ?> map) {
        sb.append('{');
        String separator = "";
        for (Map.Entry<?, ?> e : map.entrySet()) {
            sb.append(separator);
            separator = ",";
            appendString(sb, (String) e.getKey());
            sb.append(':');
            appendJson(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void appendList(StringBuilder sb, List<?> list) {
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            appendJson(sb, list.get(i));
        }
        sb.append(']');
    }

    private static void appendString(StringBuilder sb, String s) {
        sb.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> sb.append(c);
            }
        }
        sb.append('"');
    }

    /** Re-rank an existing report under new weights without re-simulating. */
    public static Report rescore(Report report, Map<String, Double> weights) {
        return new Report(report.version(), report.runKey(), report.config(), report.objectiveNames(), report.objectiveBounds(),
                weights, rescored(report, report.paretoFront(), weights), rescored(report, report.leaderboard(), weights));
    }

    /** The entries with scores recomputed under {@code weights}, best first. */
    private static List<Entry> rescored(Report report, List<Entry> entries, Map<String, Double> weights) {
        return entries.stream()
                .map(e -> {
                    double[] vector = report.objectiveNames().stream().mapToDouble(name -> e.objectives().get(name)).toArray();
                    return new Entry(e.key(), e.rank(), e.genome(), e.description(), e.metrics(), e.objectives(),
                            weightedScore(vector, report.objectiveBounds(), weights), e.campaignDayWinRate());
                })
                .sorted(FloatOrder.descendingBy(Entry::weightedScore))
                .toList();
    }
}
