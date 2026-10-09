package org.omnomnom.dnd.sim.domain.rng;

/** A source of uniformly distributed doubles in [0, 1) for one stream. Ports the TypeScript {@code Rng}. */
@FunctionalInterface
public interface Rng {

    /** The next value in [0, 1). */
    double next();
}
