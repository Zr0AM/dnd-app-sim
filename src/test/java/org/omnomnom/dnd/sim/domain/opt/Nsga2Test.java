package org.omnomnom.dnd.sim.domain.opt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.adapter.out.content.SqliteContentSource;
import org.omnomnom.dnd.sim.domain.content.MonsterCatalog;
import org.omnomnom.dnd.sim.domain.rng.LabeledRandom;

/** Port of {@code nsga2.spec.ts}, plus the progress and cancellation hooks this port adds. */
class Nsga2Test {

    static MartialCatalog catalog;

    @BeforeAll
    static void open() {
        try (SqliteContentSource source = SqliteContentSource.open()) {
            catalog = MartialCatalog.load(source, MonsterCatalog.load(source), 3);
        }
    }

    private static double[] p(double... v) {
        return v;
    }

    @Test
    void aStrictlyBetterPointDominates() {
        assertThat(Nsga2.dominates(p(2, 2), p(1, 1))).isTrue();
        assertThat(Nsga2.dominates(p(2, 1), p(1, 1))).isTrue(); // equal in one, better in another
    }

    @Test
    void tradeOffsDoNotDominate() {
        assertThat(Nsga2.dominates(p(2, 1), p(1, 2))).isFalse();
        assertThat(Nsga2.dominates(p(1, 2), p(2, 1))).isFalse();
    }

    @Test
    void equalPointsDoNotDominateEachOther() {
        assertThat(Nsga2.dominates(p(1, 1), p(1, 1))).isFalse();
    }

    @Test
    void separatesADominatedPointIntoALaterFront() {
        List<List<Integer>> fronts = Nsga2.fastNonDominatedSort(List.of(p(2, 1), p(1, 2), p(1, 1)));
        assertThat(fronts).hasSize(2);
        assertThat(fronts.get(0)).containsExactlyInAnyOrder(0, 1);
        assertThat(fronts.get(1)).containsExactly(2);
    }

    @Test
    void aSingleFrontWhenAllPointsAreMutuallyNonDominated() {
        List<List<Integer>> fronts = Nsga2.fastNonDominatedSort(List.of(p(3, 1), p(2, 2), p(1, 3)));
        assertThat(fronts).hasSize(1);
        assertThat(fronts.get(0)).hasSize(3);
    }

    @Test
    void aChainOfDominationYieldsOnePointPerFront() {
        List<List<Integer>> fronts = Nsga2.fastNonDominatedSort(List.of(p(3, 3), p(2, 2), p(1, 1)));
        assertThat(fronts.stream().map(List::size)).containsExactly(1, 1, 1);
        assertThat(fronts.get(0)).containsExactly(0);
    }

    @Test
    void anEmptyPopulationHasNoFronts() {
        assertThat(Nsga2.fastNonDominatedSort(List.of())).isEmpty();
    }

    @Test
    void boundaryPointsGetInfiniteCrowdingDistance() {
        List<double[]> points = List.of(p(1, 3), p(2, 2), p(3, 1));
        double[] d = Nsga2.crowdingDistances(points, List.of(0, 1, 2));
        assertThat(Arrays.stream(d).filter(Double::isInfinite).count()).isEqualTo(2);
        assertThat(d[1]).isPositive().isFinite();
        // Each axis contributes (3-1)/(3-1) = 1 to the middle point.
        assertThat(d[1]).isEqualTo(2.0);
    }

    @Test
    void aSinglePointIsInfinitelyCrowdingDistant() {
        assertThat(Nsga2.crowdingDistances(List.of(p(1, 1)), List.of(0))).containsExactly(Double.POSITIVE_INFINITY);
    }

    @Test
    void anObjectiveWithNoSpreadAddsNothing() {
        List<double[]> points = List.of(p(1, 5), p(2, 5), p(3, 5));
        double[] d = Nsga2.crowdingDistances(points, List.of(0, 1, 2));
        assertThat(d[1]).isEqualTo(1.0); // only the first axis has spread: (3-1)/(3-1)
    }

    private static Nsga2.Options small(int pop, int gens, int runs) {
        return Nsga2.Options.defaults().withPopulation(pop).withGenerations(gens).withEvalRuns(runs);
    }

    @Test
    void returnsANonEmptyParetoFrontOfLegalBuilds() {
        Nsga2.Result result = Nsga2.run(catalog, new LabeledRandom(42), small(16, 6, 8));
        assertThat(result.front()).isNotEmpty();
        assertThat(result.population()).hasSize(16);
        for (Nsga2.Individual ind : result.front()) {
            assertThat(ind.rank()).isZero();
            assertThat(ind.objectives()).hasSize(6);
        }
    }

    @Test
    void theFrontIsActuallyNonDominated() {
        Nsga2.Result result = Nsga2.run(catalog, new LabeledRandom(5), small(16, 6, 8));
        for (Nsga2.Individual a : result.front()) {
            for (Nsga2.Individual b : result.front()) {
                if (a != b) {
                    assertThat(Nsga2.dominates(a.objectives(), b.objectives())).isFalse();
                }
            }
        }
    }

    @Test
    void isDeterministicUnderASeed() {
        Nsga2.Options opts = small(12, 5, 6);
        Nsga2.Result a = Nsga2.run(catalog, new LabeledRandom(7), opts);
        Nsga2.Result b = Nsga2.run(catalog, new LabeledRandom(7), opts);
        assertThat(a.front().stream().map(i -> Arrays.toString(i.objectives())).toList())
                .isEqualTo(b.front().stream().map(i -> Arrays.toString(i.objectives())).toList());
    }

    @Test
    void efficiencyIsNegativeRoundsAndObjectivesMatchTheResult() {
        Nsga2.Result result = Nsga2.run(catalog, new LabeledRandom(11), small(12, 4, 6));
        Nsga2.Individual best = result.front().get(0);
        assertThat(best.objectives()).containsExactly(Objectives.of(best.result()));
        assertThat(best.objectives()[3]).isLessThanOrEqualTo(0);
    }

    @Test
    void aClassRestrictionLimitsTheBuilds() {
        Nsga2.Result result = Nsga2.run(catalog, new LabeledRandom(3),
                small(8, 2, 2).withClasses(List.of(BuildClass.WIZARD, BuildClass.CLERIC)));
        assertThat(result.population()).allMatch(i -> i.genome().classSlug() == BuildClass.WIZARD || i.genome().classSlug() == BuildClass.CLERIC);
    }

    @Test
    void reportsProgressAtEachBoundary() {
        List<String> seen = new ArrayList<>();
        Nsga2.run(catalog, new LabeledRandom(1), small(6, 3, 1).withProgress(new Nsga2.ProgressListener() {
            @Override
            public void onInitialPopulation() {
                seen.add("init");
            }

            @Override
            public void onGeneration(int completed, int total) {
                seen.add(completed + "/" + total);
            }
        }));
        assertThat(seen).containsExactly("init", "1/3", "2/3", "3/3");
    }

    @Test
    void cancellationStopsAtAGenerationBoundary() {
        int[] generations = {0};
        Nsga2.Options opts = small(6, 10, 1)
                .withProgress(new Nsga2.ProgressListener() {
                    @Override
                    public void onGeneration(int completed, int total) {
                        generations[0] = completed;
                    }
                })
                .withCancelled(() -> generations[0] >= 2);
        assertThatThrownBy(() -> Nsga2.run(catalog, new LabeledRandom(1), opts)).isInstanceOf(Nsga2.CancelledException.class);
        assertThat(generations[0]).isEqualTo(2);
    }
}
