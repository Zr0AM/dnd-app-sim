package org.omnomnom.dnd.sim.domain.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Port of {@code sim/src/core/types.spec.ts}. */
class CoreTest {

    @Test
    void vocabularyHasTheExpectedCounts() {
        assertThat(Ability.values()).hasSize(6);
        assertThat(DamageType.values()).hasSize(13);
        assertThat(Condition.values()).hasSize(15);
        assertThat(Size.values()).hasSize(6);
    }

    @Test
    void everySizeMapsToASpace() {
        for (Size s : Size.values()) {
            assertThat(s.spaceFt()).isPositive();
        }
        assertThat(Size.MEDIUM.spaceFt()).isEqualTo(5.0);
        assertThat(Size.LARGE.spaceFt()).isEqualTo(10.0);
        assertThat(Size.TINY.spaceFt()).isEqualTo(2.5);
    }

    @Test
    void codesRoundTripAndAreLowercase() {
        for (Ability a : Ability.values()) {
            assertThat(Ability.fromCode(a.code())).isEqualTo(a);
            assertThat(a.code()).isEqualTo(a.name().toLowerCase(java.util.Locale.ROOT));
        }
        for (DamageType d : DamageType.values()) {
            assertThat(DamageType.fromCode(d.code())).isEqualTo(d);
        }
        for (Condition c : Condition.values()) {
            assertThat(Condition.fromCode(c.code())).isEqualTo(c);
        }
        for (Size s : Size.values()) {
            assertThat(Size.fromCode(s.code())).isEqualTo(s);
        }
    }

    @Test
    void unknownCodesAreRejected() {
        assertThatThrownBy(() -> Ability.fromCode("luck")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DamageType.fromCode("sonic")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void abilityModifierMatchesThe2024Table() {
        Map<Integer, Integer> expected = Map.of(1, -5, 8, -1, 10, 0, 11, 0, 14, 2, 15, 2, 20, 5, 30, 10);
        expected.forEach((score, mod) -> assertThat(CoreRules.abilityModifier(score)).as("score %d", score).isEqualTo(mod));
    }

    @Test
    void proficiencyBonusMatchesTheCharacterAdvancementTable() {
        Map<Integer, Integer> expected = Map.of(1, 2, 4, 2, 5, 3, 8, 3, 9, 4, 12, 4, 13, 5, 16, 5, 17, 6, 20, 6);
        expected.forEach((lvl, pb) -> assertThat(CoreRules.proficiencyBonus(lvl)).as("level %d", lvl).isEqualTo(pb));
    }

    @Test
    void proficiencyBonusRejectsOutOfRangeLevels() {
        assertThatThrownBy(() -> CoreRules.proficiencyBonus(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CoreRules.proficiencyBonus(21)).isInstanceOf(IllegalArgumentException.class);
    }
}
