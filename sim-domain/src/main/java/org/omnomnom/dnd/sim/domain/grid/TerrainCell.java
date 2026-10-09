package org.omnomnom.dnd.sim.domain.grid;

/** Per-cell terrain flags. */
public record TerrainCell(boolean wall, boolean difficult) {

    public static final TerrainCell OPEN = new TerrainCell(false, false);
}
