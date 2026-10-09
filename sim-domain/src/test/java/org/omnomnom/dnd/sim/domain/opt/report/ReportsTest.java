package org.omnomnom.dnd.sim.domain.opt.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.adapter.out.content.SqliteContentSource;
import org.omnomnom.dnd.sim.domain.content.build.FightingStyle;
import org.omnomnom.dnd.sim.domain.content.monster.MonsterCatalog;
import org.omnomnom.dnd.sim.domain.opt.campaign.Campaign;
import org.omnomnom.dnd.sim.domain.opt.evaluation.Anchor;
import org.omnomnom.dnd.sim.domain.opt.evaluation.EvalResult;
import org.omnomnom.dnd.sim.domain.opt.evaluation.Objectives;
import org.omnomnom.dnd.sim.domain.opt.evaluation.SoloEvaluator;
import org.omnomnom.dnd.sim.domain.opt.genome.BuildClass;
import org.omnomnom.dnd.sim.domain.opt.genome.Genome;
import org.omnomnom.dnd.sim.domain.opt.genome.Genomes;
import org.omnomnom.dnd.sim.domain.opt.genome.MartialCatalog;
import org.omnomnom.dnd.sim.domain.opt.search.Nsga2;
import org.omnomnom.dnd.sim.domain.rng.LabeledRandom;

/** Ports of {@code reports.spec.ts}, {@code roles.spec.ts} and {@code campaign.spec.ts}. */
class ReportsTest {

    static MartialCatalog catalog3;
    static MartialCatalog catalog5;
    static Nsga2.Result result;

    @BeforeAll
    static void open() {
        try (SqliteContentSource source = SqliteContentSource.open()) {
            MonsterCatalog monsters = MonsterCatalog.load(source);
            catalog3 = MartialCatalog.load(source, monsters, 3);
            catalog5 = MartialCatalog.load(source, monsters, 5);
        }
        result = Nsga2.run(catalog3, new LabeledRandom(42),
                Nsga2.Options.defaults().withPopulation(16).withGenerations(6).withEvalRuns(8));
    }

