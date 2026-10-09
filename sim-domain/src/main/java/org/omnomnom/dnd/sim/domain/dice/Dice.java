package org.omnomnom.dnd.sim.domain.dice;

import org.omnomnom.dnd.sim.domain.rng.Rng;

/**
 * A dice term: {@code count}d{@code sides} plus a flat {@code bonus}, with the roll and closed-form expected
 * value helpers from the TypeScript {@code dice.ts}. Every roll draws from a named {@link Rng} stream, so the
 * common-random-numbers guarantee carries through.
 *
 * <p>A flat amount is represented as {@code Dice.of(0, 1, amount)}.
 */
public record Dice(int count, int sides, int bonus) {

    public static Dice of(int count, int sides) {
        return new Dice(count, sides, 0);
    }

    public static Dice of(int count, int sides, int bonus) {
        return new Dice(count, sides, bonus);
    }

    /** Rolls this term: {@code count} dice of {@code sides} faces, plus the bonus. */
    public int roll(Rng rng) {
        return rollDice(rng, count, sides) + bonus;
    }

    /** Expected value of this term. */
    public double mean() {
        return count * meanDie(sides) + bonus;
    }

    /** The same dice with the flat bonus dropped (used for the extra dice on a critical hit). */
    public Dice withoutBonus() {
        return new Dice(count, sides, 0);
    }

    /** Rolls one die of {@code sides} faces: an integer in [1, sides]. */
    public static int rollDie(Rng rng, int sides) {
        if (sides < 1) {
            throw new IllegalArgumentException("sides must be a positive integer, got " + sides);
        }
        return 1 + (int) Math.floor(rng.next() * sides);
    }

    /** Rolls {@code count}d{@code sides} and sums them (no bonus). */
    public static int rollDice(Rng rng, int count, int sides) {
        if (count < 0) {
            throw new IllegalArgumentException("count must be a non-negative integer, got " + count);
        }
        int total = 0;
        for (int i = 0; i < count; i++) {
            total += rollDie(rng, sides);
        }
        return total;
    }

    /**
     * Rolls a d20 under (dis)advantage. Advantage keeps the higher of two rolls, disadvantage the lower. The raw
     * die face is returned; crit and modifier handling belong to the attack resolver.
     */
    public static int rollD20(Rng rng, Advantage adv) {
        int a = rollDie(rng, 20);
        if (adv == Advantage.NORMAL) {
            return a;
        }
        int b = rollDie(rng, 20);
        return adv == Advantage.ADVANTAGE ? Math.max(a, b) : Math.min(a, b);
    }

    /** Rolls a d20 with no (dis)advantage. */
    public static int rollD20(Rng rng) {
        return rollD20(rng, Advantage.NORMAL);
    }

    /** Expected value of a single die of {@code sides} faces: (sides + 1) / 2. */
    public static double meanDie(int sides) {
        return (sides + 1) / 2.0;
    }

    /** Expected value of a d20 face under (dis)advantage. */
    public static double meanD20(Advantage adv) {
        // E[max] = sum_{k=1..20} k*(2k-1)/400 = 13.825; E[min] = 7.175 by symmetry.
        return switch (adv) {
            case ADVANTAGE -> 13.825;
            case DISADVANTAGE -> 7.175;
            case NORMAL -> 10.5;
        };
    }

    /**
     * Probability that a d20 roll meets or beats {@code target} (the number needed on the die face), under
     * (dis)advantage, clamped to [0, 1]. This is the raw face probability; the attack resolver layers the nat-1
     * and nat-20 rules on top.
     */
    public static double chanceToHit(int target, Advantage adv) {
        int need = Math.min(21, Math.max(1, target));
        double p = (21 - need) / 20.0;
        return switch (adv) {
            case ADVANTAGE -> 1 - (1 - p) * (1 - p);
            case DISADVANTAGE -> p * p;
            case NORMAL -> p;
        };
    }

    public static double chanceToHit(int target) {
        return chanceToHit(target, Advantage.NORMAL);
    }
}
