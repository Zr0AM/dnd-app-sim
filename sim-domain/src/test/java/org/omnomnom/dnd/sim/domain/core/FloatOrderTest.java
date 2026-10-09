package org.omnomnom.dnd.sim.domain.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FloatOrderTest {

    @Test
    void comparesLikeSubtractionDoes() {
        assertThat(FloatOrder.compare(1, 2)).isNegative();
        assertThat(FloatOrder.compare(2, 1)).isPositive();
        assertThat(FloatOrder.compare(1.5, 1.5)).isZero();
        assertThat(FloatOrder.compare(Double.NEGATIVE_INFINITY, 0)).isNegative();
        assertThat(FloatOrder.compare(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)).isZero();
    }

    @Test
    void negativeZeroTiesWithZero() {
        assertThat(FloatOrder.compare(-0.0, 0.0)).isZero();
        assertThat(FloatOrder.compare(0.0, -0.0)).isZero();
    }

    @Test
    void descendingByPutsTheHighestScoreFirstAndKeepsTiesInInputOrder() {
        List<double[]> items = new ArrayList<>(List.of(
                new double[] {1, 0}, new double[] {3, 1}, new double[] {2, 2}, new double[] {3, 3}, new double[] {-0.0, 4},
                new double[] {0.0, 5}));
        items.sort(FloatOrder.descendingBy(a -> a[0]));
        assertThat(items).extracting(a -> (int) a[1]).containsExactly(1, 3, 2, 0, 4, 5);
    }

    @Test
    void infiniteScoresSortAtTheEnds() {
        List<Double> scores = new ArrayList<>(List.of(1.0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0));
        scores.sort(FloatOrder.descendingBy(Double::doubleValue));
        assertThat(scores).containsExactly(Double.POSITIVE_INFINITY, 1.0, 0.0, Double.NEGATIVE_INFINITY);
    }
}
