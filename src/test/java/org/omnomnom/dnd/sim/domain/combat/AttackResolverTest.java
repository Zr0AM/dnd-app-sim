package org.omnomnom.dnd.sim.domain.combat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.domain.combat.AttackResolver.AttackParams;
import org.omnomnom.dnd.sim.domain.combat.AttackResolver.AttackResult;
import org.omnomnom.dnd.sim.domain.combat.AttackResolver.SaveParams;
import org.omnomnom.dnd.sim.domain.dice.Advantage;
import org.omnomnom.dnd.sim.domain.rng.LabeledRandom;
import org.omnomnom.dnd.sim.domain.rng.Rng;

/** Port of {@code sim/src/combat/attack.spec.ts}. */
class AttackResolverTest {

    @Test
    void naturalOneAlwaysMissesEvenAgainstLowAc() {
        AttackResult r = AttackResolver.resolveAttack(ScriptedRng.faces(1), AttackParams.of(20, 1));
        assertThat(r.d20()).isEqualTo(1);
        assertThat(r.hit()).isFalse();
        assertThat(r.crit()).isFalse();
    }

    @Test
    void naturalTwentyAlwaysHitsAndCritsEvenAgainstHighAc() {
        AttackResult r = AttackResolver.resolveAttack(ScriptedRng.faces(20), AttackParams.of(-5, 99));
        assertThat(r.d20()).isEqualTo(20);
        assertThat(r.hit()).isTrue();
        assertThat(r.crit()).isTrue();
    }

    @Test
    void hitsWhenTotalMeetsAc() {
        AttackResult r = AttackResolver.resolveAttack(ScriptedRng.faces(10), AttackParams.of(5, 15));
        assertThat(r.total()).isEqualTo(15);
        assertThat(r.hit()).isTrue();
        assertThat(r.crit()).isFalse();
    }

    @Test
    void missesWhenTotalIsBelowAc() {
        assertThat(AttackResolver.resolveAttack(ScriptedRng.faces(10), AttackParams.of(4, 15)).hit()).isFalse();
    }

    @Test
    void critsOnAWidenedRange() {
        AttackResult r = AttackResolver.resolveAttack(ScriptedRng.faces(19), AttackParams.of(0, 99).withCritRange(19));
        assertThat(r.hit()).isTrue();
        assertThat(r.crit()).isTrue();
    }

    @Test
    void empiricalHitRateMatchesChanceAttackHits() {
        Rng rng = new LabeledRandom(321).stream("atk");
        AttackParams params = AttackParams.of(5, 15);
        int hits = 0;
        int n = 200000;
        for (int i = 0; i < n; i++) {
            if (AttackResolver.resolveAttack(rng, params).hit()) {
                hits++;
            }
        }
        assertThat((double) hits / n).isCloseTo(AttackResolver.chanceAttackHits(params), within(0.005));
    }

    @Test
    void empiricalCritRateMatchesChanceAttackCritsUnderAdvantage() {
        Rng rng = new LabeledRandom(654).stream("crit");
        AttackParams params = AttackParams.of(0, 10).withAdvantage(Advantage.ADVANTAGE).withCritRange(19);
        int crits = 0;
        int n = 200000;
        for (int i = 0; i < n; i++) {
            if (AttackResolver.resolveAttack(rng, params).crit()) {
                crits++;
            }
        }
        assertThat((double) crits / n).isCloseTo(AttackResolver.chanceAttackCrits(params), within(0.005));
    }

    @Test
    void chanceAttackHitsNeedingElevenIsFiftyPercent() {
        assertThat(AttackResolver.chanceAttackHits(AttackParams.of(4, 15))).isCloseTo(0.5, within(1e-10));
    }

    @Test
    void chanceAttackHitsCapsAtNinetyFiveAndFloorsAtFivePercent() {
        assertThat(AttackResolver.chanceAttackHits(AttackParams.of(100, 10))).isCloseTo(0.95, within(1e-10));
        assertThat(AttackResolver.chanceAttackHits(AttackParams.of(-100, 10))).isCloseTo(0.05, within(1e-10));
    }

    @Test
    void saveSucceedsWhenTotalMeetsDcWithNoNaturalTwentySpecial() {
        assertThat(AttackResolver.resolveSave(ScriptedRng.faces(10), SaveParams.of(5, 15)).success()).isTrue();
        assertThat(AttackResolver.resolveSave(ScriptedRng.faces(10), SaveParams.of(4, 15)).success()).isFalse();
    }

    @Test
    void aNaturalTwentyThatFallsShortStillFailsTheSave() {
        var r = AttackResolver.resolveSave(ScriptedRng.faces(20), SaveParams.of(0, 25));
        assertThat(r.d20()).isEqualTo(20);
        assertThat(r.success()).isFalse();
    }

    @Test
    void empiricalSaveRateMatchesChanceSaveSucceeds() {
        Rng rng = new LabeledRandom(111).stream("save");
        SaveParams params = SaveParams.of(3, 14);
        int ok = 0;
        int n = 200000;
        for (int i = 0; i < n; i++) {
            if (AttackResolver.resolveSave(rng, params).success()) {
                ok++;
            }
        }
        assertThat((double) ok / n).isCloseTo(AttackResolver.chanceSaveSucceeds(params), within(0.005));
    }
}
