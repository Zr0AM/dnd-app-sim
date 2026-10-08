package org.omnomnom.dnd.sim.domain.combat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.domain.combat.SpellKind.AttackDamage;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.AbilityScores;
import org.omnomnom.dnd.sim.domain.core.DamageType;
import org.omnomnom.dnd.sim.domain.dice.Dice;

/**
 * Port of the pure parts of {@code sim/src/combat/spell.spec.ts}: scaling and spellcasting stats. The "casting in the
 * engine" tests need the encounter loop and the spell catalog and move with those ports.
 */
class SpellTest {

    @Test
    void cantripsGainADieAtLevelsFiveElevenAndSeventeen() {
        DamageScaling d = Spell.cantripDice(1, 10);
        assertThat(d.at(0, 1).mean()).isEqualTo(Dice.of(1, 10).mean());
        assertThat(d.at(0, 5)).isEqualTo(Dice.of(2, 10));
        assertThat(d.at(0, 11)).isEqualTo(Dice.of(3, 10));
        assertThat(d.at(0, 17)).isEqualTo(Dice.of(4, 10));
    }

    @Test
    void upcastDiceAddPerSlotAboveBase() {
        DamageScaling d = Spell.upcastDice(3, 8, 6); // Fireball: 8d6 + 1d6 per slot over 3
        assertThat(d.at(3, 5).count()).isEqualTo(8);
        assertThat(d.at(5, 5).count()).isEqualTo(10);
    }

    @Test
    void raysScaleWithUpcast() {
        AttackDamage scorchingRay = AttackDamage.of(Spell.upcastDice(2, 2, 6, 0), DamageType.FIRE)
                .withRays(3)
                .withRaysPerUpcast(1);
        assertThat(Spell.raysAt(scorchingRay, 2, 2)).isEqualTo(3);
        assertThat(Spell.raysAt(scorchingRay, 4, 2)).isEqualTo(5); // +1 ray per slot over 2
    }

    @Test
    void healAndBuffTargetAlliesOthersTargetEnemies() {
        Spell heal = new Spell("cure", "Cure Wounds", 1, Spell.CastingTime.ACTION, 5, false,
                new SpellKind.Heal(Spell.upcastDice(1, 2, 8), true));
        Spell bolt = new Spell("bolt", "Fire Bolt", 0, Spell.CastingTime.ACTION, 120, false,
                AttackDamage.of(Spell.cantripDice(1, 10), DamageType.FIRE));
        assertThat(heal.targetsAllies()).isTrue();
        assertThat(bolt.targetsAllies()).isFalse();
    }

    private static Combatant wizard() {
        return new Combatant(CombatantSpec.builder("wiz", "Wizard", Side.PARTY, 5,
                        AbilityScores.of(8, 14, 14, 18, 12, 10), 13, 30)
                .spellcasting(new SpellcastingSpec(
                        Ability.INT,
                        List.of(new SpellcastingSpec.Slot(1, 4), new SpellcastingSpec.Slot(2, 3), new SpellcastingSpec.Slot(3, 2)),
                        List.of(), List.of(), false))
                .build());
    }

    @Test
    void computesSaveDcAndSpellAttackFromTheAbility() {
        Combatant w = wizard();
        assertThat(w.spellAttackBonus()).isEqualTo(3 + 4); // prof 3 (L5) + Int 4
        assertThat(w.spellSaveDc()).isEqualTo(8 + 3 + 4);
    }

    @Test
    void nonCastersHaveNoSpellStats() {
        Combatant fighter = TestCombatants.make();
        assertThat(fighter.spellAbility()).isNull();
        assertThat(fighter.spellSaveDc()).isZero();
        assertThat(fighter.spellAttackBonus()).isZero();
    }

    @Test
    void tracksAndSpendsSlots() {
        Combatant w = wizard();
        assertThat(w.slotCount(3)).isEqualTo(2);
        assertThat(w.spendSlot(3)).isTrue();
        assertThat(w.slotCount(3)).isEqualTo(1);
        assertThat(w.availableSlotLevels()).containsExactly(1, 2, 3);
        w.spendSlot(3);
        assertThat(w.spendSlot(3)).isFalse();
        assertThat(w.availableSlotLevels()).containsExactly(1, 2);
    }
}
