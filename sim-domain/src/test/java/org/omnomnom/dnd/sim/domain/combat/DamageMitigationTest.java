package org.omnomnom.dnd.sim.domain.combat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.domain.core.DamageResponse;
import org.omnomnom.dnd.sim.domain.core.DamageType;

/** Port of {@code sim/src/combat/damage.spec.ts}. */
class DamageMitigationTest {

    private static final Map<DamageType, DamageResponse> NONE = Map.of();

    @Test
    void passesNormalDamageThrough() {
        assertThat(DamageMitigation.mitigate(10, DamageType.FIRE, NONE)).isEqualTo(10);
    }

    @Test
    void halvesResistantDamageRoundingDown() {
        var r = Map.of(DamageType.FIRE, DamageResponse.RESISTANT);
        assertThat(DamageMitigation.mitigate(10, DamageType.FIRE, r)).isEqualTo(5);
        assertThat(DamageMitigation.mitigate(7, DamageType.FIRE, r)).isEqualTo(3);
    }

    @Test
    void doublesVulnerableDamage() {
        var r = Map.of(DamageType.FIRE, DamageResponse.VULNERABLE);
        assertThat(DamageMitigation.mitigate(10, DamageType.FIRE, r)).isEqualTo(20);
    }

    @Test
    void zeroesImmuneDamage() {
        var r = Map.of(DamageType.FIRE, DamageResponse.IMMUNE);
        assertThat(DamageMitigation.mitigate(999, DamageType.FIRE, r)).isZero();
    }

    @Test
    void onlyAffectsTheMatchingType() {
        var r = Map.of(DamageType.FIRE, DamageResponse.RESISTANT);
        assertThat(DamageMitigation.mitigate(10, DamageType.COLD, r)).isEqualTo(10);
    }

    @Test
    void appliesFlatReductionBeforeTheResponse() {
        // 28 fire, reduce 5 -> 23, resistance halves (round down) -> 11.
        assertThat(DamageMitigation.mitigate(28, DamageType.FIRE, Map.of(DamageType.FIRE, DamageResponse.RESISTANT), 5))
                .isEqualTo(11);
        // Same reduction, vulnerable instead: 23 doubled -> 46.
        assertThat(DamageMitigation.mitigate(28, DamageType.FIRE, Map.of(DamageType.FIRE, DamageResponse.VULNERABLE), 5))
                .isEqualTo(46);
    }

    @Test
    void flatReductionAloneCannotGoBelowZero() {
        assertThat(DamageMitigation.mitigate(3, DamageType.FIRE, NONE, 5)).isZero();
    }

    @Test
    void returnsZeroForNonPositiveInput() {
        assertThat(DamageMitigation.mitigate(0, DamageType.FIRE, NONE)).isZero();
        assertThat(DamageMitigation.mitigate(-4, DamageType.FIRE, NONE)).isZero();
    }
}
