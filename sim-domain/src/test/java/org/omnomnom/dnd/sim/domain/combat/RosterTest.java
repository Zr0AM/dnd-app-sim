package org.omnomnom.dnd.sim.domain.combat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.domain.core.AbilityScores;
import org.omnomnom.dnd.sim.domain.core.Side;
import org.omnomnom.dnd.sim.domain.grid.Cell;

class RosterTest {

    private static Combatant who(String id, Side side, int x) {
        return new Combatant(CombatantSpec.builder(id, id, side, 1, AbilityScores.of(10, 10, 10, 10, 10, 10), 12, 10)
                .position(new Cell(x, 0))
                .build());
    }

    private final Combatant hero = who("hero", Side.PARTY, 0);
    private final Combatant friend = who("friend", Side.PARTY, 1);
    private final Combatant orc = who("orc", Side.ENEMY, 2);
    private final Combatant goblin = who("goblin", Side.ENEMY, 6);
    private final List<Combatant> all = new ArrayList<>(List.of(hero, friend, orc, goblin));
    private final Roster roster = new Roster(all, 5);

    @Test
    void opponentsAreTheConsciousOtherSideInListOrder() {
        assertThat(roster.consciousOpponents(hero)).containsExactly(orc, goblin);
        assertThat(roster.consciousOpponents(orc)).containsExactly(hero, friend);
        orc.takeDamage(1000);
        assertThat(roster.consciousOpponents(hero)).containsExactly(goblin);
    }

    @Test
    void alliesExcludeTheAskerAndTheUnconscious() {
        assertThat(roster.consciousAllies(hero)).containsExactly(friend);
        friend.takeDamage(1000);
        assertThat(roster.consciousAllies(hero)).isEmpty();
    }

    @Test
    void livingAlliesIncludeADownedButNotDeadAlly() {
        friend.takeDamage(10); // exactly its 10 HP: down at 0 HP, but not dead
        assertThat(friend.isConscious()).isFalse();
        assertThat(friend.isAlive()).isTrue();
        assertThat(roster.livingAllies(hero)).containsExactly(friend);
        assertThat(roster.consciousAllies(hero)).isEmpty();
    }

    @Test
    void anyConsciousTracksTheLiveList() {
        assertThat(roster.anyConscious(Side.ENEMY)).isTrue();
        orc.takeDamage(1000);
        goblin.takeDamage(1000);
        assertThat(roster.anyConscious(Side.ENEMY)).isFalse();
        assertThat(roster.anyConscious(Side.PARTY)).isTrue();
    }

    @Test
    void withinFindsOpponentsInRadiusOfAnOrigin() {
        // orc is 2 cells (10 ft) from the origin, goblin 6 cells (30 ft).
        assertThat(roster.consciousOpponentsWithin(hero, new Cell(0, 0), 10)).containsExactly(orc);
        assertThat(roster.consciousOpponentsWithin(hero, new Cell(0, 0), 30)).containsExactly(orc, goblin);
        assertThat(roster.consciousOpponentsWithin(hero, new Cell(6, 0), 5)).containsExactly(goblin);
    }

    @Test
    void rosterSeesCombatantsAddedLater() {
        Combatant late = who("late", Side.ENEMY, 3);
        all.add(late);
        assertThat(roster.consciousOpponents(hero)).contains(late);
    }
}
