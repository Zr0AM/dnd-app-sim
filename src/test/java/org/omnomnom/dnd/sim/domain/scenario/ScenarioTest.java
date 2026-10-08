package org.omnomnom.dnd.sim.domain.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.adapter.out.content.SqliteContentSource;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.content.Fillers;
import org.omnomnom.dnd.sim.domain.content.MonsterCatalog;
import org.omnomnom.dnd.sim.domain.content.Role;
import org.omnomnom.dnd.sim.domain.combat.CombatantSpec;
import org.omnomnom.dnd.sim.domain.combat.Side;
import org.omnomnom.dnd.sim.domain.core.AbilityScores;
import org.omnomnom.dnd.sim.domain.grid.Cell;

/** Ports of {@code library.spec.ts} and {@code party.spec.ts}, plus the layouts and XP totals the TypeScript only comments. */
class ScenarioTest {

    static SqliteContentSource source;
    static MonsterCatalog monsters;
    static Map<Integer, List<Scenario>> library;

    @BeforeAll
    static void open() {
        source = SqliteContentSource.open();
        monsters = MonsterCatalog.load(source);
        library = Map.of(
                3, ScenarioLibrary.load(monsters, source.xpByChallengeRating(), 3),
                5, ScenarioLibrary.load(monsters, source.xpByChallengeRating(), 5),
                11, ScenarioLibrary.load(monsters, source.xpByChallengeRating(), 11),
                17, ScenarioLibrary.load(monsters, source.xpByChallengeRating(), 17));
    }

    @AfterAll
    static void close() {
        source.close();
    }

    // ---- maps ----------------------------------------------------------------------------------

    @Test
    void openFieldIsPassableWithDistinctStartZones() {
        MapLayout m = Maps.OPEN_FIELD;
        assertThat(m.grid().isPassable(m.heroStart())).isTrue();
        m.enemyStarts().forEach(e -> assertThat(m.grid().isPassable(e)).isTrue());
        assertThat(m.heroStart()).isNotEqualTo(m.enemyStarts().get(0));
        assertThat(m.grid().width()).isEqualTo(16);
        assertThat(m.grid().height()).isEqualTo(12);
    }

    @Test
    void corridorHasAWallWithAOneCellDoorway() {
        MapLayout m = Maps.CORRIDOR_CHOKEPOINT;
        for (int y = 0; y < 9; y++) {
            assertThat(m.grid().isWall(Cell.of(8, y))).as("wall at y=%d", y).isEqualTo(y != 4);
        }
        assertThat(m.grid().isPassable(m.heroStart())).isTrue();
        m.enemyStarts().forEach(e -> assertThat(m.grid().isPassable(e)).isTrue());
    }

    @Test
    void everyRegisteredMapIsLookedUpById() {
        for (MapLayout m : Maps.all()) {
            assertThat(Maps.byId(m.id())).isSameAs(m);
        }
    }

    // ---- scenario library ----------------------------------------------------------------------

    @Test
    void levelThreeHasVariedShapesAndTheDocumentedXp() {
        List<Scenario> l3 = library.get(3);
        assertThat(l3.stream().map(Scenario::shape).collect(Collectors.toSet())).contains("single", "swarm", "pack", "mixed");
        assertThat(l3.stream().collect(Collectors.toMap(Scenario::id, Scenario::xp))).containsExactlyInAnyOrderEntriesOf(Map.of(
                "l3-pair-goblins", 100,
                "l3-single-bugbear", 200,
                "l3-swarm-minions", 125,
                "l3-mixed-hobgoblin", 200,
                "l3-choke-gnolls", 200));
    }

    @Test
    void everyScenarioHasEnemiesOnPassableCells() {
        for (List<Scenario> set : library.values()) {
            for (Scenario s : set) {
                assertThat(s.xp()).isPositive();
                List<Combatant> enemies = s.spawnEnemies();
                assertThat(enemies).isNotEmpty();
                for (Combatant e : enemies) {
                    assertThat(s.grid().isPassable(e.position())).isTrue();
                    assertThat(e.side()).isEqualTo(Side.ENEMY);
                }
                assertThat(enemies.stream().map(Combatant::id)).doesNotHaveDuplicates();
            }
        }
        library.get(3).forEach(s -> assertThat(s.xp()).isLessThanOrEqualTo(300));
    }

