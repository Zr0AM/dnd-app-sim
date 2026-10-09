package org.omnomnom.dnd.sim.domain.combat;

import org.omnomnom.dnd.sim.domain.dice.Advantage;
import org.omnomnom.dnd.sim.domain.dice.Dice;
import org.omnomnom.dnd.sim.domain.rng.Rng;

/**
 * Attack-roll and saving-throw resolution (SRD "Attack Rolls", "Saving Throws").
 *
 * <p>Attack rolls: a natural 20 always hits and is a Critical Hit; a natural 1 always misses. The crit range can
 * be widened (Champion 19-20, then 18-20); any die face in the crit range is a hit and a crit. Saving throws:
 * d20 + bonus &gt;= DC, with no natural 1/20 special case.
 */
public final class AttackResolver {

    private AttackResolver() {}

    public record AttackParams(int attackBonus, int targetAc, Advantage advantage, int critRange) {

        public static AttackParams of(int attackBonus, int targetAc) {
            return new AttackParams(attackBonus, targetAc, Advantage.NORMAL, AttackProfile.DEFAULT_CRIT_RANGE);
        }

        public AttackParams withAdvantage(Advantage adv) {
            return new AttackParams(attackBonus, targetAc, adv, critRange);
        }

        public AttackParams withCritRange(int range) {
            return new AttackParams(attackBonus, targetAc, advantage, range);
        }
    }

    public record AttackResult(int d20, int total, boolean hit, boolean crit) {}

    public record SaveParams(int saveBonus, int dc, Advantage advantage) {

        public static SaveParams of(int saveBonus, int dc) {
            return new SaveParams(saveBonus, dc, Advantage.NORMAL);
        }
    }

    public record SaveResult(int d20, int total, boolean success) {}

    public static AttackResult resolveAttack(Rng rng, AttackParams params) {
        int d20 = Dice.rollD20(rng, params.advantage());
        int total = d20 + params.attackBonus();
        if (d20 == 1) {
            return new AttackResult(d20, total, false, false);
        }
        if (d20 >= params.critRange()) {
            return new AttackResult(d20, total, true, true);
        }
        return new AttackResult(d20, total, total >= params.targetAc(), false);
    }

    /** Probability an attack hits (crit included), accounting for the natural-1 auto-miss and natural-20 auto-hit. */
    public static double chanceAttackHits(AttackParams params) {
        int needed = Math.min(20, Math.max(2, params.targetAc() - params.attackBonus()));
        return Dice.chanceToHit(needed, params.advantage());
    }

    /** Probability an attack is a Critical Hit. */
    public static double chanceAttackCrits(AttackParams params) {
        return Dice.chanceToHit(params.critRange(), params.advantage());
    }

    public static SaveResult resolveSave(Rng rng, SaveParams params) {
        int d20 = Dice.rollD20(rng, params.advantage());
        int total = d20 + params.saveBonus();
        return new SaveResult(d20, total, total >= params.dc());
    }

    /** Probability a saving throw succeeds. */
    public static double chanceSaveSucceeds(SaveParams params) {
        int needed = Math.min(21, Math.max(1, params.dc() - params.saveBonus()));
        return Dice.chanceToHit(needed, params.advantage());
    }
}
