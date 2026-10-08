package org.omnomnom.dnd.sim.domain.combat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.domain.core.AbilityScores;
import org.omnomnom.dnd.sim.domain.core.DamageType;
import org.omnomnom.dnd.sim.domain.dice.Dice;
import org.omnomnom.dnd.sim.domain.grid.Cell;
import org.omnomnom.dnd.sim.domain.grid.Grid;
import org.omnomnom.dnd.sim.domain.rng.LabeledRandom;

/** Port of {@code sim/src/combat/encounter.spec.ts}. */
class EncounterTest {

    static final AttackProfile SWORD =
            AttackProfile.builder("sword", AttackKind.MELEE, 8, Dice.of(1, 8, 4), DamageType.SLASHING).reachFt(5).build();

    static CombatantSpec.Builder heroSpec(String id, Cell pos) {
        return CombatantSpec.builder(id, "Hero", Side.PARTY, 5, AbilityScores.of(18, 14, 14, 10, 10, 10), 17, 45)
                .attacks(List.of(SWORD))
                .position(pos);
    }

    static Combatant hero() {
        return new Combatant(heroSpec("hero", new Cell(0, 0)).build());
    }

    static CombatantSpec.Builder dummySpec(String id, Cell pos) {
        return CombatantSpec.builder(id, "Dummy", Side.ENEMY, 1, AbilityScores.allTens(), 10, 7).position(pos);
    }

    static Combatant dummy() {
        return new Combatant(dummySpec("dummy", new Cell(1, 0)).build());
    }

    /** Attacks the first reachable enemy; otherwise ends the turn. */
    static final TurnPolicy ATTACK_IN_REACH = api -> {
        List<Combatant> enemies = api.enemies();
        if (enemies.isEmpty()) {
            return;
        }
        if (!api.self().attacks().isEmpty()) {
            api.attack(enemies.get(0), api.self().attacks().get(0));
        }
    };

    static Encounter.Builder enc(Grid grid, List<Combatant> cs, long seed) {
        return Encounter.builder(grid, cs, new LabeledRandom(seed));
    }

    // ---- rollInitiative ------------------------------------------------------------------------

    @Test
    void initiativeIsDeterministicForTheSameSeed() {
        var mk = (java.util.function.Supplier<List<String>>) () ->
                enc(Grid.open(10, 10), List.of(hero(), dummy()), 5).build().rollInitiative().stream().map(Combatant::id).toList();
        assertThat(mk.get()).isEqualTo(mk.get());
    }

    @Test
    void ordersByDescendingInitiativeTotal() {
        EventLog log = new EventLog();
        Encounter e = enc(Grid.open(10, 10),
                        List.of(hero(), dummy(), new Combatant(dummySpec("d2", new Cell(2, 0)).build())), 9)
                .sink(log)
                .build();
        e.rollInitiative();
        var init = log.of(CombatEvent.Initiative.class).get(0);
        for (int i = 1; i < init.order().size(); i++) {
            assertThat(init.order().get(i - 1).total()).isGreaterThanOrEqualTo(init.order().get(i).total());
        }
    }

    @Test
    void includesEveryCombatantExactlyOnce() {
        Encounter e = enc(Grid.open(10, 10), List.of(hero(), dummy()), 1).build();
        assertThat(e.rollInitiative().stream().map(Combatant::id).sorted().toList()).containsExactly("dummy", "hero");
    }

