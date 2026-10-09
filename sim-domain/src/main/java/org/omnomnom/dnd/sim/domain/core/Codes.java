package org.omnomnom.dnd.sim.domain.core;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Lookup helper shared by the {@link Coded} enums. */
final class Codes {

    private Codes() {}

    static <E extends Enum<E> & Coded> Map<String, E> index(E[] values) {
        return Map.copyOf(java.util.Arrays.stream(values).collect(Collectors.toMap(Coded::code, Function.identity())));
    }

    static <E extends Enum<E> & Coded> E lookup(Map<String, E> index, String code, String what) {
        E found = index.get(code);
        if (found == null) {
            throw new IllegalArgumentException("unknown " + what + ": " + code);
        }
        return found;
    }
}
