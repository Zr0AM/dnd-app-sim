package org.omnomnom.dnd.sim.domain.opt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.omnomnom.dnd.sim.domain.rng.LabeledRandom;
import org.omnomnom.dnd.sim.domain.rng.Rng;

/**
 * NSGA-II multi-objective optimization returning a Pareto front. Objectives are all maximized (the evaluator orients
 * them that way). The algorithmic cores, fast non-dominated sort and crowding distance, are pure functions over
 * objective vectors; the optimizer wraps them around the genome and the simulator.
 *
 * <p>Why NSGA-II over a scalar GA: the metrics are genuinely multi-objective (a glass cannon and a tank are both
 * optimal in different trades), so a single weighted score hides the trade-off. The whole front is returned; a
 * weighted score is then a configurable summary over it, so reweighting never needs a re-run.
 *
 * <p>A run is single-threaded and deterministic under its {@link LabeledRandom}. Evaluation is a pure function of the
 * genome (common random numbers), so results are cached by genome key.
 */
public final class Nsga2 {

    private Nsga2() {}

    /** Does objective vector {@code a} dominate {@code b}? (At least as good everywhere, better somewhere.) */
    public static boolean dominates(double[] a, double[] b) {
        boolean strictlyBetter = false;
        for (int i = 0; i < a.length; i++) {
            if (a[i] < b[i]) {
                return false;
            }
            if (a[i] > b[i]) {
                strictlyBetter = true;
            }
        }
        return strictlyBetter;
    }

    /** Fast non-dominated sort: Pareto fronts, best first, each a list of indices into {@code points}. */
    public static List<List<Integer>> fastNonDominatedSort(List<double[]> points) {
        int n = points.size();
        List<List<Integer>> dominatedBy = new ArrayList<>(); // who each point dominates
        int[] dominationCount = new int[n]; // how many dominate each point
        List<List<Integer>> fronts = new ArrayList<>();
        fronts.add(new ArrayList<>());
        for (int p = 0; p < n; p++) {
            dominatedBy.add(new ArrayList<>());
        }
        for (int p = 0; p < n; p++) {
            for (int q = 0; q < n; q++) {
                if (p == q) {
                    continue;
                }
                if (dominates(points.get(p), points.get(q))) {
                    dominatedBy.get(p).add(q);
                } else if (dominates(points.get(q), points.get(p))) {
                    dominationCount[p]++;
                }
            }
            if (dominationCount[p] == 0) {
                fronts.get(0).add(p);
            }
        }
        int i = 0;
        while (!fronts.get(i).isEmpty()) {
            List<Integer> next = new ArrayList<>();
            for (int p : fronts.get(i)) {
                for (int q : dominatedBy.get(p)) {
                    if (--dominationCount[q] == 0) {
                        next.add(q);
                    }
                }
            }
            i++;
            fronts.add(next);
        }
        fronts.remove(fronts.size() - 1); // the last one is empty
        return fronts;
    }

    /** A JavaScript-style comparator result from a difference: NaN counts as equal. */
    private static int signOf(double d) {
        return d > 0 ? 1 : d < 0 ? -1 : 0;
    }

    /**
     * Crowding distance for the points in one front, aligned to {@code frontIndices}. Boundary points get infinity so
     * the extremes are preserved.
     */
    public static double[] crowdingDistances(List<double[]> points, List<Integer> frontIndices) {
        int m = frontIndices.size();
        double[] distance = new double[m];
        if (m == 0) {
            return distance;
        }
        int numObjectives = points.get(frontIndices.get(0)).length;
        for (int obj = 0; obj < numObjectives; obj++) {
            final int o = obj;
            List<Integer> order = new ArrayList<>();
            for (int k = 0; k < m; k++) {
                order.add(k);
            }
            order.sort((a, b) -> signOf(points.get(frontIndices.get(a))[o] - points.get(frontIndices.get(b))[o]));
            distance[order.get(0)] = Double.POSITIVE_INFINITY;
            distance[order.get(m - 1)] = Double.POSITIVE_INFINITY;
            double min = points.get(frontIndices.get(order.get(0)))[obj];
            double max = points.get(frontIndices.get(order.get(m - 1)))[obj];
            double span = max - min;
            if (span == 0) {
                continue;
            }
            for (int k = 1; k < m - 1; k++) {
                double prev = points.get(frontIndices.get(order.get(k - 1)))[obj];
                double nextV = points.get(frontIndices.get(order.get(k + 1)))[obj];
                distance[order.get(k)] += (nextV - prev) / span;
            }
        }
        return distance;
    }

