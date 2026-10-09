package org.omnomnom.dnd.sim.domain.combat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link Vitals}, the HP / death-save state extracted from {@link Combatant}. */
class VitalsTest {

    private static Vitals vitals() {
        return new Vitals(40);
    }

    @Test
    void temporaryHpAbsorbsFirstAndDoesNotStack() {
        Vitals v = vitals();
        v.grantTempHp(5);
        v.grantTempHp(3);
        assertThat(v.tempHp()).isEqualTo(5);
        DamageOutcome out = v.takeDamage(8, false);
        assertThat(out.absorbedByTemp()).isEqualTo(5);
        assertThat(out.hpLost()).isEqualTo(3);
        assertThat(v.tempHp()).isZero();
        assertThat(v.hp()).isEqualTo(37);
    }

    @Test
    void droppingToZeroResetsDeathSavesAndClearsStable() {
        Vitals v = vitals();
        v.setHp(0);
        v.setDeathFailures(2);
        v.stabilize();
        assertThat(v.stable()).isTrue();
        v.setHp(10);
        DamageOutcome out = v.takeDamage(10, false);
        assertThat(out.dropped()).isTrue();
        assertThat(out.died()).isFalse();
        assertThat(v.isDying()).isTrue();
        assertThat(v.stable()).isFalse();
        assertThat(v.deathFailures()).isZero();
        assertThat(v.deathSuccesses()).isZero();
    }

    @Test
    void massiveDamageKillsOutright() {
        Vitals v = vitals();
        DamageOutcome out = v.takeDamage(80, false);
        assertThat(out.dropped()).isTrue();
        assertThat(out.died()).isTrue();
        assertThat(v.dead()).isTrue();
    }

    @Test
    void damageAtZeroHpAddsFailuresAndCritAddsTwo() {
        Vitals v = vitals();
        v.setHp(0);
        DamageOutcome out = v.takeDamage(5, false);
        assertThat(out.deathSaveFailures()).isEqualTo(1);
        assertThat(v.deathFailures()).isEqualTo(1);
        out = v.takeDamage(5, true);
        assertThat(out.deathSaveFailures()).isEqualTo(2);
        assertThat(v.deathFailures()).isEqualTo(3);
        assertThat(out.died()).isTrue();
        assertThat(v.dead()).isTrue();
    }

    @Test
    void massiveDamageAtZeroHpKills() {
        Vitals v = vitals();
        v.setHp(0);
        DamageOutcome out = v.takeDamage(40, false);
        assertThat(out.died()).isTrue();
        assertThat(v.dead()).isTrue();
    }

    @Test
    void thirdDeathSaveFailureKills() {
        Vitals v = vitals();
        v.setHp(0);
        v.setDeathFailures(2);
        DamageOutcome out = v.takeDamage(5, false);
        assertThat(out.died()).isTrue();
        assertThat(v.dead()).isTrue();
    }

    @Test
    void healFromZeroRevivesAndResetsCounters() {
        Vitals v = vitals();
        v.setHp(0);
        v.setDeathFailures(2);
        int healed = v.heal(7);
        assertThat(healed).isEqualTo(7);
        assertThat(v.hp()).isEqualTo(7);
        assertThat(v.isDying()).isFalse();
        assertThat(v.deathFailures()).isZero();
        assertThat(v.deathSuccesses()).isZero();
    }

    @Test
    void healWhenDeadReturnsZero() {
        Vitals v = vitals();
        v.setDead(true);
        assertThat(v.heal(7)).isZero();
        assertThat(v.hp()).isEqualTo(40);
    }

    @Test
    void naturalTwentyRevivesAtOneHp() {
        Vitals v = vitals();
        v.setHp(0);
        DeathSaveOutcome out = v.rollDeathSave(ScriptedRng.faces(20));
        assertThat(out.revived()).isTrue();
        assertThat(v.hp()).isEqualTo(1);
        assertThat(v.isConscious()).isTrue();
    }

    @Test
    void naturalOneAddsTwoFailures() {
        Vitals v = vitals();
        v.setHp(0);
        DeathSaveOutcome out = v.rollDeathSave(ScriptedRng.faces(1));
        assertThat(out.success()).isFalse();
        assertThat(v.deathFailures()).isEqualTo(2);
    }

    @Test
    void tenOrBetterSucceedsNineOrWorseFails() {
        Vitals v = vitals();
        v.setHp(0);
        assertThat(v.rollDeathSave(ScriptedRng.faces(10)).success()).isTrue();
        assertThat(v.deathSuccesses()).isEqualTo(1);
        assertThat(v.rollDeathSave(ScriptedRng.faces(9)).success()).isFalse();
        assertThat(v.deathFailures()).isEqualTo(1);
    }

    @Test
    void threeSuccessesStabilize() {
        Vitals v = vitals();
        v.setHp(0);
        v.rollDeathSave(ScriptedRng.faces(10));
        v.rollDeathSave(ScriptedRng.faces(10));
        DeathSaveOutcome out = v.rollDeathSave(ScriptedRng.faces(10));
        assertThat(out.stabilized()).isTrue();
        assertThat(v.stable()).isTrue();
        assertThat(v.dead()).isFalse();
    }

    @Test
    void exhaustionClampsToSixAndSixKills() {
        Vitals v = vitals();
        v.gainExhaustion(9);
        assertThat(v.exhaustionLevel()).isEqualTo(6);
        assertThat(v.dead()).isTrue();

        Vitals w = vitals();
        w.gainExhaustion(-2);
        assertThat(w.exhaustionLevel()).isZero();
        assertThat(w.dead()).isFalse();
    }
}
