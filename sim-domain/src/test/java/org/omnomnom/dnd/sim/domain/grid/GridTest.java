package org.omnomnom.dnd.sim.domain.grid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Port of {@code sim/src/grid/grid.spec.ts}. */
class GridTest {

    private static Cell c(int x, int y) {
        return Cell.of(x, y);
    }

    @Test
    void countsDiagonalsAsOneStepLikeThe2024Srd() {
        assertThat(GridMath.stepDistance(c(0, 0), c(3, 0))).isEqualTo(3);
        assertThat(GridMath.stepDistance(c(0, 0), c(3, 3))).isEqualTo(3);
        assertThat(GridMath.stepDistance(c(0, 0), c(3, 1))).isEqualTo(3);
    }

    @Test
    void convertsToFeetWithTheCellSize() {
        assertThat(GridMath.distanceFt(c(0, 0), c(3, 3))).isEqualTo(15);
        assertThat(GridMath.distanceFt(c(0, 0), c(2, 0), 10)).isEqualTo(20);
    }

    @Test
    void cellEqualityAndSameCellDistance() {
        assertThat(c(1, 2)).isEqualTo(c(1, 2));
        assertThat(GridMath.distanceFt(c(4, 4), c(4, 4))).isZero();
    }

    @Test
    void orthogonalAndDiagonalNeighborsAreAdjacent() {
        assertThat(GridMath.isAdjacent(c(5, 5), c(5, 6))).isTrue();
        assertThat(GridMath.isAdjacent(c(5, 5), c(6, 6))).isTrue();
        assertThat(GridMath.isAdjacent(c(5, 5), c(5, 5))).isFalse();
        assertThat(GridMath.isAdjacent(c(5, 5), c(7, 5))).isFalse();
    }

    @Test
    void reachExcludesTheAttackersOwnCellAndRespectsReachFeet() {
        assertThat(GridMath.withinReach(c(0, 0), c(0, 0))).isFalse();
        assertThat(GridMath.withinReach(c(0, 0), c(1, 0))).isTrue();
        assertThat(GridMath.withinReach(c(0, 0), c(2, 0))).isFalse();
        assertThat(GridMath.withinReach(c(0, 0), c(2, 0), 10)).isTrue();
    }

    @Test
    void rangeIsDistanceBounded() {
        assertThat(GridMath.withinRange(c(0, 0), c(0, 12), 60)).isTrue();
        assertThat(GridMath.withinRange(c(0, 0), c(0, 13), 60)).isFalse();
    }

    @Test
    void cellsWithinReturnsASquareAreaOfTheRightSize() {
        assertThat(GridMath.cellsWithin(c(0, 0), 10)).hasSize(25);
        assertThat(GridMath.cellsWithin(c(0, 0), 5)).hasSize(9);
    }

    @Test
    void cellsWithinIncludesTheCenter() {
        assertThat(GridMath.cellsWithin(c(3, 3), 5)).contains(c(3, 3));
    }

    @Test
    void rejectsBadDimensions() {
        assertThatThrownBy(() -> Grid.open(0, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Grid.builder(5, 5, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void treatsOutOfBoundsAsWallAndInBoundsAsOpenByDefault() {
        Grid g = Grid.open(10, 10);
        assertThat(g.inBounds(c(-1, 0))).isFalse();
        assertThat(g.isWall(c(-1, 0))).isTrue();
        assertThat(g.isWall(c(5, 5))).isFalse();
        assertThat(g.isPassable(c(5, 5))).isTrue();
        assertThat(g.isPassable(c(10, 10))).isFalse();
    }

    @Test
    void setsAndReadsTerrainFlagsWithoutDisturbingTheOtherFlag() {
        Grid g = Grid.builder(10, 10).wall(c(3, 3)).difficult(c(4, 4)).build();
        assertThat(g.isWall(c(3, 3))).isTrue();
        assertThat(g.isPassable(c(3, 3))).isFalse();
        assertThat(g.isDifficult(c(4, 4))).isTrue();
        assertThat(g.isWall(c(4, 4))).isFalse();
    }

    @Test
    void laterTerrainCallsMergeWithEarlierOnes() {
        Grid g = Grid.builder(5, 5).wall(c(1, 1)).difficult(c(1, 1)).build();
        assertThat(g.at(c(1, 1))).isEqualTo(new TerrainCell(true, true));
    }

    @Test
    void fillRectClipsToBoundsAndDoesNotThrowAtTheEdge() {
        Grid g = Grid.builder(5, 5).fillRect(3, 3, 10, 10, true, null).build();
        assertThat(g.isWall(c(4, 4))).isTrue();
        assertThat(g.isWall(c(3, 3))).isTrue();
        assertThat(g.isWall(c(2, 2))).isFalse();
    }

    @Test
    void setTerrainOutOfBoundsThrows() {
        assertThatThrownBy(() -> Grid.builder(5, 5).wall(c(9, 9))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void usesItsCellSizeForDistance() {
        Grid g = Grid.builder(10, 10, 10).build();
        assertThat(g.distanceFt(c(0, 0), c(3, 0))).isEqualTo(30);
    }

    @Test
    void gridIsUnaffectedByLaterBuilderChanges() {
        Grid.Builder b = Grid.builder(5, 5);
        Grid g = b.build();
        b.wall(c(2, 2));
        assertThat(g.isWall(c(2, 2))).isFalse();
    }
}