    /** One evaluated genome. Rank and crowding are assigned by the sort and change as the population evolves. */
    public static final class Individual {
        private final Genome genome;
        private final EvalResult result;
        private final double[] objectives;
        private int rank;
        private double crowding;

        Individual(Genome genome, EvalResult result, double[] objectives) {
            this.genome = genome;
            this.result = result;
            this.objectives = objectives;
        }

        public Genome genome() {
            return genome;
        }

        public EvalResult result() {
            return result;
        }

        public double[] objectives() {
            return objectives.clone();
        }

        double objective(int i) {
            return objectives[i];
        }

        /** The Pareto front index (0 is the best). */
        public int rank() {
            return rank;
        }

        public double crowding() {
            return crowding;
        }
    }

    /** Observes the run's progress at its natural boundaries. */
    public interface ProgressListener {
        ProgressListener NONE = new ProgressListener() {};

        /** The initial population has been evaluated. */
        default void onInitialPopulation() {}

        /** Generation {@code completed} of {@code total} has finished (1-based). */
        default void onGeneration(int completed, int total) {}
    }

    /** Thrown at a generation boundary when the run is cancelled. */
    public static final class CancelledException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public CancelledException() {
            super("optimization cancelled");
        }
    }

    /**
     * @param populationSize individuals per generation
     * @param generations number of generations after the initial population
     * @param mutationRate probability a child is mutated
     * @param evalRunsPerScenario runs per scenario for each evaluation
     * @param classes restrict the genome pool to these classes; null or empty means all
     * @param progress progress callbacks
     * @param cancelled polled at generation boundaries; true stops the run with {@link CancelledException}
     */
    public record Options(
            int populationSize,
            int generations,
            double mutationRate,
            int evalRunsPerScenario,
            List<BuildClass> classes,
            ProgressListener progress,
            BooleanSupplier cancelled) {

        public static Options defaults() {
            return new Options(24, 12, 0.3, SoloEvaluator.DEFAULT_RUNS_PER_SCENARIO, null, ProgressListener.NONE, () -> false);
        }

        public Options withPopulation(int v) {
            return new Options(v, generations, mutationRate, evalRunsPerScenario, classes, progress, cancelled);
        }

        public Options withGenerations(int v) {
            return new Options(populationSize, v, mutationRate, evalRunsPerScenario, classes, progress, cancelled);
        }

        public Options withMutationRate(double v) {
            return new Options(populationSize, generations, v, evalRunsPerScenario, classes, progress, cancelled);
        }

        public Options withEvalRuns(int v) {
            return new Options(populationSize, generations, mutationRate, v, classes, progress, cancelled);
        }

        public Options withClasses(List<BuildClass> v) {
            return new Options(populationSize, generations, mutationRate, evalRunsPerScenario, v, progress, cancelled);
        }

        public Options withProgress(ProgressListener v) {
            return new Options(populationSize, generations, mutationRate, evalRunsPerScenario, classes, v, cancelled);
        }

        public Options withCancelled(BooleanSupplier v) {
            return new Options(populationSize, generations, mutationRate, evalRunsPerScenario, classes, progress, v);
        }
    }

    /**
     * @param front the final Pareto front (rank 0), sorted by crowding distance descending
     */
    public record Result(List<Individual> front, List<Individual> population, int generations) {}

    /** Assign rank (front index) and crowding distance to every individual in place. */
    private static List<List<Integer>> assignRanksAndCrowding(List<Individual> pop) {
        List<double[]> points = new ArrayList<>();
        for (Individual ind : pop) {
            points.add(ind.objectives);
        }
        List<List<Integer>> fronts = fastNonDominatedSort(points);
        for (int rank = 0; rank < fronts.size(); rank++) {
            List<Integer> front = fronts.get(rank);
            double[] dists = crowdingDistances(points, front);
            for (int k = 0; k < front.size(); k++) {
                Individual ind = pop.get(front.get(k));
                ind.rank = rank;
                ind.crowding = dists[k];
            }
        }
        return fronts;
    }

    /** The crowded-comparison operator: lower rank wins; a tie goes to the higher crowding distance. */
    private static boolean crowdedBetter(Individual a, Individual b) {
        if (a.rank != b.rank) {
            return a.rank < b.rank;
        }
        return a.crowding > b.crowding;
    }

    private static Individual tournament(List<Individual> pop, Rng rng) {
        Individual a = pop.get((int) Math.floor(rng.next() * pop.size()));
        Individual b = pop.get((int) Math.floor(rng.next() * pop.size()));
        return crowdedBetter(a, b) ? a : b;
    }

    /** Run NSGA-II over the genome and return the Pareto front. */
    public static Result run(MartialCatalog catalog, LabeledRandom random, Options opts) {
        int populationSize = opts.populationSize();
        Map<String, EvalResult> cache = new HashMap<>();
        java.util.function.Function<Genome, Individual> assess = genome -> {
            String key = Genomes.key(genome);
            EvalResult result = cache.get(key);
            if (result == null) {
                result = SoloEvaluator.evaluate(id -> Genomes.build(genome, catalog, id), catalog.scenarios(), opts.evalRunsPerScenario());
                cache.put(key, result);
            }
            return new Individual(genome, result, Objectives.of(result));
        };

        List<Individual> population = new ArrayList<>();
        for (int i = 0; i < populationSize; i++) {
            population.add(assess.apply(Genomes.randomGenome(catalog, random, "init:" + i, opts.classes())));
        }
        assignRanksAndCrowding(population);
        opts.progress().onInitialPopulation();

        for (int gen = 0; gen < opts.generations(); gen++) {
            if (opts.cancelled().getAsBoolean()) {
                throw new CancelledException();
            }
            Rng genRng = random.stream("gen:" + gen);
            // Offspring via crowded tournament selection + crossover + mutation.
            List<Individual> offspring = new ArrayList<>();
            for (int c = 0; c < populationSize; c++) {
                Individual a = tournament(population, genRng);
                Individual b = tournament(population, genRng);
                Genome child = Genomes.crossover(a.genome, b.genome, catalog, random, "gen:" + gen + ":x:" + c);
                if (genRng.next() < opts.mutationRate()) {
                    child = Genomes.mutate(child, catalog, random, "gen:" + gen + ":m:" + c, opts.classes());
                }
                offspring.add(assess.apply(child));
            }

            // Combine parents and offspring, re-rank, and fill the next generation by front, breaking the
            // overflowing front by crowding distance.
            List<Individual> combined = new ArrayList<>(population);
            combined.addAll(offspring);
            List<List<Integer>> fronts = assignRanksAndCrowding(combined);
            List<Individual> next = new ArrayList<>();
            for (List<Integer> front : fronts) {
                if (next.size() + front.size() <= populationSize) {
                    for (int idx : front) {
                        next.add(combined.get(idx));
                    }
                } else {
                    int remaining = populationSize - next.size();
                    List<Integer> sorted = new ArrayList<>(front);
                    sorted.sort((x, y) -> signOf(combined.get(y).crowding - combined.get(x).crowding));
                    for (int k = 0; k < remaining; k++) {
                        next.add(combined.get(sorted.get(k)));
                    }
                    break;
                }
            }
            population = next;
            assignRanksAndCrowding(population);
            opts.progress().onGeneration(gen + 1, opts.generations());
        }

        List<Individual> front = new ArrayList<>();
        for (Individual ind : population) {
            if (ind.rank == 0) {
                front.add(ind);
            }
        }
        front.sort((a, b) -> signOf(b.crowding - a.crowding));
        return new Result(Collections.unmodifiableList(front), Collections.unmodifiableList(population), opts.generations());
    }
}
