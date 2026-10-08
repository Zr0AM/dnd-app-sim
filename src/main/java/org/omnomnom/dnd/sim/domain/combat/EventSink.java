package org.omnomnom.dnd.sim.domain.combat;

/** Receives combat events as the encounter emits them. */
@FunctionalInterface
public interface EventSink {

    /** Discards every event (for callers that only need the outcome). */
    EventSink NOOP = event -> {};

    void accept(CombatEvent event);
}
