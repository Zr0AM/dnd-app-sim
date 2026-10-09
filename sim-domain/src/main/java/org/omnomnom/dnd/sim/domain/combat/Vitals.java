package org.omnomnom.dnd.sim.domain.combat;

import org.omnomnom.dnd.sim.domain.dice.Dice;
import org.omnomnom.dnd.sim.domain.rng.Rng;

/**
 * Hit Points, temporary HP, exhaustion and the 0-HP / death-save pipeline of one combatant. Purely internal:
 * condition flags and Wild Shape side effects stay with {@link Combatant}.
 */
final class Vitals {

    private final int maxHp;
    private int hp;
    private int tempHp;
    private int exhaustionLevel;
    private int deathSuccesses;
    private int deathFailures;
    private boolean stable;
    private boolean dead;

    Vitals(int maxHp) {
        this.maxHp = maxHp;
        this.hp = maxHp;
    }

    int hp() {
        return hp;
    }

    int tempHp() {
        return tempHp;
    }

    boolean dead() {
        return dead;
    }

    boolean stable() {
        return stable;
    }

    int deathSuccesses() {
        return deathSuccesses;
    }

    int deathFailures() {
        return deathFailures;
    }

    int exhaustionLevel() {
        return exhaustionLevel;
    }

    /** Alive and above 0 HP (not unconscious). */
    boolean isConscious() {
        return !dead && hp > 0;
    }

    /** Not dead (may be unconscious at 0 HP). */
    boolean isAlive() {
        return !dead;
    }

    /** At 0 HP, not dead: unconscious and dying (or stable). */
    boolean isDying() {
        return !dead && hp == 0;
    }

    // Package-private mutators for the engine and tests.
    void setHp(int v) {
        this.hp = v;
    }

    void setDead(boolean v) {
        this.dead = v;
    }

    void setDeathFailures(int v) {
        this.deathFailures = v;
    }

    /** Grant temporary HP. Temp HP does not stack; the larger pool wins. */
    void grantTempHp(int amount) {
        if (amount > tempHp) {
            tempHp = amount;
        }
    }

    /**
     * Apply {@code amount} damage (already mitigated for type). {@code critical} matters only when the creature is
     * at 0 HP, where a crit inflicts two death-save failures.
     */
    DamageOutcome takeDamage(int amount, boolean critical) {
        if (dead || amount <= 0) {
            return DamageOutcome.NONE;
        }

        // Damage taken while already at 0 HP causes death-save failures, not HP loss.
        if (hp == 0) {
            int fails = critical ? 2 : 1;
            stable = false;
            if (amount >= maxHp) {
                dead = true;
                return new DamageOutcome(0, 0, false, true, fails);
            }
            deathFailures += fails;
            boolean died = deathFailures >= 3;
            if (died) {
                dead = true;
            }
            return new DamageOutcome(0, 0, false, died, fails);
        }

        int absorbedByTemp = Math.min(tempHp, amount);
        tempHp -= absorbedByTemp;
        int toHp = amount - absorbedByTemp;
        int newHp = hp - toHp;

        if (newHp > 0) {
            hp = newHp;
            return new DamageOutcome(toHp, absorbedByTemp, false, false, 0);
        }

        // Reduced to 0. Massive damage: if the overflow equals or exceeds max HP, die.
        int overflow = -newHp;
        hp = 0;
        if (overflow >= maxHp) {
            dead = true;
            return new DamageOutcome(maxHp, absorbedByTemp, true, true, 0);
        }
        // Drop to 0: unconscious, death saves reset.
        deathSuccesses = 0;
        deathFailures = 0;
        stable = false;
        return new DamageOutcome(toHp, absorbedByTemp, true, false, 0);
    }

    /** Restore HP. Healing from 0 revives: clears dying/stable and resets death saves. Returns HP restored. */
    int heal(int amount) {
        if (dead || amount <= 0) {
            return 0;
        }
        int before = hp;
        hp = Math.min(maxHp, hp + amount);
        int healed = hp - before;
        if (before == 0 && hp > 0) {
            deathSuccesses = 0;
            deathFailures = 0;
            stable = false;
        }
        return healed;
    }

    /** Stabilize a dying creature (for example a successful Medicine check). */
    void stabilize() {
        if (isDying()) {
            stable = true;
        }
    }

    /**
     * Roll a death saving throw (made at the start of a turn spent at 0 HP). 10+ succeeds; a natural 20 revives at
     * 1 HP; a natural 1 is two failures; three successes stabilize; three failures kill.
     */
    DeathSaveOutcome rollDeathSave(Rng rng) {
        if (!isDying() || stable) {
            return new DeathSaveOutcome(0, false, stable, dead, false);
        }
        int d20 = Dice.rollD20(rng);
        if (d20 == 20) {
            heal(1);
            return new DeathSaveOutcome(d20, true, false, false, true);
        }
        if (d20 == 1) {
            deathFailures += 2;
        } else if (d20 >= 10) {
            deathSuccesses += 1;
        } else {
            deathFailures += 1;
        }
        if (deathFailures >= 3) {
            dead = true;
            return new DeathSaveOutcome(d20, false, false, true, false);
        }
        if (deathSuccesses >= 3) {
            stable = true;
            return new DeathSaveOutcome(d20, d20 >= 10, true, false, false);
        }
        return new DeathSaveOutcome(d20, d20 >= 10, false, false, false);
    }

    /** Raise exhaustion by {@code n} levels (0-6); at level 6 the creature dies. */
    void gainExhaustion(int n) {
        exhaustionLevel = Math.clamp(exhaustionLevel + n, 0, 6);
        if (exhaustionLevel >= 6) {
            dead = true;
        }
    }
}
