package org.omnomnom.dnd.sim.domain.combat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.domain.combat.spell.BuffSpec;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.Condition;
import org.omnomnom.dnd.sim.domain.rng.Rng;

/** Unit tests for {@link StatusEffects}, the condition / buff state extracted from {@link Combatant}. */
class StatusEffectsTest {

    private static final Rng UNUSED_RNG = () -> 0.5;
    private static final ToIntFunction<Ability> NO_BONUS = a -> 0;

    private static TimedConditionSpec timed(Condition condition, int rounds) {
        return new TimedConditionSpec(condition, "src", rounds, null, null);
    }

    private static BuffSpec buff(String id, String source, int rounds) {
        return new BuffSpec(id, source, rounds, null, null, 0, false, null);
    }

    @Test
    void applyTimedConditionSetsTheBaseFlag() {
        StatusEffects e = new StatusEffects();
        e.applyTimedCondition(timed(Condition.PRONE, 2));
        assertThat(e.has(Condition.PRONE)).isTrue();
        assertThat(e.baseConditions()).containsExactly(Condition.PRONE);
    }

    @Test
    void tickDecrementsAndRemovesAtZeroInOrder() {
        StatusEffects e = new StatusEffects();
        e.applyTimedCondition(timed(Condition.PRONE, 1));
        e.applyTimedCondition(timed(Condition.FRIGHTENED, 2));
        e.applyTimedCondition(timed(Condition.POISONED, 1));
        assertThat(e.tickTimedConditions(UNUSED_RNG, NO_BONUS))
                .containsExactly(Condition.PRONE, Condition.POISONED);
        assertThat(e.has(Condition.PRONE)).isFalse();
        assertThat(e.has(Condition.FRIGHTENED)).isTrue();
        assertThat(e.tickTimedConditions(UNUSED_RNG, NO_BONUS)).containsExactly(Condition.FRIGHTENED);
    }

    @Test
    void successfulRepeatSaveEndsTheConditionEarly() {
        StatusEffects e = new StatusEffects();
        e.applyTimedCondition(new TimedConditionSpec(
                Condition.STUNNED, "src", 3, new RepeatSave(Ability.CON, 10, true), null));
        assertThat(e.tickTimedConditions(ScriptedRng.faces(15), NO_BONUS)).containsExactly(Condition.STUNNED);
        assertThat(e.has(Condition.STUNNED)).isFalse();
    }

    @Test
    void failedRepeatSaveLeavesTheCondition() {
        StatusEffects e = new StatusEffects();
        e.applyTimedCondition(new TimedConditionSpec(
                Condition.STUNNED, "src", 3, new RepeatSave(Ability.CON, 10, true), null));
        assertThat(e.tickTimedConditions(ScriptedRng.faces(5), NO_BONUS)).isEmpty();
        assertThat(e.has(Condition.STUNNED)).isTrue();
    }

    @Test
    void flagStaysWhileAnotherTimedEntryGrantsTheSameCondition() {
        StatusEffects e = new StatusEffects();
        e.applyTimedCondition(timed(Condition.PRONE, 1));
        e.applyTimedCondition(timed(Condition.PRONE, 2));
        assertThat(e.tickTimedConditions(UNUSED_RNG, NO_BONUS)).containsExactly(Condition.PRONE);
        assertThat(e.has(Condition.PRONE)).isTrue();
        assertThat(e.tickTimedConditions(UNUSED_RNG, NO_BONUS)).containsExactly(Condition.PRONE);
        assertThat(e.has(Condition.PRONE)).isFalse();
    }

    @Test
    void endConcentrationConditionsRemovesOnlyTheOwnersEntries() {
        StatusEffects e = new StatusEffects();
        e.applyTimedCondition(new TimedConditionSpec(Condition.PRONE, "a", 5, null, "caster1"));
        e.applyTimedCondition(new TimedConditionSpec(Condition.FRIGHTENED, "b", 5, null, "caster2"));
        e.applyTimedCondition(new TimedConditionSpec(Condition.POISONED, "c", 5, null, "caster1"));
        e.endConcentrationConditions("caster1");
        assertThat(e.has(Condition.PRONE)).isFalse();
        assertThat(e.has(Condition.POISONED)).isFalse();
        assertThat(e.has(Condition.FRIGHTENED)).isTrue();
    }

    @Test
    void reapplyingABuffReplacesInPlace() {
        StatusEffects e = new StatusEffects();
        e.applyBuff(buff("bless", "c1", 1));
        e.applyBuff(buff("haste", "c2", 3));
        e.applyBuff(buff("bless", "c1", 3));
        assertThat(e.buffSources()).containsExactly("c1", "c2");
        // "bless" kept its original slot: it ends after "haste", not first.
        assertThat(e.tickBuffs()).isEmpty();
        assertThat(e.tickBuffs()).isEmpty();
        assertThat(e.tickBuffs()).containsExactly("bless", "haste");
    }

    @Test
    void tickBuffsReturnsEndedIdsInOrder() {
        StatusEffects e = new StatusEffects();
        e.applyBuff(buff("a", "c1", 1));
        e.applyBuff(buff("b", "c2", 2));
        e.applyBuff(buff("c", "c3", 1));
        assertThat(e.tickBuffs()).containsExactly("a", "c");
        assertThat(e.hasBuff("b")).isTrue();
        assertThat(e.tickBuffs()).containsExactly("b");
    }

    @Test
    void endConcentrationBuffsRemovesOnlyTheOwners() {
        StatusEffects e = new StatusEffects();
        e.applyBuff(new BuffSpec("a", "c1", 5, null, null, 0, false, "caster1"));
        e.applyBuff(new BuffSpec("b", "c2", 5, null, null, 0, false, "caster2"));
        e.applyBuff(new BuffSpec("c", "c3", 5, null, null, 0, false, "caster1"));
        e.endConcentrationBuffs("caster1");
        assertThat(e.hasBuff("a")).isFalse();
        assertThat(e.hasBuff("c")).isFalse();
        assertThat(e.hasBuff("b")).isTrue();
        assertThat(e.buffSources()).containsExactly("c2");
    }
}