    @Test
    void initiativeTiesBreakByDexThenPartyBeforeEnemyThenId() {
        // Equal Dex modifiers, so a tie on the total falls through to side, then id. The enemy's id sorts first
        // alphabetically, which proves the side rule (not the id rule) decides party-versus-enemy ties.
        int partyVsEnemyTies = 0;
        int sameSideTies = 0;
        for (long seed = 0; seed < 2000; seed++) {
            Combatant party = new Combatant(heroSpec("z-hero", new Cell(0, 0)).build());
            Combatant enemy = new Combatant(CombatantSpec.builder("a-foe", "Foe", Side.ENEMY, 5,
                            AbilityScores.of(18, 14, 14, 10, 10, 10), 10, 7).position(new Cell(1, 0)).build());
            Combatant sibling = new Combatant(heroSpec("a-hero", new Cell(0, 1)).build());
            EventLog log = new EventLog();
            enc(Grid.open(10, 10), List.of(party, enemy, sibling), seed).sink(log).build().rollInitiative();
            var order = log.of(CombatEvent.Initiative.class).get(0).order();
            for (int i = 1; i < order.size(); i++) {
                var prev = order.get(i - 1);
                var cur = order.get(i);
                assertThat(prev.total()).isGreaterThanOrEqualTo(cur.total());
                if (prev.total() != cur.total()) {
                    continue;
                }
                boolean prevParty = prev.id().endsWith("hero");
                boolean curParty = cur.id().endsWith("hero");
                if (prevParty != curParty) {
                    partyVsEnemyTies++;
                    assertThat(prevParty).as("seed %d: party acts before enemy on a tie", seed).isTrue();
                } else {
                    sameSideTies++;
                    assertThat(prev.id()).as("seed %d: id order on a same-side tie", seed).isLessThan(cur.id());
                }
            }
        }
        assertThat(partyVsEnemyTies).isPositive();
        assertThat(sameSideTies).isPositive();
    }

    // ---- a basic fight -------------------------------------------------------------------------

    @Test
    void theAttackerDefeatsAPassiveDummyAndThePartyWins() {
        Combatant d = dummy();
        EventLog log = new EventLog();
        Encounter e = enc(Grid.open(10, 10), List.of(hero(), d), 42)
                .policyFor(c -> c.side() == Side.PARTY ? ATTACK_IN_REACH : TurnPolicy.IDLE)
                .sink(log)
                .build();
        Encounter.RunResult result = e.run();
        assertThat(result.winner()).isEqualTo(Side.PARTY);
        assertThat(d.isConscious()).isFalse();
        assertThat(result.rounds()).isGreaterThanOrEqualTo(1);
        assertThat(log.of(CombatEvent.Attack.class)).anyMatch(CombatEvent.Attack::hit);
    }

    @Test
    void terminatesWhenBothSidesFight() {
        Combatant foe = new Combatant(CombatantSpec.builder("foe", "Foe", Side.ENEMY, 3,
                        AbilityScores.of(16, 12, 14, 8, 10, 8), 14, 30)
                .attacks(List.of(AttackProfile.builder("claw", AttackKind.MELEE, 5, Dice.of(1, 6, 3), DamageType.SLASHING)
                        .reachFt(5).build()))
                .position(new Cell(1, 0))
                .build());
        Encounter e = enc(Grid.open(10, 10), List.of(hero(), foe), 7).policyFor(c -> ATTACK_IN_REACH).build();
        Encounter.RunResult result = e.run(100);
        assertThat(result.rounds()).isLessThanOrEqualTo(100);
        assertThat(e.isOver()).isTrue();
    }

    @Test
    void isReproducibleUnderCommonRandomNumbers() {
        var play = (java.util.function.Supplier<Object[]>) () -> {
            EventLog log = new EventLog();
            Encounter e = enc(Grid.open(10, 10), List.of(hero(), dummy()), 123)
                    .policyFor(c -> c.side() == Side.PARTY ? ATTACK_IN_REACH : TurnPolicy.IDLE)
                    .sink(log)
                    .build();
            Encounter.RunResult r = e.run();
            return new Object[] {r, log.events()};
        };
        assertThat(play.get()).isEqualTo(play.get());
    }

    // ---- movement and opportunity attacks ------------------------------------------------------

    @Test
    void provokesAnOpportunityAttackWhenLeavingReach() {
        Combatant mover = new Combatant(heroSpec("mover", new Cell(5, 5)).attacks(List.of()).build());
        Combatant guard = new Combatant(CombatantSpec.builder("guard", "Guard", Side.ENEMY, 3,
                        AbilityScores.of(16, 12, 14, 8, 10, 8), 12, 30)
                .attacks(List.of(SWORD))
                .position(new Cell(6, 5))
                .build());
        TurnPolicy flee = api -> {
            if (api.self().id().equals("mover")) {
                api.moveTo(new Cell(0, 5));
            }
        };
        EventLog log = new EventLog();
        Encounter e = enc(Grid.open(12, 12), List.of(mover, guard), 3).policyFor(c -> flee).sink(log).build();
        e.rollInitiative();
        e.runRound();
        assertThat(log.of(CombatEvent.Opportunity.class)).isNotEmpty();
    }

