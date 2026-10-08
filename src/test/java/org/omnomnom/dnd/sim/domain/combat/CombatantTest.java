package org.omnomnom.dnd.sim.domain.combat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.omnomnom.dnd.sim.domain.combat.TestCombatants.make;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.AbilityScores;
import org.omnomnom.dnd.sim.domain.core.Condition;

/** Port of {@code sim/src/combat/actor.spec.ts}. */
class CombatantTest {

    @Test
    void startsAtFullHpAndConscious() {
        Combatant c = make();
        assertThat(c.hp()).isEqualTo(40);
        assertThat(c.isConscious()).isTrue();
        assertThat(c.isAlive()).isTrue();
        assertThat(c.isDying()).isFalse();
    }

    @Test
    void computesAbilityModifiersAndProficiencyBonus() {
        Combatant c = make();
        assertThat(c.abilityMod(Ability.STR)).isEqualTo(3);
        assertThat(c.abilityMod(Ability.CHA)).isEqualTo(-1);
        assertThat(c.proficiencyBonus()).isEqualTo(3);
    }

    @Test
    void addsProficiencyToProficientSavesOnly() {
        Combatant c = make();
        assertThat(c.saveBonus(Ability.CON)).isEqualTo(2 + 3);
        assertThat(c.saveBonus(Ability.DEX)).isEqualTo(2);
    }

    @Test
    void explicitSaveBonusOverridesTheComputedValue() {
        Combatant c = make(b -> b.saveBonuses(java.util.Map.of(Ability.WIS, 9)));
        assertThat(c.saveBonus(Ability.WIS)).isEqualTo(9);
        assertThat(c.saveBonus(Ability.CON)).isEqualTo(5);
    }

    // ---- takeDamage ----------------------------------------------------------------------------

    @Test
    void takeDamageReducesHp() {
        Combatant c = make();
        DamageOutcome out = c.takeDamage(10);
        assertThat(c.hp()).isEqualTo(30);
        assertThat(out.hpLost()).isEqualTo(10);
        assertThat(out.dropped()).isFalse();
    }

    @Test
    void temporaryHpAbsorbsFirstAndDoesNotStack() {
        Combatant c = make();
        c.grantTempHp(5);
        c.grantTempHp(3);
        DamageOutcome out = c.takeDamage(8);
        assertThat(out.absorbedByTemp()).isEqualTo(5);
        assertThat(out.hpLost()).isEqualTo(3);
        assertThat(c.tempHp()).isZero();
        assertThat(c.hp()).isEqualTo(37);
    }

    @Test
    void dropsToZeroAndBecomesDyingNotDead() {
        Combatant c = make();
        DamageOutcome out = c.takeDamage(40);
        assertThat(c.hp()).isZero();
        assertThat(out.dropped()).isTrue();
        assertThat(out.died()).isFalse();
        assertThat(c.isDying()).isTrue();
        assertThat(c.isConscious()).isFalse();
        assertThat(c.hasCondition(Condition.UNCONSCIOUS)).isTrue();
    }

    private static Combatant withMaxHp(int maxHp) {
        return new Combatant(CombatantSpec.builder("t", "T", Side.PARTY, 5, AbilityScores.allTens(), 16, maxHp).build());
    }

    @Test
    void diesFromMassiveDamage() {
        Combatant c = withMaxHp(12);
        c.setHp(6);
        DamageOutcome out = c.takeDamage(18); // 6 to zero, 12 overflow >= 12 max
        assertThat(out.died()).isTrue();
        assertThat(c.dead()).isTrue();
        assertThat(c.isAlive()).isFalse();
    }

    @Test
    void doesNotDieWhenOverflowIsBelowMaxHp() {
        Combatant c = withMaxHp(12);
        c.setHp(6);
        DamageOutcome out = c.takeDamage(10); // overflow 4 < 12
        assertThat(out.dropped()).isTrue();
        assertThat(out.died()).isFalse();
        assertThat(c.isDying()).isTrue();
    }

    @Test
    void damageAtZeroHpCausesDeathSaveFailuresTwoOnACrit() {
        Combatant c = make();
        c.takeDamage(40);
        DamageOutcome out1 = c.takeDamage(3);
        assertThat(out1.deathSaveFailures()).isEqualTo(1);
        assertThat(c.deathFailures()).isEqualTo(1);
        DamageOutcome out2 = c.takeDamage(3, true);
        assertThat(out2.deathSaveFailures()).isEqualTo(2);
        assertThat(c.deathFailures()).isEqualTo(3);
        assertThat(c.dead()).isTrue();
    }

    @Test
    void aSingleHitForMaxHpWhileAtZeroKillsOutright() {
        Combatant c = make();
        c.takeDamage(40);
        DamageOutcome out = c.takeDamage(40);
        assertThat(out.died()).isTrue();
        assertThat(c.dead()).isTrue();
    }