    private static Map<String, Double> w(Object... pairs) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], ((Number) pairs[i + 1]).doubleValue());
        }
        return m;
    }

    // ---- weightedScore ---------------------------------------------------------------------------

    private static final Map<String, double[]> BOUNDS = new LinkedHashMap<>();

    static {
        BOUNDS.put("reliability", new double[] {0, 1});
        BOUNDS.put("offense", new double[] {0, 100});
        BOUNDS.put("survival", new double[] {0, 1});
        BOUNDS.put("efficiency", new double[] {-10, 0});
        BOUNDS.put("control", new double[] {0, 10});
        BOUNDS.put("support", new double[] {0, 10});
    }

    @Test
    void aDominantPointScoresOneAndTheWorstZero() {
        assertThat(Reports.weightedScore(new double[] {1, 100, 1, 0, 10, 10}, BOUNDS, Reports.equalWeights())).isCloseTo(1, within(1e-10));
        assertThat(Reports.weightedScore(new double[] {0, 0, 0, -10, 0, 0}, BOUNDS, Reports.equalWeights())).isCloseTo(0, within(1e-10));
    }

    @Test
    void weightsShiftTheRanking() {
        double[] a = {0.5, 100, 0, -5, 0, 0};
        double[] b = {0.5, 0, 1, -5, 0, 0};
        Map<String, Double> offense = w("reliability", 0, "offense", 1, "survival", 0, "efficiency", 0);
        Map<String, Double> survival = w("reliability", 0, "offense", 0, "survival", 1, "efficiency", 0);
        assertThat(Reports.weightedScore(a, BOUNDS, offense)).isGreaterThan(Reports.weightedScore(b, BOUNDS, offense));
        assertThat(Reports.weightedScore(b, BOUNDS, survival)).isGreaterThan(Reports.weightedScore(a, BOUNDS, survival));
    }

    @Test
    void anObjectiveWithoutBoundsOrWeightIsSkippedAndNoWeightScoresZero() {
        Map<String, double[]> partial = new LinkedHashMap<>(BOUNDS);
        partial.remove("offense");
        assertThat(Reports.weightedScore(new double[] {1, 100, 1, 0, 10, 10}, partial, w("offense", 1))).isZero();
        assertThat(Reports.weightedScore(new double[] {1, 100, 1, 0, 10, 10}, BOUNDS, Map.of())).isZero();
        // A degenerate range (min == max) scores the middle.
        assertThat(Reports.weightedScore(new double[] {7}, Map.of("reliability", new double[] {3, 3}), w("reliability", 1))).isEqualTo(0.5);
    }

    // ---- describe --------------------------------------------------------------------------------

    @Test
    void describeSummarizesClassGearAndTopAbilities() {
        String text = Reports.describe(new Genome(BuildClass.BARBARIAN, List.of(0, 1, 2, 3, 4, 5), "Greatsword", null, false, true, null), 3);
        assertThat(text).isEqualTo("L3 barbarian — Greatsword (2H), unarmored — STR 15, DEX 14");
    }

    @Test
    void describeListsShieldArmorAndFightingStyle() {
        String text = Reports.describe(new Genome(BuildClass.FIGHTER, List.of(2, 1, 0, 3, 4, 5), "Longsword", "Chain Mail", true, false,
                FightingStyle.DEFENSE), 11);
        assertThat(text).isEqualTo("L11 fighter — Longsword, shield, Chain Mail, defense — CON 15, DEX 14");
    }

    // ---- buildReport -----------------------------------------------------------------------------

    @Test
    void producesAWellFormedReport() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("level", 3);
        config.put("scenarioId", "l3-goblins");
        Reports.Report report = Reports.build(result, config, 3);
        assertThat(report.version()).isEqualTo(Reports.VERSION);
        assertThat(report.runKey()).matches("[0-9a-f]{8}");
        assertThat(report.objectiveNames()).containsExactly("reliability", "offense", "survival", "efficiency", "control", "support");
        assertThat(report.paretoFront()).isNotEmpty();
        assertThat(report.leaderboard()).isNotEmpty().hasSizeLessThanOrEqualTo(20);
        assertThat(report.paretoFront()).allMatch(e -> e.rank() == 0);
    }

    @Test
    void theLeaderboardIsSortedAndDeduplicated() {
        Reports.Report report = Reports.build(result, Map.of(), 3);
        for (int i = 1; i < report.leaderboard().size(); i++) {
            assertThat(report.leaderboard().get(i - 1).weightedScore()).isGreaterThanOrEqualTo(report.leaderboard().get(i).weightedScore());
        }
        List<String> keys = report.leaderboard().stream().map(Reports.Entry::key).toList();
        assertThat(new HashSet<>(keys)).hasSameSizeAs(keys);
    }

    @Test
    void theLeaderboardSizeIsHonored() {
        assertThat(Reports.build(result, Map.of(), 3, Reports.equalWeights(), 3).leaderboard()).hasSize(3);
    }

    @Test
    void rescoreReweightsWithoutResimulatingAndKeepsTheBuilds() {
        Reports.Report report = Reports.build(result, Map.of(), 3);
        Reports.Report survival = Reports.rescore(report, w("reliability", 0, "offense", 0, "survival", 3, "efficiency", 0));
        assertThat(survival.leaderboard()).hasSameSizeAs(report.leaderboard());
        double top = survival.leaderboard().get(0).objectives().get("survival");
        survival.leaderboard().forEach(e -> assertThat(top).isGreaterThanOrEqualTo(e.objectives().get("survival")));
        assertThat(survival.leaderboard().stream().map(Reports.Entry::key)).containsExactlyInAnyOrderElementsOf(
                report.leaderboard().stream().map(Reports.Entry::key).toList());
        assertThat(survival.runKey()).isEqualTo(report.runKey());
    }

    @Test
    void runKeyIsStableForTheSameConfigAndDiffersOtherwise() {
        assertThat(Reports.runKey("{\"a\":1,\"b\":2}")).isEqualTo(Reports.runKey("{\"a\":1,\"b\":2}"));
        assertThat(Reports.runKey("{\"a\":1}")).isNotEqualTo(Reports.runKey("{\"a\":2}"));
        assertThat(Reports.runKey("{}")).hasSize(8);
    }

    // ---- roles -----------------------------------------------------------------------------------

    @Test
    void rolePresetsWeightTheirOwnAxisHighest() {
        assertThat(RolePresets.names()).contains("tank", "sustained-dps", "burst", "generalist", "controller", "healer", "buffer");
        assertThat(RolePresets.weights("tank").get("survival")).isGreaterThan(RolePresets.weights("tank").get("offense"));
        assertThat(RolePresets.weights("healer").get("support")).isGreaterThan(RolePresets.weights("healer").get("offense"));
        assertThat(RolePresets.weights("buffer").get("support")).isGreaterThan(RolePresets.weights("buffer").get("survival"));
        assertThat(RolePresets.weights("controller").get("control")).isGreaterThan(RolePresets.weights("controller").get("offense"));
        assertThat(RolePresets.PARTY_ONLY).containsExactly("healer", "buffer", "controller");
    }

    @Test
    void rejectsAnUnknownRole() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RolePresets.weights("paladin-smiter"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roleReweightingShiftsTheRankingTowardTheRolesAxis() {
        Reports.Report base = Reports.build(result, Map.of(), 3);
        Reports.Entry tankTop = Reports.rescore(base, RolePresets.weights("tank")).leaderboard().get(0);
        Reports.Entry dpsTop = Reports.rescore(base, RolePresets.weights("sustained-dps")).leaderboard().get(0);
        assertThat(tankTop.objectives().get("survival")).isGreaterThanOrEqualTo(dpsTop.objectives().get("survival"));
        assertThat(dpsTop.objectives().get("offense")).isGreaterThanOrEqualTo(tankTop.objectives().get("offense"));
    }

    // ---- anchor ----------------------------------------------------------------------------------

    @Test
    void theBenchmarkNormalizesToOneOnEveryAxis() {
        double[] anchor = Anchor.compute(catalog3, 12);
        double[] self = Objectives.of(SoloEvaluator.evaluate(id -> Genomes.build(Anchor.BENCHMARK, catalog3, id), catalog3.scenarios(), 12));
        for (double v : Anchor.normalize(self, anchor)) {
            assertThat(v).isCloseTo(1, within(1e-6));
        }
    }

    @Test
    void anAxisAtTheFloorScoresOneOrZero() {
        double[] anchor = {0, 0, 0, -50, 0, 0};
        assertThat(Anchor.normalize(new double[] {0, 1, 0, -50, -1, 0}, anchor)).containsExactly(1, 1, 1, 1, 0, 1);
    }

    @Test
    void floorsAreDefinedForEveryObjective() {
        assertThat(Anchor.OBJECTIVE_FLOORS.keySet()).containsExactlyInAnyOrderElementsOf(Objectives.NAMES);
        assertThat(Anchor.OBJECTIVE_FLOORS.get("efficiency")).isLessThan(0);
        assertThat(Anchor.OBJECTIVE_FLOORS.get("reliability")).isZero();
    }

    // ---- campaign --------------------------------------------------------------------------------

    private static final Genome BARBARIAN = new Genome(BuildClass.BARBARIAN, List.of(0, 2, 1, 3, 4, 5), "Greataxe", null, false, true, null);
    private static final Genome WIZARD = new Genome(BuildClass.WIZARD, List.of(5, 1, 2, 0, 3, 4), "Dagger", null, false, false, null);

    @Test
    void anAdventuringDayResultIsWellFormedAndDeterministic() {
        Campaign.Result r = Campaign.evaluateAdventuringDay(BARBARIAN, catalog5, 8, 0.5);
        assertThat(r.days()).isEqualTo(8);
        assertThat(r.encountersPerDay()).isEqualTo(catalog5.scenarios().size());
        assertThat(r.dayWinRate()).isBetween(0.0, 1.0);
        assertThat(r.avgEncountersCleared()).isLessThanOrEqualTo(r.encountersPerDay());
        Campaign.Result again = Campaign.evaluateAdventuringDay(BARBARIAN, catalog5, 8, 0.5);
        assertThat(again).isEqualTo(r);
    }

    @Test
    void aNovaBuildLastsLessOfTheDayThanASustainedOne() {
        // The wizard dumps its limited slots early and has no at-will staying power; the barbarian lasts.
        EvalResult barbOne = SoloEvaluator.evaluate(id -> Genomes.build(BARBARIAN, catalog5, id), catalog5.scenarios(), 16);
        EvalResult wizOne = SoloEvaluator.evaluate(id -> Genomes.build(WIZARD, catalog5, id), catalog5.scenarios(), 16);
        Campaign.Result barbDay = Campaign.evaluateAdventuringDay(BARBARIAN, catalog5, 16, 0.5);
        Campaign.Result wizDay = Campaign.evaluateAdventuringDay(WIZARD, catalog5, 16, 0.5);
        assertThat(wizOne.winRate()).isGreaterThan(0.4);
        assertThat(barbOne.winRate()).isGreaterThan(0.4);
        assertThat(barbDay.avgEncountersCleared()).isGreaterThan(wizDay.avgEncountersCleared());
        assertThat(barbDay.dayWinRate()).isGreaterThan(wizDay.dayWinRate());
    }

    @Test
    void annotationAddsCampaignViabilityAndLeavesTheOriginalAlone() {
        Nsga2.Result run = Nsga2.run(catalog5, new LabeledRandom(42),
                Nsga2.Options.defaults().withPopulation(12).withGenerations(4).withEvalRuns(6));
        Reports.Report report = Reports.build(run, Map.of("level", 5), 5);
        assertThat(report.leaderboard().get(0).campaignDayWinRate()).isNull();
        Reports.Report annotated = Campaign.annotate(report, catalog5, 6, 0.5);
        for (Reports.Entry e : java.util.stream.Stream.concat(annotated.paretoFront().stream(), annotated.leaderboard().stream()).toList()) {
            assertThat(e.campaignDayWinRate()).isBetween(0.0, 1.0);
        }
        assertThat(report.leaderboard().get(0).campaignDayWinRate()).isNull();
        // A build in both lists is simulated once and gets the same rate in both.
        Map<String, Double> rates = new LinkedHashMap<>();
        annotated.paretoFront().forEach(e -> rates.put(e.key(), e.campaignDayWinRate()));
        annotated.leaderboard().stream().filter(e -> rates.containsKey(e.key()))
                .forEach(e -> assertThat(e.campaignDayWinRate()).isEqualTo(rates.get(e.key())));
    }
}
