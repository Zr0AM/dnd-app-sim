package org.omnomnom.dnd.sim.domain.combat;

import java.util.List;
import org.omnomnom.dnd.sim.domain.combat.event.CombatEvent;
import org.omnomnom.dnd.sim.domain.combat.event.EventSink;
import org.omnomnom.dnd.sim.domain.grid.Grid;
import org.omnomnom.dnd.sim.domain.grid.GridMath;
import org.omnomnom.dnd.sim.domain.rng.LabeledRandom;

/**
 * What every part of one fight shares: the grid, the combatants, the seeded random source and the event sink, plus the
 * one counter that spans the whole fight. Like {@link Encounter}, it is single-use and not thread-safe.
 */
final class FightContext {

    private final Grid grid;
    private final Roster roster;
    private final LabeledRandom rng;
    private final EventSink sink;
    /** Monotonic counter so each concentration save draws a distinct stream value. */
    private int concentrationSeq;

    FightContext(Grid grid, List<Combatant> combatants, LabeledRandom rng, EventSink sink) {
        this.grid = grid;
        this.roster = new Roster(combatants, grid.cellFt());
        this.rng = rng;
        this.sink = sink;
    }

    Grid grid() {
        return grid;
    }

    Roster roster() {
        return roster;
    }

    LabeledRandom rng() {
        return rng;
    }

    void log(CombatEvent event) {
        sink.accept(event);
    }

    int distanceFt(Combatant a, Combatant b) {
        return GridMath.distanceFt(a.position(), b.position(), grid.cellFt());
    }

    /** The next concentration-save sequence number; the first call returns 0. */
    int nextConcentrationSeq() {
        return concentrationSeq++;
    }
}