    @Test
    void aMoveCostsDoubleThroughDifficultTerrainAndCanBeRefused() {
        Grid g = Grid.builder(12, 12).fillRect(1, 0, 1, 1, null, true).build();
        Combatant h = new Combatant(heroSpec("hero", new Cell(0, 0)).attacks(List.of()).build());
        boolean[] moved = new boolean[1];
        TurnPolicy policy = api -> {
            // Speed 30 -> 6 normal steps, but moving 6 straight with one difficult cell costs 35 > 30.
            if (api.self().id().equals("hero")) {
                moved[0] = api.moveTo(new Cell(6, 0));
            }
        };
        Encounter e = enc(g, List.of(h, new Combatant(dummySpec("dummy", new Cell(11, 11)).build())), 1)
                .policyFor(c -> c.id().equals("hero") ? policy : TurnPolicy.IDLE)
                .build();
        e.rollInitiative();
        e.runRound();
        assertThat(moved[0]).isFalse();
        assertThat(h.position()).isEqualTo(new Cell(0, 0));
    }

    // ---- death saves ---------------------------------------------------------------------------

    @Test
    void aDyingCombatantRollsADeathSaveAtTheStartOfItsTurn() {
        Combatant downed = new Combatant(heroSpec("downed", new Cell(0, 0)).attacks(List.of()).build());
        downed.takeDamage(45);
        Combatant ally = new Combatant(heroSpec("ally", new Cell(0, 1)).build());
        EventLog log = new EventLog();
        Encounter e = enc(Grid.open(10, 10), List.of(downed, ally, dummy()), 11)
                .policyFor(c -> TurnPolicy.IDLE)
                .sink(log)
                .build();
        e.rollInitiative();
        e.runRound();
        assertThat(log.of(CombatEvent.DeathSave.class)).anyMatch(x -> x.id().equals("downed"));
    }

    // ---- linePath ------------------------------------------------------------------------------

    @Test
    void linePathStepsOneCellAtATimeToTheDestination() {
        assertThat(Encounter.linePath(new Cell(0, 0), new Cell(3, 2)))
                .containsExactly(new Cell(0, 0), new Cell(1, 1), new Cell(2, 2), new Cell(3, 2));
    }

    @Test
    void linePathLengthIsChebyshevDistancePlusOne() {
        assertThat(Encounter.linePath(new Cell(0, 0), new Cell(0, 0))).hasSize(1);
        assertThat(Encounter.linePath(new Cell(0, 0), new Cell(5, 0))).hasSize(6);
    }

    // ---- API guard rails (not covered upstream) -----------------------------------------------

    @Test
    void anEncounterWithNoOpSinkStillProducesAnOutcome() {
        Encounter e = enc(Grid.open(10, 10), List.of(hero(), dummy()), 42)
                .policyFor(c -> c.side() == Side.PARTY ? ATTACK_IN_REACH : TurnPolicy.IDLE)
                .build();
        assertThat(e.run().winner()).isEqualTo(Side.PARTY);
    }

    @Test
    void illegalActionsReturnEmptyAndSpendNothing() {
        Combatant h = hero();
        Combatant far = new Combatant(dummySpec("far", new Cell(9, 9)).build());
        OptionalInt[] result = new OptionalInt[1];
        TurnPolicy policy = api -> {
            if (api.self() == h) {
                result[0] = api.attack(far, SWORD); // out of reach
            }
        };
        Encounter e = enc(Grid.open(10, 10), List.of(h, far), 1)
                .policyFor(c -> c == h ? policy : TurnPolicy.IDLE)
                .build();
        e.rollInitiative();
        e.runRound();
        assertThat(result[0]).isEmpty();
    }
}
