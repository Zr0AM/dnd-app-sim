package org.omnomnom.dnd.sim.domain.opt.evaluation;

import java.util.List;
import java.util.function.ToDoubleFunction;

/** Helpers for reducing a list of per-run records to the number series the {@link Stats} functions take. */
final class Samples {

    private Samples() {}

    /** One value per row, in row order. */
    static <T> List<Double> column(List<T> rows, ToDoubleFunction<? super T> value) {
        return rows.stream().map(row -> value.applyAsDouble(row)).toList();
    }
}
