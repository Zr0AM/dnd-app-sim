package org.omnomnom.dnd.sim.domain.combat;

import java.util.List;
import org.omnomnom.dnd.sim.domain.core.Side;
import org.omnomnom.dnd.sim.domain.grid.Cell;
import org.omnomnom.dnd.sim.domain.grid.GridMath;

/**
 * Read-only queries over an encounter's combatants. It wraps the encounter's live list rather than copying it, so it
 * always reflects the current state, and every result keeps the list's order (which seeded results depend on).
 */
final class Roster {

    private final List<Combatant> combatants;
    private final int cellFt;

    Roster(List<Combatant> combatants, int cellFt) {
        this.combatants = combatants;
        this.cellFt = cellFt;
    }

    boolean anyConscious(Side side) {
        return combatants.stream().anyMatch(c -> c.side() == side && c.isConscious());
    }

    /** Conscious combatants on the other side from {@code self}. */
    List<Combatant> consciousOpponents(Combatant self) {
        return combatants.stream().filter(c -> c.side() != self.side() && c.isConscious()).toList();
    }

    /** Conscious combatants on {@code self}'s side, not counting {@code self}. */
    List<Combatant> consciousAllies(Combatant self) {
        return combatants.stream().filter(c -> c.side() == self.side() && c.isConscious() && c != self).toList();
    }

    /** Allies of {@code self} that are not dead (a dying ally can still be healed), not counting {@code self}. */
    List<Combatant> livingAllies(Combatant self) {
        return combatants.stream().filter(c -> c.side() == self.side() && c.isAlive() && c != self).toList();
    }

    /** Conscious opponents of {@code self} within {@code radiusFt} of {@code origin}. */
    List<Combatant> consciousOpponentsWithin(Combatant self, Cell origin, int radiusFt) {
        return combatants.stream()
                .filter(c -> c.side() != self.side() && c.isConscious() && GridMath.distanceFt(origin, c.position(), cellFt) <= radiusFt)
                .toList();
    }
}
