package org.omnomnom.dnd.sim.domain.grid;

/**
 * A rectangular terrain grid. Immutable once built, so one instance can be shared safely across concurrent runs
 * (the TypeScript grid was mutable and only treated as read-only by convention). Out-of-bounds cells read as
 * walls, so callers can treat the map edge as impassable without special-casing.
 */
public final class Grid {

    private static final TerrainCell OUT_OF_BOUNDS = new TerrainCell(true, false);

    private final int width;
    private final int height;
    private final int cellFt;
    private final TerrainCell[] terrain;

    private Grid(Builder b) {
        this.width = b.width;
        this.height = b.height;
        this.cellFt = b.cellFt;
        this.terrain = b.terrain.clone();
    }

    /** A grid with no terrain. */
    public static Grid open(int width, int height) {
        return builder(width, height).build();
    }

    public static Builder builder(int width, int height) {
        return new Builder(width, height, GridMath.DEFAULT_CELL_FT);
    }

    public static Builder builder(int width, int height, int cellFt) {
        return new Builder(width, height, cellFt);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int cellFt() {
        return cellFt;
    }

    public boolean inBounds(Cell c) {
        return c.x() >= 0 && c.y() >= 0 && c.x() < width && c.y() < height;
    }

    public TerrainCell at(Cell c) {
        return inBounds(c) ? terrain[c.y() * width + c.x()] : OUT_OF_BOUNDS;
    }

    public boolean isWall(Cell c) {
        return at(c).wall();
    }

    public boolean isDifficult(Cell c) {
        return at(c).difficult();
    }

    /** A cell a creature may stand in: in bounds and not a wall. */
    public boolean isPassable(Cell c) {
        return inBounds(c) && !at(c).wall();
    }

    public int distanceFt(Cell a, Cell b) {
        return GridMath.distanceFt(a, b, cellFt);
    }

    /** Builds a {@link Grid}. Terrain flags passed as {@code null} leave that flag unchanged. */
    public static final class Builder {

        private final int width;
        private final int height;
        private final int cellFt;
        private final TerrainCell[] terrain;

        private Builder(int width, int height, int cellFt) {
            if (width < 1 || height < 1) {
                throw new IllegalArgumentException("grid dimensions must be positive integers, got " + width + "x" + height);
            }
            if (cellFt < 1) {
                throw new IllegalArgumentException("cellFt must be positive, got " + cellFt);
            }
            this.width = width;
            this.height = height;
            this.cellFt = cellFt;
            this.terrain = new TerrainCell[width * height];
            java.util.Arrays.fill(terrain, TerrainCell.OPEN);
        }

        private boolean inBounds(Cell c) {
            return c.x() >= 0 && c.y() >= 0 && c.x() < width && c.y() < height;
        }

        /** Sets terrain flags on one cell; throws if the cell is out of bounds. */
        public Builder setTerrain(Cell c, Boolean wall, Boolean difficult) {
            if (!inBounds(c)) {
                throw new IllegalArgumentException("cell (" + c.x() + ", " + c.y() + ") is out of bounds");
            }
            int i = c.y() * width + c.x();
            TerrainCell prev = terrain[i];
            terrain[i] = new TerrainCell(wall != null ? wall : prev.wall(), difficult != null ? difficult : prev.difficult());
            return this;
        }

        public Builder wall(Cell c) {
            return setTerrain(c, true, null);
        }

        public Builder difficult(Cell c) {
            return setTerrain(c, null, true);
        }

        /** Fills a rectangle of cells with terrain flags, clipped to bounds. */
        public Builder fillRect(int x, int y, int w, int h, Boolean wall, Boolean difficult) {
            for (int yy = y; yy < y + h; yy++) {
                for (int xx = x; xx < x + w; xx++) {
                    Cell c = new Cell(xx, yy);
                    if (inBounds(c)) {
                        setTerrain(c, wall, difficult);
                    }
                }
            }
            return this;
        }

        public Grid build() {
            return new Grid(this);
        }
    }
}
