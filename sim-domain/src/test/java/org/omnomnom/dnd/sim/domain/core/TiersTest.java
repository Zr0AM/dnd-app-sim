package org.omnomnom.dnd.sim.domain.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TiersTest {

    private static int dieAt(int level) {
        return Tiers.pick(level, 4, Tiers.from(5, 6), Tiers.from(11, 8), Tiers.from(17, 10));
    }

    @Test
    void usesTheBaseBelowEveryTier() {
        assertThat(dieAt(1)).isEqualTo(4);
        assertThat(dieAt(4)).isEqualTo(4);
    }

    @Test
    void takesTheHighestTierReached() {
        assertThat(dieAt(5)).isEqualTo(6);
        assertThat(dieAt(10)).isEqualTo(6);
        assertThat(dieAt(11)).isEqualTo(8);
        assertThat(dieAt(16)).isEqualTo(8);
        assertThat(dieAt(17)).isEqualTo(10);
        assertThat(dieAt(20)).isEqualTo(10);
    }

    @Test
    void tierOrderDoesNotMatter() {
        assertThat(Tiers.pick(12, "low", Tiers.from(17, "top"), Tiers.from(5, "mid"), Tiers.from(11, "high"))).isEqualTo("high");
    }
}
