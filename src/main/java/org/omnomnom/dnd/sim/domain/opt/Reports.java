package org.omnomnom.dnd.sim.domain.opt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.AbilityScores;
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
     *     {@link Campaign#annotate}; null until annotated
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
        StringBuilder top = new StringBuilder();
        for (int i = 0; i < 2; i++) {
            if (i > 0) {
                top.append(", ");
            }
            top.append(order.get(i).code().toUpperCase(java.util.Locale.ROOT)).append(' ').append(ab.get(order.get(i)));
        }
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
        Map<String, Double> w = new LinkedHashMap<>();
        for (String name : Objectives.NAMES) {
            w.put(name, 1.0);
        }
        return w;
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
            if (w == 0) {
                continue;
            }
            double[] bound = bounds.get(name);
            if (bound == null) {
                continue; // objective has no bounds in this report; skip
            }
            sum += w * normalize(objectives[i], bound[0], bound[1]);
            total += w;
        }
        return total > 0 ? sum / total : 0;
    }

    public static Report build(Nsga2.Result result, Map<String, Object> config, int level) {
        return build(result, config, level, equalWeights(), 20);
    }

    /** Build the report from an NSGA-II result. */
    public static Report build(Nsga2.Result result, Map<String, Object> config, int level, Map<String, Double> weights, int leaderboardSize) {
        List<Nsga2.Individual> pop = result.population();

        Map<String, double[]> bounds = new LinkedHashMap<>();
        for (int i = 0; i < Objectives.NAMES.size(); i++) {
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (Nsga2.Individual ind : pop) {
                min = Math.min(min, ind.objective(i));
                max = Math.max(max, ind.objective(i));
            }
            bounds.put(Objectives.NAMES.get(i), new double[] {min, max});
        }

        java.util.function.Function<Nsga2.Individual, Entry> toEntry = ind -> {
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
        };
        Comparator<Entry> byScore = (a, b) -> signOf(b.weightedScore() - a.weightedScore());

        // De-duplicate by genome key for the leaderboard (the population can repeat elites).
        Set<String> seen = new HashSet<>();
        List<Entry> leaderboard = new ArrayList<>();
        for (Nsga2.Individual ind : pop) {
            Entry e = toEntry.apply(ind);
            if (seen.add(e.key())) {
                leaderboard.add(e);
            }
        }
        leaderboard.sort(byScore);
        List<Entry> board = List.copyOf(leaderboard.subList(0, Math.min(leaderboardSize, leaderboard.size())));

        List<Entry> front = new ArrayList<>();
        for (Nsga2.Individual ind : result.front()) {
            front.add(toEntry.apply(ind));
        }
        front.sort(byScore);

        return new Report(VERSION, runKey(configJson(config)), config, Objectives.NAMES, bounds, weights, List.copyOf(front), board);
    }

    private static int signOf(double d) {
        return d > 0 ? 1 : d < 0 ? -1 : 0;
    }

    /**
     * A short stable key for a run configuration, given its canonical JSON text (the domain holds no JSON library, so
     * the caller serializes it). Equal text gives an equal key.
     */
    public static String runKey(String configJson) {
        String hex = Long.toHexString(Seeds.seedFrom("run", configJson));
        return "0".repeat(Math.max(0, 8 - hex.length())) + hex;
    }

    private static String configJson(Map<String, Object> config) {
        // Insertion-ordered, compact; only scalars, lists and nested maps are expected in a run configuration.
        StringBuilder sb = new StringBuilder();
        appendJson(sb, config);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendJson(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                appendString(sb, e.getKey());
                sb.append(':');
                appendJson(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List<?> l) {
            sb.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                appendJson(sb, l.get(i));
            }
            sb.append(']');
        } else if (v instanceof String s) {
            appendString(sb, s);
        } else if (v instanceof Double d && d == Math.rint(d) && !d.isInfinite()) {
            sb.append(d.longValue());
        } else {
            sb.append(v);
        }
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
        java.util.function.UnaryOperator<List<Entry>> rescoreAll = entries -> {
            List<Entry> out = new ArrayList<>();
            for (Entry e : entries) {
                double[] vec = new double[report.objectiveNames().size()];
                for (int i = 0; i < vec.length; i++) {
                    vec[i] = e.objectives().get(report.objectiveNames().get(i));
                }
                out.add(new Entry(e.key(), e.rank(), e.genome(), e.description(), e.metrics(), e.objectives(),
                        weightedScore(vec, report.objectiveBounds(), weights), e.campaignDayWinRate()));
            }
            out.sort((a, b) -> signOf(b.weightedScore() - a.weightedScore()));
            return List.copyOf(out);
        };
        return new Report(report.version(), report.runKey(), report.config(), report.objectiveNames(), report.objectiveBounds(),
                weights, rescoreAll.apply(report.paretoFront()), rescoreAll.apply(report.leaderboard()));
    }
}
