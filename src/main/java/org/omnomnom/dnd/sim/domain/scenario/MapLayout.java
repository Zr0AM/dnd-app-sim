package org.omnomnom.dnd.sim.domain.scenario;

import java.util.List;
import org.omnomnom.dnd.sim.domain.grid.Cell;
import org.omnomnom.dnd.sim.domain.grid.Grid;

/**
 * A battle map with its deployment cells. The grid is immutable, so one instance is shared across every run.
 *
 * @param heroStart where the solo hero starts
 * @param enemyStarts enemy deployment cells, in placement order (enough for a small pack)
 */
public record MapLayout(String id, Grid grid, Cell heroStart, List<Cell> enemyStarts) {

    public MapLayout {
        enemyStarts = List.copyOf(enemyStarts);
    }
}