    @Test
    void spawnEnemiesProducesFreshFullHpCombatantsEachCall() {
        Scenario s = library.get(3).get(0);
        List<Combatant> a = s.spawnEnemies();
        a.get(0).takeDamage(1000);
        List<Combatant> b = s.spawnEnemies();
        assertThat(b.get(0).isConscious()).isTrue();
        assertThat(b.get(0).hp()).isEqualTo(b.get(0).maxHp());
    }

    @Test
    void selectsLevelAppropriateSpecsByCheckpoint() {
        assertThat(library.get(3)).allMatch(s -> s.id().startsWith("l3-"));
        assertThat(library.get(5)).allMatch(s -> s.id().startsWith("l3-")); // below 11 reuses the level-3 set
        assertThat(library.get(11)).allMatch(s -> s.id().startsWith("l11-"));
        assertThat(library.get(17)).allMatch(s -> s.id().startsWith("l17-"));
        assertThat(library.get(11)).extracting(Scenario::level).containsOnly(11);
        for (int level : new int[] {11, 17}) {
            assertThat(library.get(level).stream().map(Scenario::shape).collect(Collectors.toSet())).contains("single");
        }
    }

    @Test
    void highChallengeOpponentsKeepTheirMultiattack() {
        Combatant troll = library.get(11).stream().filter(s -> s.id().equals("l11-single-troll")).findFirst().orElseThrow()
                .spawnEnemies().get(0);
        assertThat(troll.name()).isEqualTo("Troll");
        assertThat(troll.attacks()).isNotEmpty();
        assertThat(troll.extraAttacks()).isEqualTo(2); // Rend x3
    }

    // ---- party harness -------------------------------------------------------------------------

    private static Combatant hero() {
        return new Combatant(CombatantSpec.builder("hero", "Hero", Side.PARTY, 5, AbilityScores.of(16, 12, 14, 10, 10, 10), 18, 45)
                .position(Cell.of(0, 0)).build());
    }

    private static List<Cell> cells(PartyTemplate t) {
        return java.util.stream.IntStream.range(0, t.roles().size()).mapToObj(i -> Cell.of(0, i)).toList();
    }

    @Test
    void assemblePutsTheHeroInItsRoleSlotAndFillsTheRest() {
        Map<Role, Fillers.Filler> fillers = Fillers.load(source, 5);
        List<Combatant> party = PartyScenarios.assemble(fillers, PartyTemplate.R4, hero(), Role.TANK, cells(PartyTemplate.R4));
        assertThat(party).hasSize(4);
        assertThat(party.stream().filter(c -> c.id().equals("hero"))).hasSize(1);
        assertThat(party.get(0).id()).isEqualTo("hero");
        assertThat(party.get(1).id()).isEqualTo("ally-burst-1");
    }

    @Test
    void assemblesTheFullSixWithUniqueIds() {
        Map<Role, Fillers.Filler> fillers = Fillers.load(source, 5);
        List<Combatant> party = PartyScenarios.assemble(fillers, PartyTemplate.R6, hero(), Role.BUFFER, cells(PartyTemplate.R6));
        assertThat(party).hasSize(6);
        assertThat(party.stream().map(Combatant::id).collect(Collectors.toSet())).hasSize(6);
        assertThat(party.get(PartyTemplate.R6.roles().indexOf(Role.BUFFER)).id()).isEqualTo("hero");
        for (int i = 0; i < 6; i++) {
            assertThat(party.get(i).position()).isEqualTo(Cell.of(0, i));
        }
    }

    @Test
    void aHeroWithoutAMatchingRoleTakesTheFlexSlot() {
        Map<Role, Fillers.Filler> fillers = Fillers.load(source, 5);
        // R3 has no burst role; the hero goes to the flex (controller) slot.
        List<Combatant> party = PartyScenarios.assemble(fillers, PartyTemplate.R3, hero(), Role.BURST, cells(PartyTemplate.R3));
        assertThat(party.stream().filter(c -> c.id().equals("hero"))).hasSize(1);
        assertThat(party.get(PartyTemplate.R3.roles().indexOf(PartyTemplate.R3.flex())).id()).isEqualTo("hero");
    }

