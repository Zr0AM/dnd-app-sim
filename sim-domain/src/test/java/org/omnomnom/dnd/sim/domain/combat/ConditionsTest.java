package org.omnomnom.dnd.sim.domain.combat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.omnomnom.dnd.sim.domain.combat.TestCombatants.plain;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.Condition;
import org.omnomnom.dnd.sim.domain.dice.Advantage;

/** Port of {@code sim/src/combat/conditions.spec.ts}. */
class ConditionsTest {

    private static Combatant with(Condition c) {
        Combatant x = plain();
        x.addCondition(c);
        return x;
    }

    @Test
    void expandsImpliedConditions() {
        var eff = Conditions.effectiveConditions(with(Condition.UNCONSCIOUS));
        assertThat(eff).contains(Condition.UNCONSCIOUS, Condition.INCAPACITATED, Condition.PRONE);
    }

    @Test
    void paralyzedImpliesIncapacitated() {
        assertThat(Conditions.effectiveConditions(with(Condition.PARALYZED))).contains(Condition.INCAPACITATED);
    }

    @Test
    void incapacitatingConditionsStopActionsAndReactions() {
        Combatant c = plain();
        assertThat(Conditions.canAct(c)).isTrue();
        c.addCondition(Condition.STUNNED);
        assertThat(Conditions.isIncapacitated(c)).isTrue();
        assertThat(Conditions.canAct(c)).isFalse();
        assertThat(Conditions.canReact(c)).isFalse();
    }

    @Test
    void aDyingCreatureCannotAct() {
        Combatant c = plain();
        c.takeDamage(30);
        assertThat(Conditions.canAct(c)).isFalse();
    }

    @Test
    void attackAdvantageIsNormalBetweenTwoHealthyCreatures() {
        assertThat(Conditions.attackAdvantage(plain(), plain(), true)).isEqualTo(Advantage.NORMAL);
    }

    @Test
    void proneDefenderAdvantageInMeleeDisadvantageAtRange() {
        Combatant def = with(Condition.PRONE);
        assertThat(Conditions.attackAdvantage(plain(), def, true)).isEqualTo(Advantage.ADVANTAGE);
        assertThat(Conditions.attackAdvantage(plain(), def, false)).isEqualTo(Advantage.DISADVANTAGE);
    }

    @Test
    void restrainedDefenderGrantsAdvantage() {
        assertThat(Conditions.attackAdvantage(plain(), with(Condition.RESTRAINED), true)).isEqualTo(Advantage.ADVANTAGE);
    }

    @Test
    void blindedAttackerHasDisadvantage() {
        assertThat(Conditions.attackAdvantage(with(Condition.BLINDED), plain(), true)).isEqualTo(Advantage.DISADVANTAGE);
    }

    @Test
    void advantageAndDisadvantageCancel() {
        // Prone defender in melee (advantage) + poisoned attacker (disadvantage) => normal.
        assertThat(Conditions.attackAdvantage(with(Condition.POISONED), with(Condition.PRONE), true))
                .isEqualTo(Advantage.NORMAL);
    }

    @Test
    void invisibleAttackerHasAdvantageAndInvisibleDefenderImposesDisadvantage() {
        assertThat(Conditions.attackAdvantage(with(Condition.INVISIBLE), plain(), true)).isEqualTo(Advantage.ADVANTAGE);
        assertThat(Conditions.attackAdvantage(plain(), with(Condition.INVISIBLE), true)).isEqualTo(Advantage.DISADVANTAGE);
    }

    @Test
    void paralyzedWithinFiveFeetIsAnAutoCrit() {
        Combatant def = with(Condition.PARALYZED);
        assertThat(Conditions.isAutoCritTarget(def, true)).isTrue();
        assertThat(Conditions.isAutoCritTarget(def, false)).isFalse();
    }

    @Test
    void unconsciousWithinFiveFeetIsAnAutoCrit() {
        Combatant def = plain();
        def.takeDamage(30);
        assertThat(Conditions.isAutoCritTarget(def, true)).isTrue();
    }

    @Test
    void stunnedAndPetrifiedAreNotAutoCrits() {
        assertThat(Conditions.isAutoCritTarget(with(Condition.STUNNED), true)).isFalse();
        assertThat(Conditions.isAutoCritTarget(with(Condition.PETRIFIED), true)).isFalse();
    }

    @Test
    void inertConditionsAutoFailStrAndDexSavesOnly() {
        Combatant c = with(Condition.PARALYZED);
        assertThat(Conditions.autoFailsSave(c, Ability.STR)).isTrue();
        assertThat(Conditions.autoFailsSave(c, Ability.DEX)).isTrue();
        assertThat(Conditions.autoFailsSave(c, Ability.CON)).isFalse();
        assertThat(Conditions.autoFailsSave(c, Ability.WIS)).isFalse();
    }

    @Test
    void restrainedGivesDisadvantageOnDexSavesOnly() {
        Combatant c = with(Condition.RESTRAINED);
        assertThat(Conditions.saveAdvantage(c, Ability.DEX)).isEqualTo(Advantage.DISADVANTAGE);
        assertThat(Conditions.saveAdvantage(c, Ability.CON)).isEqualTo(Advantage.NORMAL);
    }

    @Test
    void exhaustionPenalizesD20TestsAndSpeed() {
        Combatant c = plain();
        c.gainExhaustion(3);
        assertThat(Conditions.exhaustionD20Penalty(c)).isEqualTo(-6);
        assertThat(Conditions.effectiveSpeedFt(c)).isEqualTo(30 - 15);
        assertThat(c.hasCondition(Condition.EXHAUSTION)).isTrue();
        assertThat(c.conditionList()).contains(Condition.EXHAUSTION);
    }

    @Test
    void diesAtExhaustionLevelSix() {
        Combatant c = plain();
        c.gainExhaustion(6);
        assertThat(c.dead()).isTrue();
    }

    @Test
    void speedIsZeroWhileMovementIsLocked() {
        for (Condition cond : List.of(Condition.GRAPPLED, Condition.RESTRAINED, Condition.PARALYZED, Condition.STUNNED)) {
            assertThat(Conditions.effectiveSpeedFt(with(cond))).as(cond.name()).isZero();
        }
    }

    @Test
    void proneDoesNotZeroSpeed() {
        assertThat(Conditions.effectiveSpeedFt(with(Condition.PRONE))).isEqualTo(30);
    }
}
