package org.omnomnom.dnd.sim.domain.opt.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import org.omnomnom.dnd.sim.domain.core.FloatOrder;
import org.omnomnom.dnd.sim.domain.opt.evaluation.EvalResult;
import org.omnomnom.dnd.sim.domain.opt.evaluation.Objectives;
import org.omnomnom.dnd.sim.domain.opt.evaluation.SoloEvaluator;
import org.omnomnom.dnd.sim.domain.opt.genome.BuildClass;
import org.omnomnom.dnd.sim.domain.opt.genome.Genome;
import org.omnomnom.dnd.sim.domain.opt.genome.Genomes;
import org.omnomnom.dnd.sim.domain.opt.genome.MartialCatalog;
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
        for (int p = 0; p < n; p++) {
            dominatedBy.add(new ArrayList<>());
        }
        for (int p = 0; p < n; p++) {
            dominationCount[p] = scanDomination(points, p, dominatedBy.get(p));
        }
        List<List<Integer>> fronts = new ArrayList<>();
        List<Integer> front = IntStream.range(0, n).filter(p -> dominationCount[p] == 0).boxed().toList();
        while (!front.isEmpty()) {
            fronts.add(front);
            front = nextFront(front, dominatedBy, dominationCount);
        }
        return fronts;
    }

    /** Record the points {@code p} dominates in {@code dominated}; returns how many points dominate {@code p}. */
    private static int scanDomination(List<double[]> points, int p, List<Integer> dominated) {
        int dominators = 0;
        for (int q = 0; q < points.size(); q++) {
            if (q != p && dominates(points.get(p), points.get(q))) {
                dominated.add(q);
            } else if (q != p && dominates(points.get(q), points.get(p))) {
                dominators++;
            }
        }
        return dominators;
    }

    /** Remove {@code front} from the counts; the points no one else dominates any more form the next front. */
    private static List<Integer> nextFront(List<Integer> front, List<List<Integer>> dominatedBy, int[] dominationCount) {
        List<Integer> next = new ArrayList<>();
        for (int p : front) {
            for (int q : dominatedBy.get(p)) {
                if (--dominationCount[q] == 0) {
                    next.add(q);
                }
            }
        }
        return next;
    }

    /**
     * Crowding distance for the points in one front, aligned to {@code frontIndices}. Boundary points get infinity so
     * the extremes are preserved.
     */
    public static double[] crowdingDistances(List<double[]> points, List<Integer> frontIndices) {
        double[] distance = new double[frontIndices.size()];
        if (distance.length == 0) {
            return distance;
        }
        int numObjectives = points.get(frontIndices.get(0)).length;
        for (int obj = 0; obj < numObjectives; obj++) {
            int objective = obj;
            accumulateCrowding(distance, frontIndices.stream().mapToDouble(i -> points.get(i)[objective]).toArray());
        }
        return distance;
    }

    /**
     * Add one objective's contribution: the two extremes get infinity, and each point between them gains the gap between
     * its neighbours in objective order, relative to the objective's span.
     *
     * @param values the objective's value for each front member, aligned with {@code distance}
     */
    private static void accumulateCrowding(double[] distance, double[] values) {
        int m = values.length;
        List<Integer> order = IntStream.range(0, m).boxed().sorted((a, b) -> FloatOrder.compare(values[a], values[b])).toList();
        distance[order.get(0)] = Double.POSITIVE_INFINITY;
        distance[order.get(m - 1)] = Double.POSITIVE_INFINITY;
        double span = values[order.get(m - 1)] - values[order.get(0)];
        if (span == 0) {
            return;
        }
        for (int k = 1; k < m - 1; k++) {
            distance[order.get(k)] += (values[order.get(k + 1)] - values[order.get(k - 1)]) / span;
        }
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

        public double objective(int i) {
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
        List<double[]> points = pop.stream().map(ind -> ind.objectives).toList();
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

    /** Evaluates genomes against the scenario library, caching by genome key (evaluation is a pure function of the genome). */
    private static final class Assessor {
        private final MartialCatalog catalog;
        private final int runsPerScenario;
        private final Map<String, EvalResult> cache = new HashMap<>();

        Assessor(MartialCatalog catalog, int runsPerScenario) {
            this.catalog = catalog;
            this.runsPerScenario = runsPerScenario;
        }

        Individual assess(Genome genome) {
            EvalResult result = cache.computeIfAbsent(Genomes.key(genome),
                    key -> SoloEvaluator.evaluate(id -> Genomes.build(genome, catalog, id), catalog.scenarios(), runsPerScenario));
            return new Individual(genome, result, Objectives.of(result));
        }
    }

    /** Run NSGA-II over the genome and return the Pareto front. */
    public static Result run(MartialCatalog catalog, LabeledRandom random, Options opts) {
        Assessor assessor = new Assessor(catalog, opts.evalRunsPerScenario());

        List<Individual> population = new ArrayList<>();
        for (int i = 0; i < opts.populationSize(); i++) {
            population.add(assessor.assess(Genomes.randomGenome(catalog, random, "init:" + i, opts.classes())));
        }
        assignRanksAndCrowding(population);
        opts.progress().onInitialPopulation();

        for (int gen = 0; gen < opts.generations(); gen++) {
            if (opts.cancelled().getAsBoolean()) {
                throw new CancelledException();
            }
            List<Individual> offspring = breed(population, catalog, random, opts, assessor, gen);
            population = nextGeneration(population, offspring, opts.populationSize());
            opts.progress().onGeneration(gen + 1, opts.generations());
        }

        List<Individual> front = population.stream()
                .filter(ind -> ind.rank == 0)
                .sorted(FloatOrder.descendingBy(ind -> ind.crowding))
                .toList();
        return new Result(front, Collections.unmodifiableList(population), opts.generations());
    }

    /** One generation of offspring via crowded tournament selection + crossover + mutation. */
    private static List<Individual> breed(
            List<Individual> population, MartialCatalog catalog, LabeledRandom random, Options opts, Assessor assessor, int gen) {
        Rng genRng = random.stream("gen:" + gen);
        List<Individual> offspring = new ArrayList<>();
        for (int c = 0; c < opts.populationSize(); c++) {
            Individual a = tournament(population, genRng);
            Individual b = tournament(population, genRng);
            Genome child = Genomes.crossover(a.genome, b.genome, catalog, random, "gen:" + gen + ":x:" + c);
            if (genRng.next() < opts.mutationRate()) {
                child = Genomes.mutate(child, catalog, random, "gen:" + gen + ":m:" + c, opts.classes());
            }
            offspring.add(assessor.assess(child));
        }
        return offspring;
    }

    /**
     * Combine parents and offspring, re-rank, and fill the next generation by front, breaking the overflowing front by
     * crowding distance.
     */
    private static List<Individual> nextGeneration(List<Individual> population, List<Individual> offspring, int size) {
        List<Individual> combined = new ArrayList<>(population);
        combined.addAll(offspring);
        List<Individual> next = new ArrayList<>();
        for (List<Integer> front : assignRanksAndCrowding(combined)) {
            if (next.size() + front.size() > size) {
                next.addAll(leastCrowded(front, combined, size - next.size()));
                break;
            }
            front.forEach(idx -> next.add(combined.get(idx)));
        }
        assignRanksAndCrowding(next);
        return next;
    }

    /** The {@code count} individuals of a front with the greatest crowding distance (the most spread out). */
    private static List<Individual> leastCrowded(List<Integer> front, List<Individual> combined, int count) {
        return front.stream()
                .sorted(FloatOrder.descendingBy(idx -> combined.get(idx).crowding))
                .limit(count)
                .map(combined::get)
                .toList();
    }
}
