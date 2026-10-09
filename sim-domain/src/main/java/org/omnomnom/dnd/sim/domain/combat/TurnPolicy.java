package org.omnomnom.dnd.sim.domain.combat;

/** Decides what one creature does on its turn by calling the {@link TurnApi}. */
@FunctionalInterface
public interface TurnPolicy {

    /** A policy that ends the turn immediately. */
    TurnPolicy IDLE = api -> {};

    void act(TurnApi api);
}
