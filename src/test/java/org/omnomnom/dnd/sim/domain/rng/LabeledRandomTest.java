package org.omnomnom.dnd.sim.domain.rng;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.DoubleStream;
import org.junit.jupiter.api.Test;

/** Port of {@code sim/src/rng/rng.spec.ts}. */
class LabeledRandomTest {

    static double[] take(Rng rng, int n) {
        return DoubleStream.generate(rng::next).limit(n).toArray();
    }

    static double[] take(Rng rng) {
        return take(rng, 50);
    }

    @Test
    void replaysTheSameSequenceForTheSameSeedAndLabel() {
        assertThat(take(new LabeledRandom(42).stream("enemy:goblin:attack")))
                .containsExactly(take(new LabeledRandom(42).stream("enemy:goblin:attack")));
    }

    @Test
    void givesIndependentSequencesToDifferentLabels() {
        LabeledRandom r = new LabeledRandom(42);
        assertThat(take(r.stream("a"))).isNotEqualTo(take(r.stream("b")));
    }

    @Test
    void divergesForDifferentRootSeedsOnTheSameLabel() {
        assertThat(take(new LabeledRandom(1).stream("x"))).isNotEqualTo(take(new LabeledRandom(2).stream("x")));
    }

    @Test
    void continuesALabelSequenceAcrossCallsRatherThanRestarting() {
        LabeledRandom r = new LabeledRandom(7);
        double[] first = take(r.stream("s"), 10);
        double[] second = take(r.stream("s"), 10);
        assertThat(second).isNotEqualTo(first);
        double[] fresh = take(new LabeledRandom(7).stream("s"), 20);
        double[] joined = new double[20];
        System.arraycopy(first, 0, joined, 0, 10);
        System.arraycopy(second, 0, joined, 10, 10);
        assertThat(joined).containsExactly(fresh);
    }

    @Test
    void producesValuesInHalfOpenUnitInterval() {
        for (double v : take(new LabeledRandom(123).stream("range"), 500)) {
            assertThat(v).isGreaterThanOrEqualTo(0.0).isLessThan(1.0);
        }
    }

    /** The core CRN guarantee: draining one stream does not shift another. */
    @Test
    void isOrderIndependent() {
        double[] baseline = take(new LabeledRandom(99).stream("enemy:attack"), 20);
        LabeledRandom other = new LabeledRandom(99);
        take(other.stream("hero:extra-spell"), 1000);
        assertThat(take(other.stream("enemy:attack"), 20)).containsExactly(baseline);
    }

    @Test
    void interleavingTwoStreamsDoesNotChangeEither() {
        LabeledRandom sequential = new LabeledRandom(5);
        double[] aSeq = take(sequential.stream("a"), 20);
        double[] bSeq = take(sequential.stream("b"), 20);

        LabeledRandom interleaved = new LabeledRandom(5);
        double[] aInt = new double[20];
        double[] bInt = new double[20];
        for (int i = 0; i < 20; i++) {
            aInt[i] = interleaved.stream("a").next();
            bInt[i] = interleaved.stream("b").next();
        }
        assertThat(aInt).containsExactly(aSeq);
        assertThat(bInt).containsExactly(bSeq);
    }

    @Test
    void childNamespacesLabelsUnderThePrefix() {
        double[] viaChild = take(new LabeledRandom(11).child("enemy:goblin-1").stream("attack"));
        double[] viaPath = take(new LabeledRandom(11).stream("enemy:goblin-1/attack"));
        assertThat(viaChild).containsExactly(viaPath);
    }

    @Test
    void siblingChildrenAreIndependent() {
        LabeledRandom root = new LabeledRandom(11);
        assertThat(take(root.child("goblin-1").stream("attack")))
                .isNotEqualTo(take(root.child("goblin-2").stream("attack")));
    }
}
