package org.omnomnom.dnd.sim.domain.opt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Port of {@code stats.spec.ts}. */
class StatsTest {

    @Test
    void computesTheMean() {
        assertThat(Stats.mean(List.of(1.0, 2.0, 3.0, 4.0))).isEqualTo(2.5);
        assertThat(Stats.mean(List.of())).isZero();
    }

    @Test
    void computesTheBesselCorrectedStandardDeviation() {
        // values 2,4,4,4,5,5,7,9 -> sd = 2.138 (n-1)
        assertThat(Stats.sampleStdDev(List.of(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0))).isCloseTo(2.13809, within(1e-3));
        assertThat(Stats.sampleStdDev(List.of(5.0))).isZero();
    }

    @Test
    void wilsonBracketsThePointEstimateWithinZeroAndOne() {
        Interval ci = Stats.wilsonInterval(5, 10);
        assertThat(ci.point()).isEqualTo(0.5);
        assertThat(ci.lo()).isGreaterThanOrEqualTo(0).isLessThan(0.5);
        assertThat(ci.hi()).isLessThanOrEqualTo(1).isGreaterThan(0.5);
    }

    @Test
    void wilsonMatchesAKnownValue() {
        Interval ci = Stats.wilsonInterval(5, 10, Stats.Z_95);
        assertThat(ci.lo()).isCloseTo(0.2366, within(1e-2));
        assertThat(ci.hi()).isCloseTo(0.7634, within(1e-2));
    }

    @Test
    void wilsonStaysInBoundsAtTheExtremes() {
        Interval all = Stats.wilsonInterval(10, 10);
        assertThat(all.hi()).isCloseTo(1, within(1e-10)).isLessThanOrEqualTo(1);
        assertThat(all.lo()).isGreaterThan(0.6);
        Interval none = Stats.wilsonInterval(0, 10);
        assertThat(none.lo()).isZero();
        assertThat(none.hi()).isLessThan(0.4);
    }

    @Test
    void wilsonNarrowsAsNGrows() {
        assertThat(Stats.wilsonInterval(50, 100).halfWidth()).isLessThan(Stats.wilsonInterval(5, 10).halfWidth());
    }

    @Test
    void wilsonHandlesNZero() {
        Interval ci = Stats.wilsonInterval(0, 0);
        assertThat(ci.lo()).isZero();
        assertThat(ci.hi()).isEqualTo(1);
        assertThat(ci.halfWidth()).isEqualTo(0.5);
    }

    @Test
    void meanIntervalIsSymmetricAroundTheMean() {
        Interval ci = Stats.meanInterval(List.of(10.0, 12.0, 14.0, 16.0, 18.0));
        assertThat(ci.point()).isEqualTo(14);
        assertThat(ci.point() - ci.lo()).isCloseTo(ci.hi() - ci.point(), within(1e-10));
    }

    @Test
    void meanIntervalNarrowsAsTheSampleGrowsForTheSameSpread() {
        Interval small = Stats.meanInterval(List.of(8.0, 10.0, 12.0));
        Interval large = Stats.meanInterval(List.of(8.0, 10.0, 12.0, 8.0, 10.0, 12.0, 8.0, 10.0, 12.0, 8.0, 10.0, 12.0));
        assertThat(large.halfWidth()).isLessThan(small.halfWidth());
    }

    @Test
    void meanIntervalOfASingleSampleHasZeroWidth() {
        assertThat(Stats.meanInterval(List.of(5.0)).halfWidth()).isZero();
    }
}
