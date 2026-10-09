package org.omnomnom.dnd.sim.domain.combat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.domain.content.feature.AuraOfProtectionFeature;
import org.omnomnom.dnd.sim.domain.core.AbilityScores;
import org.omnomnom.dnd.sim.domain.core.Side;
import org.omnomnom.dnd.sim.domain.grid.Cell;

/** Port of {@code sim/src/combat/aura.spec.ts}, run against the real {@link AuraOfProtectionFeature}. */
class AuraSaveBonusTest {

    private static Combatant who(String id, Side side, int cha, Cell pos, boolean aura) {
        CombatantSpec.Builder b = CombatantSpec.builder(id, id, side, 11, AbilityScores.of(10, 10, 10, 10, 10, cha), 12, 40)
                .position(pos);
        if (aura) {
            b.features(List.of(AuraOfProtectionFeature::new));
        }
        return new Combatant(b.build());
    }

    @Test
    void addsANearbyPaladinsChaModifierToAnAlliesSave() {
        Combatant pal = who("pal", Side.PARTY, 20, new Cell(0, 0), true); // Cha +5
        Combatant ally = who("ally", Side.PARTY, 8, new Cell(1, 0), false);
        assertThat(Encounter.auraSaveBonus(List.of(pal, ally), ally, 5)).isEqualTo(5);
        assertThat(Encounter.auraSaveBonus(List.of(pal, ally), pal, 5)).isEqualTo(5); // benefits from its own aura
    }

    @Test
    void doesNotReachBeyondTenFeet() {
        Combatant pal = who("pal", Side.PARTY, 20, new Cell(0, 0), true);
        Combatant far = who("far", Side.PARTY, 20, new Cell(3, 0), false); // 15 ft away
        assertThat(Encounter.auraSaveBonus(List.of(pal, far), far, 5)).isZero();
    }

    @Test
    void doesNotHelpEnemies() {
        Combatant pal = who("pal", Side.PARTY, 20, new Cell(0, 0), true);
        Combatant foe = who("foe", Side.ENEMY, 20, new Cell(1, 0), false);
        assertThat(Encounter.auraSaveBonus(List.of(pal, foe), foe, 5)).isZero();
    }

    @Test
    void doesNotStackTheBestNearbyAuraApplies() {
        Combatant strong = who("strong", Side.PARTY, 20, new Cell(0, 0), true);
        Combatant weak = who("weak", Side.PARTY, 14, new Cell(1, 0), true); // Cha +2
        Combatant ally = who("ally", Side.PARTY, 10, new Cell(1, 1), false);
        assertThat(Encounter.auraSaveBonus(List.of(strong, weak, ally), ally, 5)).isEqualTo(5); // max(+5, +2)
    }

    @Test
    void aNegativeCharismaModifierNeverHurts() {
        Combatant pal = who("pal", Side.PARTY, 8, new Cell(0, 0), true); // Cha -1
        Combatant ally = who("ally", Side.PARTY, 10, new Cell(1, 0), false);
        assertThat(Encounter.auraSaveBonus(List.of(pal, ally), ally, 5)).isZero();
    }

    @Test
    void anUnconsciousPaladinProjectsNoAura() {
        Combatant pal = who("pal", Side.PARTY, 20, new Cell(0, 0), true);
        pal.takeDamage(1000);
        Combatant ally = who("ally", Side.PARTY, 10, new Cell(1, 0), false);
        assertThat(Encounter.auraSaveBonus(List.of(pal, ally), ally, 5)).isZero();
    }
}
