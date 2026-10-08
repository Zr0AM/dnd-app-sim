package org.omnomnom.dnd.sim.domain.scenario;

import java.util.List;
import org.omnomnom.dnd.sim.domain.grid.Cell;
import org.omnomnom.dnd.sim.domain.grid.Grid;

/**
 * A battle map with its deployment cells. The grid is immutable, so one instance is shared across every run.
 *
 * @param partyStarts party deployment cells in placement order (a single cell for the solo maps)
 * @param enemyStarts enemy deployment cells, in placement order (enough for a small pack)
 */
public record MapLayout(String id, Grid grid, List<Cell> partyStarts, List<Cell> enemyStarts) {

    public MapLayout {
        partyStarts = List.copyOf(partyStarts);
        enemyStarts = List.copyOf(enemyStarts);
    }

    /** Where the solo hero starts: the first party cell. */
    public Cell heroStart() {
        return partyStarts.get(0);
    }
}
