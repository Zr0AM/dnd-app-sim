package org.omnomnom.dnd.sim.domain.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AbilityScoresTest {

    @Test
    void getAndModifierReadEachScore() {
        AbilityScores s = AbilityScores.of(16, 14, 12, 10, 8, 20);
        assertThat(s.get(Ability.STR)).isEqualTo(16);
        assertThat(s.get(Ability.INT)).isEqualTo(10);
        assertThat(s.get(Ability.CHA)).isEqualTo(20);
        assertThat(s.modifier(Ability.STR)).isEqualTo(3);
        assertThat(s.modifier(Ability.WIS)).isEqualTo(-1);
        assertThat(s.modifier(Ability.CHA)).isEqualTo(5);
    }

    @Test
    void allTensIsPlusZeroEverywhere() {
        for (Ability a : Ability.values()) {
            assertThat(AbilityScores.allTens().modifier(a)).isZero();
        }
    }
}