    @Test
    void templatesAreWeightedTwoTwoOne() {
        assertThat(PartyTemplate.ALL.stream().map(PartyTemplate::weight)).containsExactly(2, 2, 1);
        assertThat(PartyTemplate.ALL.stream().map(t -> t.roles().size())).containsExactly(6, 4, 3);
    }

    @Test
    void partyScenariosScaleEnemyCountsWithPartySize() {
        int small = PartyScenarios.load(monsters, 3, 5).stream().filter(s -> s.id().equals("horde")).findFirst().orElseThrow()
                .spawnEnemies().size();
        int large = PartyScenarios.load(monsters, 4, 5).stream().filter(s -> s.id().equals("horde")).findFirst().orElseThrow()
                .spawnEnemies().size();
        assertThat(small).isEqualTo(15);
        assertThat(large).isEqualTo(20);
        // The six-member horde would need 30 cells and exactly fills the map's enemy area.
        assertThat(PartyScenarios.load(monsters, 6, 5).get(0).spawnEnemies()).hasSize(30);
    }

    @Test
    void anOversizedPartyEncounterIsCappedAtTheMapsEnemyCells() {
        // Seven members would call for 35 goblins; the map has 30 cells.
        assertThat(PartyScenarios.load(monsters, 7, 5).get(0).spawnEnemies()).hasSize(30);
    }

    @Test
    void partyScenariosSpawnFreshEnemiesOnPassableCells() {
        PartyScenario s = PartyScenarios.load(monsters, 4, 5).get(0);
        for (Combatant e : s.spawnEnemies()) {
            assertThat(s.grid().isPassable(e.position())).isTrue();
            assertThat(e.isConscious()).isTrue();
        }
    }

    @Test
    void highLevelPartyEncountersHaveALegendaryDragonBossWithFixedAdds() {
        Set<String> l5 = PartyScenarios.load(monsters, 4, 5).stream().map(PartyScenario::id).collect(Collectors.toSet());
        assertThat(l5).containsExactlyInAnyOrder("horde", "mixed");
        assertThat(PartyScenarios.load(monsters, 4, 11).stream().map(PartyScenario::id)).contains("boss-young-dragon");
        List<PartyScenario> l17 = PartyScenarios.load(monsters, 4, 17);
        assertThat(l17.stream().map(PartyScenario::id)).contains("boss-adult-dragon");

        Combatant dragon = l17.stream().filter(s -> s.id().equals("boss-adult-dragon")).findFirst().orElseThrow()
                .spawnEnemies().stream().filter(m -> m.name().equals("Adult Red Dragon")).findFirst().orElseThrow();
        assertThat(dragon.legendaryMax()).isEqualTo(3);
        assertThat(dragon.extraAttacks()).isEqualTo(2); // Rend x3 via Multiattack

        // Fixed boss adds do not balloon with party size; a scaling pack still does.
        int boss3 = PartyScenarios.load(monsters, 3, 17).stream().filter(s -> s.id().equals("boss-adult-dragon")).findFirst().orElseThrow()
                .spawnEnemies().size();
        int boss6 = PartyScenarios.load(monsters, 6, 17).stream().filter(s -> s.id().equals("boss-adult-dragon")).findFirst().orElseThrow()
                .spawnEnemies().size();
        assertThat(boss3).isEqualTo(boss6).isEqualTo(3); // dragon + 2 fixed adds
        int pack3 = PartyScenarios.load(monsters, 3, 11).stream().filter(s -> s.id().equals("troll-pack")).findFirst().orElseThrow()
                .spawnEnemies().size();
        int pack6 = PartyScenarios.load(monsters, 6, 11).stream().filter(s -> s.id().equals("troll-pack")).findFirst().orElseThrow()
                .spawnEnemies().size();
        assertThat(pack6).isGreaterThan(pack3);
    }
}
