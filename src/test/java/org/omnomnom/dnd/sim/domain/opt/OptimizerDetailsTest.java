package org.omnomnom.dnd.sim.domain.opt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.adapter.out.content.SqliteContentSource;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.content.FightingStyle;
import org.omnomnom.dnd.sim.domain.content.MonsterCatalog;
import org.omnomnom.dnd.sim.domain.rng.LabeledRandom;

/** Pins found by mutation testing the optimizer: constants, catalog data, role weights, run keys and edge cases. */
class OptimizerDetailsTest {

    static MartialCatalog catalog3;
    static MartialCatalog catalog5;
    static final Genome BARBARIAN = new Genome(BuildClass.BARBARIAN, List.of(0, 2, 1, 3, 4, 5), "Greataxe", null, false, true, null);

    @BeforeAll
    static void open() {
        try (SqliteContentSource source = SqliteContentSource.open()) {
            MonsterCatalog monsters = MonsterCatalog.load(source);
            catalog3 = MartialCatalog.load(source, monsters, 3);
            catalog5 = MartialCatalog.load(source, monsters, 5);
        }
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    // ---- run keys: the first four values were produced by the TypeScript runKey on the same config -----------------

    @Test
    void runKeysMatchTheTypeScriptForEqualConfigText() {
        assertThat(Reports.runKey(Reports.canonicalJson(map()))).isEqualTo("27565fad");
        assertThat(Reports.runKey(Reports.canonicalJson(map("a", 1, "b", 2)))).isEqualTo("1507871b");
        assertThat(Reports.runKey(Reports.canonicalJson(map("level", 3, "xs", List.of(1, 2, 3))))).isEqualTo("b2803d71");
        assertThat(Reports.runKey(Reports.canonicalJson(map("n", map("p", "q\"r", "f", 0.3), "t", true, "z", null)))).isEqualTo("f5deb2b5");
        assertThat(Reports.runKey(Reports.canonicalJson(map("m", 0.3, "k", List.of(map("a", 1), map("b", List.of()))))))
                .isEqualTo("ef135274");
    }

    @Test
    void canonicalJsonPrintsWholeDoublesLikeJavaScript() {
        assertThat(Reports.canonicalJson(map("x", 3.0, "y", 2.5, "s", "a\\b\nc"))).isEqualTo("{\"x\":3,\"y\":2.5,\"s\":\"a\\\\b\\nc\"}");
    }

    // ---- defaults ----------------------------------------------------------------------------------------------

    @Test
    void optionDefaultsMatchUpstream() {
        Nsga2.Options d = Nsga2.Options.defaults();
        assertThat(d.populationSize()).isEqualTo(24);
        assertThat(d.generations()).isEqualTo(12);
        assertThat(d.mutationRate()).isEqualTo(0.3);
        assertThat(d.evalRunsPerScenario()).isEqualTo(16);
        assertThat(d.classes()).isNull();
        assertThat(Campaign.DEFAULT_DAYS).isEqualTo(16);
        assertThat(Campaign.DEFAULT_SHORT_REST_HEAL_FRACTION).isEqualTo(0.5);
        assertThat(Campaign.evaluateAdventuringDay(Anchor.BENCHMARK, catalog3).days()).isEqualTo(16);
    }

    @Test
    void theDefaultLeaderboardHoldsTwentyBuilds() {
        Nsga2.Result big = Nsga2.run(catalog3, new LabeledRandom(5), Nsga2.Options.defaults().withPopulation(40).withGenerations(1).withEvalRuns(1));
        Reports.Report report = Reports.build(big, Map.of(), 3);
        assertThat(big.population().stream().map(i -> Genomes.key(i.genome())).distinct().count()).isGreaterThan(21);
        assertThat(report.leaderboard()).hasSize(20);
    }

    // ---- campaign edge cases -----------------------------------------------------------------------------------

    @Test
    void zeroDaysIsAnEmptyResult() {
        Campaign.Result r = Campaign.evaluateAdventuringDay(Anchor.BENCHMARK, catalog3, 0, 0.5);
        assertThat(r.dayWinRate()).isZero();
        assertThat(r.avgEncountersCleared()).isZero();
        assertThat(r.days()).isZero();
        assertThat(r.dayWinRateCi().hi()).isEqualTo(1);
    }

    @Test
    void aSingleDayReportsItsOwnOutcome() {
        // A rapier barbarian clears the level-5 day under the fixed day-0 seed (found by search), so one day is a 100% rate.
        Genome rapierBarbarian = new Genome(BuildClass.BARBARIAN, List.of(4, 1, 2, 3, 0, 5), "Rapier", null, false, false, null);
        Campaign.Result one = Campaign.evaluateAdventuringDay(rapierBarbarian, catalog5, 1, 0.5);
        assertThat(one.days()).isEqualTo(1);
        assertThat(one.dayWinRate()).isEqualTo(1.0);
        assertThat(one.avgEncountersCleared()).isEqualTo(catalog5.scenarios().size());
        // The benchmark does not clear the level-3 day-0 seed.
        assertThat(Campaign.evaluateAdventuringDay(Anchor.BENCHMARK, catalog3, 1, 0.5).dayWinRate()).isZero();
    }

    // ---- the benchmark and the catalog -------------------------------------------------------------------------

    @Test
    void theBenchmarkIsTheSwordAndBoardChampion() {
        assertThat(Anchor.BENCHMARK).isEqualTo(new Genome(BuildClass.FIGHTER, List.of(0, 3, 1, 4, 5, 2), "Longsword", "Chain Mail", true,
                false, FightingStyle.DEFENSE));
        Combatant hero = Genomes.build(Anchor.BENCHMARK, catalog3, "hero");
        assertThat(hero.ac()).isEqualTo(16 + 2 + 1); // chain mail, shield, Defense
    }

    @Test
    void subclassesAreTheSrdLevelThreeChoices() {
        Map<BuildClass, String> expected = Map.ofEntries(
                Map.entry(BuildClass.FIGHTER, "champion"),
                Map.entry(BuildClass.BARBARIAN, "path-of-the-berserker"),
                Map.entry(BuildClass.ROGUE, "thief"),
                Map.entry(BuildClass.RANGER, "hunter"),
                Map.entry(BuildClass.PALADIN, "oath-of-devotion"),
                Map.entry(BuildClass.MONK, "warrior-of-the-open-hand"),
                Map.entry(BuildClass.WIZARD, "evoker"),
                Map.entry(BuildClass.CLERIC, "life-domain"),
                Map.entry(BuildClass.BARD, "college-of-lore"),
                Map.entry(BuildClass.SORCERER, "draconic-sorcery"),
                Map.entry(BuildClass.WARLOCK, "fiend-patron"),
                Map.entry(BuildClass.DRUID, "circle-of-the-land"));
        expected.forEach((c, sub) -> assertThat(catalog3.subclassFor(c)).as(c.code()).isEqualTo(sub));
    }

    @Test
    void casterPackagesCarryTheirClassFeatures() {
        assertThat(catalog3.casterPackageFor(BuildClass.WARLOCK).shortRestSlots()).isTrue();
        for (BuildClass c : BuildClass.CASTER) {
            if (c != BuildClass.WARLOCK) {
                assertThat(catalog3.casterPackageFor(c).shortRestSlots()).as(c.code()).isFalse();
            }
        }
        assertThat(catalog3.casterPackageFor(BuildClass.SORCERER).extraHp()).isEqualTo(3);
        assertThat(catalog3.casterPackageFor(BuildClass.SORCERER).resources()).extracting(r -> r.id() + ":" + r.max()).containsExactly("sorcery:3");
        assertThat(catalog3.casterPackageFor(BuildClass.DRUID).resources()).extracting(r -> r.id() + ":" + r.max()).containsExactly("wild-shape:2");
        assertThat(catalog3.casterPackageFor(BuildClass.WIZARD).cantrips()).extracting(s -> s.id()).containsExactly("fire-bolt", "ray-of-frost");
        assertThat(catalog3.casterPackageFor(BuildClass.CLERIC).shield()).isTrue();
        assertThat(catalog3.gishSpellcastingFor(BuildClass.PALADIN)).isNotNull();
        assertThat(catalog3.gishSpellcastingFor(BuildClass.FIGHTER)).isNull();
    }

    @Test
    void aWarlocksPactSlotsComeBackOnAShortRest() {
        Combatant warlock = Genomes.build(new Genome(BuildClass.WARLOCK, List.of(0, 1, 2, 3, 4, 5), "Dagger", null, false, false, null), catalog3, "hero");
        int slots = warlock.slotCount(2);
        assertThat(slots).isPositive();
        warlock.spendSlot(2);
        assertThat(warlock.slotCount(2)).isEqualTo(slots - 1);
        warlock.shortRest();
        assertThat(warlock.slotCount(2)).isEqualTo(slots);
    }

    // ---- role presets ------------------------------------------------------------------------------------------

    @Test
    void roleWeightsArePinned() {
        assertThat(RolePresets.weights("sustained-dps")).isEqualTo(Map.of("reliability", 1.0, "offense", 3.0, "survival", 1.0, "efficiency", 2.0));
        assertThat(RolePresets.weights("burst")).isEqualTo(Map.of("reliability", 1.0, "offense", 3.0, "survival", 0.0, "efficiency", 3.0));
        assertThat(RolePresets.weights("tank")).isEqualTo(Map.of("reliability", 2.0, "offense", 1.0, "survival", 3.0, "efficiency", 1.0));
        assertThat(RolePresets.weights("generalist")).isEqualTo(Map.of("reliability", 1.0, "offense", 1.0, "survival", 1.0, "efficiency", 1.0));
        assertThat(RolePresets.weights("controller")).isEqualTo(
                Map.of("reliability", 1.0, "offense", 1.0, "survival", 1.0, "control", 3.0, "efficiency", 1.0));
        assertThat(RolePresets.weights("healer")).isEqualTo(
                Map.of("reliability", 2.0, "offense", 0.0, "survival", 1.0, "support", 3.0, "efficiency", 1.0));
        assertThat(RolePresets.weights("buffer")).isEqualTo(
                Map.of("reliability", 1.0, "offense", 1.0, "survival", 1.0, "control", 1.0, "support", 3.0, "efficiency", 1.0));
    }
}
