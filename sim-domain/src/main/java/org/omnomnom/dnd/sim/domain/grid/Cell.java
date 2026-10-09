package org.omnomnom.dnd.sim.domain.grid;

/** An integer cell coordinate on the grid. */
public record Cell(int x, int y) {

    public static Cell of(int x, int y) {
        return new Cell(x, y);
    }
}