    @Test
    void ignoresNonPositiveDamageAndTheDead() {
        Combatant c = make();
        assertThat(c.takeDamage(0).hpLost()).isZero();
        c.setDead(true);
        assertThat(c.takeDamage(10).hpLost()).isZero();
    }

    // ---- heal ----------------------------------------------------------------------------------

    @Test
    void healRestoresHpUpToTheMaximum() {
        Combatant c = make();
        c.takeDamage(30);
        assertThat(c.heal(100)).isEqualTo(30);
        assertThat(c.hp()).isEqualTo(40);
    }

    @Test
    void healRevivesFromZeroClearingDyingAndDeathSaves() {
        Combatant c = make();
        c.takeDamage(40);
        c.setDeathFailures(2);
        assertThat(c.heal(5)).isEqualTo(5);
        assertThat(c.hp()).isEqualTo(5);
        assertThat(c.isDying()).isFalse();
        assertThat(c.isConscious()).isTrue();
        assertThat(c.deathFailures()).isZero();
        assertThat(c.hasCondition(Condition.UNCONSCIOUS)).isFalse();
    }

    @Test
    void cannotHealTheDead() {
        Combatant c = make();
        c.setDead(true);
        assertThat(c.heal(10)).isZero();
    }

    // ---- death saves ---------------------------------------------------------------------------

    @Test
    void threeSuccessesStabilize() {
        Combatant c = make();
        c.takeDamage(40);
        c.rollDeathSave(ScriptedRng.faces(10));
        c.rollDeathSave(ScriptedRng.faces(12));
        DeathSaveOutcome out = c.rollDeathSave(ScriptedRng.faces(15));
        assertThat(out.stabilized()).isTrue();
        assertThat(c.stable()).isTrue();
        assertThat(c.isDying()).isTrue();
    }

    @Test
    void threeFailuresKill() {
        Combatant c = make();
        c.takeDamage(40);
        c.rollDeathSave(ScriptedRng.faces(5));
        DeathSaveOutcome out = c.rollDeathSave(ScriptedRng.faces(1)); // nat 1 = two failures
        assertThat(out.died()).isTrue();
        assertThat(c.dead()).isTrue();
    }

    @Test
    void aNaturalTwentyRevivesAtOneHp() {
        Combatant c = make();
        c.takeDamage(40);
        DeathSaveOutcome out = c.rollDeathSave(ScriptedRng.faces(20));
        assertThat(out.revived()).isTrue();
        assertThat(c.hp()).isEqualTo(1);
        assertThat(c.isConscious()).isTrue();
    }

    @Test
    void aStableCreatureMakesNoDeathSaves() {
        Combatant c = make();
        c.takeDamage(40);
        c.stabilize();
        DeathSaveOutcome out = c.rollDeathSave(ScriptedRng.faces(1));
        assertThat(out.stabilized()).isTrue();
        assertThat(c.deathFailures()).isZero();
    }

    @Test
    void takingDamageEndsStability() {
        Combatant c = make();
        c.takeDamage(40);
        c.stabilize();
        assertThat(c.stable()).isTrue();
        c.takeDamage(3);
        assertThat(c.stable()).isFalse();
        assertThat(c.deathFailures()).isEqualTo(1);
    }

    // ---- conditions ----------------------------------------------------------------------------

    @Test
    void addsChecksAndRemovesConditions() {
        Combatant c = make();
        assertThat(c.hasCondition(Condition.PRONE)).isFalse();
        c.addCondition(Condition.PRONE);
        assertThat(c.hasCondition(Condition.PRONE)).isTrue();
        assertThat(c.conditionList()).contains(Condition.PRONE);
        c.removeCondition(Condition.PRONE);
        assertThat(c.hasCondition(Condition.PRONE)).isFalse();
    }

    @Test
    void conditionListIncludesImplicitUnconsciousAndExhaustion() {
        Combatant c = make();
        c.gainExhaustion(1);
        c.takeDamage(40);
        assertThat(c.conditionList()).containsExactlyInAnyOrder(Condition.UNCONSCIOUS, Condition.EXHAUSTION);
    }

    // ---- spell-slot recovery -------------------------------------------------------------------

    private static Combatant caster(boolean shortRestSlots) {
        return make(b -> b.spellcasting(new SpellcastingSpec(
                Ability.CHA, List.of(new SpellcastingSpec.Slot(3, 2)), List.of(), List.of(), shortRestSlots)));
    }

    @Test
    void pactMagicSlotsReturnOnAShortRest() {
        Combatant warlock = caster(true);
        warlock.spendSlot(3);
        warlock.spendSlot(3);
        assertThat(warlock.slotCount(3)).isZero();
        warlock.shortRest();
        assertThat(warlock.slotCount(3)).isEqualTo(2);
    }

    @Test
    void ordinarySlotsReturnOnlyOnALongRest() {
        Combatant wizard = caster(false);
        wizard.spendSlot(3);
        wizard.shortRest();
        assertThat(wizard.slotCount(3)).isEqualTo(1);
        wizard.longRest();
        assertThat(wizard.slotCount(3)).isEqualTo(2);
    }
}
