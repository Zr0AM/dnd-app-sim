package org.omnomnom.dnd.sim.domain.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.domain.grid.Cell;

/** The deployment cells are part of the engine's behavior (they decide who reaches whom), so they are pinned exactly. */
class MapsTest {

    @Test
    void openFieldLayout() {
        MapLayout m = Maps.OPEN_FIELD;
        assertThat(m.id()).isEqualTo("open-field");
        assertThat(m.grid().width()).isEqualTo(16);
        assertThat(m.grid().height()).isEqualTo(12);
        assertThat(m.heroStart()).isEqualTo(Cell.of(1, 6));
        assertThat(m.enemyStarts()).containsExactly(
                Cell.of(14, 6), Cell.of(14, 4), Cell.of(14, 8), Cell.of(13, 5), Cell.of(13, 7), Cell.of(12, 6));
    }

    @Test
    void corridorLayout() {
        MapLayout m = Maps.CORRIDOR_CHOKEPOINT;
        assertThat(m.id()).isEqualTo("corridor-chokepoint");
        assertThat(m.grid().width()).isEqualTo(16);
        assertThat(m.grid().height()).isEqualTo(9);
        assertThat(m.heroStart()).isEqualTo(Cell.of(6, 4));
        assertThat(m.enemyStarts()).containsExactly(
                Cell.of(12, 4), Cell.of(13, 3), Cell.of(13, 5), Cell.of(14, 4), Cell.of(12, 3), Cell.of(12, 5));
        assertThat(IntStream.range(0, 9).filter(y -> m.grid().isWall(Cell.of(8, y))).boxed().toList())
                .containsExactly(0, 1, 2, 3, 5, 6, 7, 8);
    }

    @Test
    void partyMapLayout() {
        // 22 x 14, six party cells on the left, 30 enemy cells (x 18-20, y 2-11) on the right.
        var monsters = org.omnomnom.dnd.sim.adapter.out.content.SqliteContentSource.open();
        try {
            var catalog = org.omnomnom.dnd.sim.domain.content.MonsterCatalog.load(monsters);
            PartyScenario horde = PartyScenarios.load(catalog, 6, 5).get(0);
            assertThat(horde.grid().width()).isEqualTo(22);
            assertThat(horde.grid().height()).isEqualTo(14);
            assertThat(horde.partyCells()).containsExactly(
                    Cell.of(1, 5), Cell.of(1, 7), Cell.of(1, 9), Cell.of(2, 6), Cell.of(2, 8), Cell.of(2, 4));
            List<Cell> expected = IntStream.rangeClosed(18, 20).boxed()
                    .flatMap(x -> IntStream.rangeClosed(2, 11).mapToObj(y -> Cell.of(x, y))).toList();
            assertThat(horde.enemyCells()).containsExactlyElementsOf(expected).hasSize(30);
        } finally {
            monsters.close();
        }
    }
}
