package org.omnomnom.dnd.sim.domain.core;

/**
 * A value that steps up with level, for the "level >= 17 ... else level >= 11 ..." ladders: the value of the highest
 * tier the level has reached, or the base value below them all.
 */
public final class Tiers {

    /** The value that applies from {@code minLevel} upward. */
    public record Tier<T>(int minLevel, T value) {}

    private Tiers() {}

    public static <T> Tier<T> from(int minLevel, T value) {
        return new Tier<>(minLevel, value);
    }

    /** The value of the tier with the greatest {@code minLevel} not above {@code level}, else {@code base}. Tiers may be given in any order. */
    @SafeVarargs
    public static <T> T pick(int level, T base, Tier<T>... tiers) {
        T result = base;
        int reached = Integer.MIN_VALUE;
        for (Tier<T> tier : tiers) {
            if (tier.minLevel() <= level && tier.minLevel() > reached) {
                result = tier.value();
                reached = tier.minLevel();
            }
        }
        return result;
    }
}
