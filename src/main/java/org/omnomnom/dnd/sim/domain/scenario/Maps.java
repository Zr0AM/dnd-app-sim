package org.omnomnom.dnd.sim.domain.scenario;

import java.util.List;
import java.util.Map;
import org.omnomnom.dnd.sim.domain.grid.Cell;
import org.omnomnom.dnd.sim.domain.grid.Grid;

/**
 * The battle maps of the scenario library. They are intentionally varied so a build cannot win everywhere by one
 * trick: open ground, and a corridor with a one-cell doorway.
 */
public final class Maps {

    private Maps() {}

    public static final String OPEN_FIELD_ID = "open-field";
    public static final String CORRIDOR_CHOKEPOINT_ID = "corridor-chokepoint";

    /** Open ground: no cover, full mobility. The neutral baseline. */
    public static final MapLayout OPEN_FIELD = new MapLayout(
            OPEN_FIELD_ID,
            Grid.open(16, 12),
            List.of(Cell.of(1, 6)),
            List.of(Cell.of(14, 6), Cell.of(14, 4), Cell.of(14, 8), Cell.of(13, 5), Cell.of(13, 7), Cell.of(12, 6)));

    /** A vertical wall at x=8 with a doorway at y=4, forcing single-file approaches. */
    public static final MapLayout CORRIDOR_CHOKEPOINT = corridor();

    private static MapLayout corridor() {
        Grid.Builder b = Grid.builder(16, 9);
        for (int y = 0; y < 9; y++) {
            if (y != 4) {
                b.wall(Cell.of(8, y));
            }
        }
        return new MapLayout(
                CORRIDOR_CHOKEPOINT_ID,
                b.build(),
                List.of(Cell.of(6, 4)), // just inside the doorway on the hero's side
                List.of(Cell.of(12, 4), Cell.of(13, 3), Cell.of(13, 5), Cell.of(14, 4), Cell.of(12, 3), Cell.of(12, 5)));
    }

    public static final String PARTY_FIELD_ID = "party-field";

    /** The shared 22 x 14 map of the reference-party harness: six party cells on the left, 30 enemy cells on the right. */
    public static final MapLayout PARTY_FIELD = partyField();

    private static MapLayout partyField() {
        List<Cell> enemy = new java.util.ArrayList<>();
        for (int x = 18; x <= 20; x++) {
            for (int y = 2; y <= 11; y++) {
                enemy.add(Cell.of(x, y));
            }
        }
        return new MapLayout(
                PARTY_FIELD_ID,
                Grid.open(22, 14),
                List.of(Cell.of(1, 5), Cell.of(1, 7), Cell.of(1, 9), Cell.of(2, 6), Cell.of(2, 8), Cell.of(2, 4)),
                enemy);
    }

    private static final Map<String, MapLayout> BY_ID =
            Map.of(OPEN_FIELD_ID, OPEN_FIELD, CORRIDOR_CHOKEPOINT_ID, CORRIDOR_CHOKEPOINT, PARTY_FIELD_ID, PARTY_FIELD);

    /** The map with this id; throws if unknown. */
    public static MapLayout byId(String id) {
        MapLayout m = BY_ID.get(id);
        if (m == null) {
            throw new IllegalArgumentException("unknown map: " + id);
        }
        return m;
    }

    public static List<MapLayout> all() {
        return List.of(OPEN_FIELD, CORRIDOR_CHOKEPOINT, PARTY_FIELD);
    }
}
