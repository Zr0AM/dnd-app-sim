package org.omnomnom.dnd.sim.domain.combat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.domain.combat.event.CombatEvent;
import org.omnomnom.dnd.sim.domain.combat.event.EventLog;
import org.omnomnom.dnd.sim.domain.core.AbilityScores;
import org.omnomnom.dnd.sim.domain.core.DamageType;
import org.omnomnom.dnd.sim.domain.core.Side;
import org.omnomnom.dnd.sim.domain.dice.Dice;
import org.omnomnom.dnd.sim.domain.grid.Cell;
import org.omnomnom.dnd.sim.domain.grid.Grid;
import org.omnomnom.dnd.sim.domain.rng.LabeledRandom;

/** Port of {@code sim/src/combat/legendary.spec.ts}. */
class LegendaryActionsTest {

    static final AttackProfile CLAW =
            AttackProfile.builder("Claw", AttackKind.MELEE, 20, Dice.of(2, 6, 4), DamageType.SLASHING).reachFt(5).build();

    static Combatant boss(int legendary) {
        return new Combatant(CombatantSpec.builder("boss", "Boss", Side.ENEMY, 1,
                        AbilityScores.of(20, 10, 18, 10, 12, 14), 18, 300)
                .attacks(List.of(CLAW))
                .legendaryActions(legendary)
                .position(new Cell(5, 5))
                .build());
    }

    static Combatant hero(String id, Cell pos) {
        return new Combatant(CombatantSpec.builder(id, id, Side.PARTY, 11, AbilityScores.of(16, 12, 14, 10, 10, 10), 16, 90)
                .attacks(List.of(AttackProfile.builder("sword", AttackKind.MELEE, 8, Dice.of(1, 8, 3), DamageType.SLASHING)
                        .reachFt(5).build()))
                .position(pos)
                .build());
    }

    @Test
    void aBossAttacksBetweenOtherCreaturesTurnsUpToItsBudgetEachRound() {
        Combatant b = boss(3);
        List<Combatant> all = List.of(hero("h0", new Cell(4, 5)), hero("h1", new Cell(6, 5)), b);
        EventLog log = new EventLog();
        // Heroes stand and swing; the boss's own policy does nothing, so every legendary event comes from the
        // between-turns legendary action path.
        Encounter e = Encounter.builder(Grid.open(12, 12), all, new LabeledRandom(5))
                .policyFor(c -> c.side() == Side.PARTY
                        ? api -> {
                            var t = api.enemies();
                            if (!t.isEmpty()) {
                                api.attack(t.get(0), api.self().attacks().get(0));
                            }
                        }
                        : TurnPolicy.IDLE)
                .sink(log)
                .build();
        e.rollInitiative();
        e.runRound();
        var firstRound = log.of(CombatEvent.Legendary.class);
        assertThat(firstRound).isNotEmpty();
        assertThat(firstRound).hasSizeLessThanOrEqualTo(3);
        assertThat(firstRound).allMatch(x -> x.source().equals("boss"));

        int afterFirst = firstRound.size();
        e.runRound();
        assertThat(log.of(CombatEvent.Legendary.class)).hasSizeGreaterThan(afterFirst);
    }

    @Test
    void anOrdinaryMonsterTakesNoLegendaryActions() {
        List<Combatant> all = List.of(hero("h0", new Cell(4, 5)), hero("h1", new Cell(6, 5)), boss(0));
        EventLog log = new EventLog();
        Encounter e = Encounter.builder(Grid.open(12, 12), all, new LabeledRandom(5)).sink(log).build();
        e.rollInitiative();
        e.runRound();
        assertThat(log.of(CombatEvent.Legendary.class)).isEmpty();
    }

    @Test
    void refreshesItsLegendaryBudgetAtTheStartOfItsOwnTurn() {
        Combatant b = boss(3);
        b.spendLegendary();
        b.spendLegendary();
        assertThat(b.legendaryRemaining()).isEqualTo(1);
        b.refreshLegendary();
        assertThat(b.legendaryRemaining()).isEqualTo(3);
    }
}
