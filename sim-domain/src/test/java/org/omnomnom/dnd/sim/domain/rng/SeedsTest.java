package org.omnomnom.dnd.sim.domain.rng;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Port of the {@code deriveSeed}, {@code seedFrom} and {@code randomSeed} blocks of {@code rng.spec.ts}. */
class SeedsTest {

    @Test
    void deriveSeedIsDeterministic() {
        assertThat(Seeds.deriveSeed(123, "label")).isEqualTo(Seeds.deriveSeed(123, "label"));
    }

    @Test
    void deriveSeedChangesWithTheLabel() {
        assertThat(Seeds.deriveSeed(123, "a")).isNotEqualTo(Seeds.deriveSeed(123, "b"));
    }

    @Test
    void deriveSeedChangesWithTheRootSeed() {
        assertThat(Seeds.deriveSeed(1, "a")).isNotEqualTo(Seeds.deriveSeed(2, "a"));
    }

    @Test
    void deriveSeedReturnsAnUnsigned32BitInteger() {
        long s = Seeds.deriveSeed(0xffffffffL, "anything");
        assertThat(s).isBetween(0L, 0xffffffffL);
    }

    @Test
    void seedFromBuildsAStableSeedFromMixedParts() {
        assertThat(Seeds.seedFrom("scenario-a", "family", 3)).isEqualTo(Seeds.seedFrom("scenario-a", "family", 3));
        assertThat(Seeds.seedFrom("scenario-a", "family", 3)).isNotEqualTo(Seeds.seedFrom("scenario-a", "family", 4));
    }

    @Test
    void seedFromRejectsNonIntegralParts() {
        assertThatThrownBy(() -> Seeds.seedFrom("x", 1.5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void randomSeedReturnsAnUnsigned32BitInteger() {
        assertThat(Seeds.randomSeed()).isBetween(0L, 0xffffffffL);
    }
}
