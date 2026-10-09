package org.omnomnom.dnd.sim.domain.core;

import java.util.Optional;
import java.util.function.ToDoubleFunction;

/** Selections over small candidate lists that must stay deterministic, because simulation results depend on them. */
public final class Picks {

    private Picks() {}

    /**
     * The candidate with the highest score, the <em>first</em> of any tied candidates (a later one must be strictly
     * higher to replace it). A NaN score never replaces the current pick. Empty for an empty input.
     */
    public static <T> Optional<T> firstMax(Iterable<? extends T> candidates, ToDoubleFunction<? super T> score) {
        T best = null;
        double bestScore = 0;
        for (T candidate : candidates) {
            double s = score.applyAsDouble(candidate);
            if (best == null || s > bestScore) {
                best = candidate;
                bestScore = s;
            }
        }
        return Optional.ofNullable(best);
    }
}
