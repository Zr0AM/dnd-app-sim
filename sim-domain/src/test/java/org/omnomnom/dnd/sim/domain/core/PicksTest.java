package org.omnomnom.dnd.sim.domain.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

class PicksTest {

    private record Item(String name, double score) {}

    @Test
    void picksTheHighestScore() {
        List<Item> items = List.of(new Item("a", 1), new Item("b", 3), new Item("c", 2));
        assertThat(Picks.firstMax(items, Item::score)).map(Item::name).contains("b");
    }

    @Test
    void theFirstOfTiedCandidatesWins() {
        List<Item> items = List.of(new Item("a", 2), new Item("b", 3), new Item("c", 3), new Item("d", 1));
        assertThat(Picks.firstMax(items, Item::score)).map(Item::name).contains("b");
    }

    @Test
    void firstMinPicksTheLowestAndTheFirstOfTies() {
        List<Item> items = List.of(new Item("a", 3), new Item("b", 1), new Item("c", 1), new Item("d", 2));
        assertThat(Picks.firstMin(items, Item::score)).map(Item::name).contains("b");
        assertThat(Picks.firstMin(List.<Item>of(), Item::score)).isEmpty();
    }

    @Test
    void anEmptyInputPicksNothing() {
        assertThat(Picks.firstMax(List.<Item>of(), Item::score)).isEmpty();
    }

    @Test
    void aNegativeOnlyInputStillPicksItsBest() {
        List<Item> items = List.of(new Item("a", -5), new Item("b", -2), new Item("c", -9));
        assertThat(Picks.firstMax(items, Item::score)).map(Item::name).contains("b");
    }

    @Test
    void aNaNScoreNeverReplacesTheCurrentPick() {
        List<Item> items = List.of(new Item("a", 1), new Item("nan", Double.NaN), new Item("b", 0.5));
        assertThat(Picks.firstMax(items, Item::score)).map(Item::name).contains("a");
    }

    @Test
    void agreesWithStreamMaxOnTies() {
        List<Item> items = List.of(new Item("a", 1), new Item("b", 4), new Item("c", 4), new Item("d", 4), new Item("e", 2));
        Item viaStream = items.stream().max(Comparator.comparingDouble(Item::score)).orElseThrow();
        assertThat(Picks.firstMax(items, Item::score)).contains(viaStream);
    }
}
