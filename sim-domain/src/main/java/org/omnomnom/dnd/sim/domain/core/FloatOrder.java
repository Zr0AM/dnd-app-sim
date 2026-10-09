package org.omnomnom.dnd.sim.domain.core;

import java.util.Comparator;
import java.util.function.ToDoubleFunction;

/**
 * Orders floating-point scores the way the TypeScript engine's {@code (a, b) => b - a} comparators do, so sorts and
 * tie-breaks (and therefore seeded results) match it. {@code -0.0} and {@code 0.0} tie, as they do under
 * subtraction. NaN does not occur in scores here; unlike a subtraction comparator, which calls NaN equal to
 * everything, {@link Double#compare} orders it after every number.
 */
public final class FloatOrder {

    private FloatOrder() {}

    /** Negative, zero or positive as {@code a} is below, equal to or above {@code b}; the zeros tie. */
    public static int compare(double a, double b) {
        return Double.compare(a + 0.0, b + 0.0); // adding 0.0 turns -0.0 into 0.0
    }

    /** Highest score first. The sort is stable, so equal scores keep their input order. */
    public static <T> Comparator<T> descendingBy(ToDoubleFunction<? super T> score) {
        return (a, b) -> compare(score.applyAsDouble(b), score.applyAsDouble(a));
    }
}
