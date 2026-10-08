package org.omnomnom.dnd.sim.domain.grid;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometry on the battlefield grid. Distance uses the 2024 SRD simple grid method: every square counts as 5 feet,
 * diagonals included. That is Chebyshev distance times the cell size, not Euclidean and not the older 5-10-5 rule.
 */
public final class GridMath {

    /** Default cell edge length in feet. */
    public static final int DEFAULT_CELL_FT = 5;

    private GridMath() {}

    /** Chebyshev step count between two cells (diagonals count as one step). */
    public static int stepDistance(Cell a, Cell b) {
        return Math.max(Math.abs(a.x() - b.x()), Math.abs(a.y() - b.y()));
    }

    /** Distance in feet between two cells under the simple grid method. */
    public static int distanceFt(Cell a, Cell b, int cellFt) {
        return stepDistance(a, b) * cellFt;
    }

    public static int distanceFt(Cell a, Cell b) {
        return distanceFt(a, b, DEFAULT_CELL_FT);
    }

    /** Two cells are adjacent if they touch, including diagonally (not the same cell). */
    public static boolean isAdjacent(Cell a, Cell b) {
        return stepDistance(a, b) == 1;
    }

    /** Whether {@code target} is within {@code reachFt} of {@code from} for a melee attack of that reach. */
    public static boolean withinReach(Cell from, Cell target, int reachFt, int cellFt) {
        if (from.equals(target)) {
            return false;
        }
        return distanceFt(from, target, cellFt) <= reachFt;
    }

    public static boolean withinReach(Cell from, Cell target, int reachFt) {
        return withinReach(from, target, reachFt, DEFAULT_CELL_FT);
    }

    public static boolean withinReach(Cell from, Cell target) {
        return withinReach(from, target, 5, DEFAULT_CELL_FT);
    }

    /** Whether {@code target} is within {@code rangeFt} of {@code from} for a ranged effect. */
    public static boolean withinRange(Cell from, Cell target, int rangeFt, int cellFt) {
        return distanceFt(from, target, cellFt) <= rangeFt;
    }

    public static boolean withinRange(Cell from, Cell target, int rangeFt) {
        return withinRange(from, target, rangeFt, DEFAULT_CELL_FT);
    }

    /** The cells within {@code radiusFt} of {@code center} (a square "radius" under the grid method). */
    public static List<Cell> cellsWithin(Cell center, int radiusFt, int cellFt) {
        int steps = radiusFt / cellFt;
        List<Cell> out = new ArrayList<>();
        for (int dy = -steps; dy <= steps; dy++) {
            for (int dx = -steps; dx <= steps; dx++) {
                out.add(new Cell(center.x() + dx, center.y() + dy));
            }
        }
        return out;
    }

    public static List<Cell> cellsWithin(Cell center, int radiusFt) {
        return cellsWithin(center, radiusFt, DEFAULT_CELL_FT);
    }
}
