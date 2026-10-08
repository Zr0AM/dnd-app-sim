package org.omnomnom.dnd.sim.domain.opt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.adapter.out.content.SqliteContentSource;
import org.omnomnom.dnd.sim.domain.content.FightingStyle;
import org.omnomnom.dnd.sim.domain.content.MonsterCatalog;
import org.omnomnom.dnd.sim.domain.rng.LabeledRandom;
import org.omnomnom.dnd.sim.testsupport.TestJson;
import tools.jackson.databind.JsonNode;

/**
 * Compares the optimizer with the original TypeScript one ({@code tools/reference/gen-opt.mts}): genome operators draw
 * for draw, every class at every checkpoint level through {@code buildFromGenome} and the solo evaluator, the NSGA-II
 * sort and crowding cores on tied synthetic points, full NSGA-II runs with their reports, rescoring and campaign
 * annotation, the adventuring day and the anchor. Random streams, selection order and sort stability all have to line
 * up for the populations to match.
 */
class OptimizerParityTest {

    private static final double EPS = 1e-9;

    static SqliteContentSource source;
    static MonsterCatalog monsters;
    static JsonNode expected;
    static final Map<Integer, MartialCatalog> catalogs = new HashMap<>();

    @BeforeAll
    static void open() throws Exception {
        source = SqliteContentSource.open();
        monsters = MonsterCatalog.load(source);
        expected = TestJson.load("/reference/opt-expected.json");
    }

    @AfterAll
    static void close() {
        source.close();
    }

    static MartialCatalog catalog(int level) {
        return catalogs.computeIfAbsent(level, l -> MartialCatalog.load(source, monsters, l));
    }

    static Genome genome(JsonNode n) {
        List<Integer> assignment = new ArrayList<>();
        n.get("abilityAssignment").forEach(a -> assignment.add(a.asInt()));
        return new Genome(
                BuildClass.fromCode(n.get("classSlug").asString()),
                assignment,
                n.get("weaponName").asString(),
                n.hasNonNull("armorName") ? n.get("armorName").asString() : null,
                n.get("shield").asBoolean(),
                n.get("twoHanded").asBoolean(),
                n.hasNonNull("fightingStyle") ? FightingStyle.valueOf(n.get("fightingStyle").asString().toUpperCase().replace('-', '_')) : null);
    }

    static List<BuildClass> classes(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        List<BuildClass> out = new ArrayList<>();
        n.forEach(c -> out.add(BuildClass.fromCode(c.asString())));
        return out;
    }

    static double[] vec(JsonNode n) {
        double[] v = new double[n.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = n.get(i).isString() ? Double.POSITIVE_INFINITY : n.get(i).asDouble();
        }
        return v;
    }

    private static void assertVec(double[] got, JsonNode want, String what) {
        assertThat(got).as(what).hasSize(want.size());
        for (int i = 0; i < got.length; i++) {
            if (want.get(i).isString()) {
                assertThat(got[i]).as(what + "[" + i + "]").isEqualTo(Double.POSITIVE_INFINITY);
            } else {
                assertThat(got[i]).as(what + "[" + i + "]").isCloseTo(want.get(i).asDouble(), within(EPS));
            }
        }
    }

    @Test
    void genomeOperatorsDrawForDraw() {
        MartialCatalog cat = catalog(3);
        LabeledRandom random = new LabeledRandom(20261010);
        JsonNode chains = expected.get("operators");
        assertThat(chains.size()).isEqualTo(90);
        for (int i = 0; i < chains.size(); i++) {
            JsonNode c = chains.get(i);
            List<BuildClass> classes = classes(c.get("classes"));
            Genome g = Genomes.randomGenome(cat, random, "r:" + i, classes);
            assertThat(g).as("random " + i).isEqualTo(genome(c.get("g")));
            Genome m = Genomes.mutate(g, cat, random, "m:" + i, classes);
            assertThat(m).as("mutate " + i).isEqualTo(genome(c.get("m")));
            Genome m2 = Genomes.mutate(m, cat, random, "m2:" + i, classes);
            assertThat(m2).as("mutate again " + i).isEqualTo(genome(c.get("m2")));
            Genome g2 = Genomes.randomGenome(cat, random, "r2:" + i, null);
            assertThat(g2).as("random 2 " + i).isEqualTo(genome(c.get("g2")));
            Genome x = Genomes.crossover(g, g2, cat, random, "x:" + i);
            assertThat(x).as("crossover " + i).isEqualTo(genome(c.get("x")));
            assertThat(List.of(Genomes.key(g), Genomes.key(m), Genomes.key(x))).as("keys " + i)
                    .containsExactly(c.get("keys").get(0).asString(), c.get("keys").get(1).asString(), c.get("keys").get(2).asString());
        }
    }

    static void assertEval(EvalResult got, JsonNode s, String p) {
        assertThat(got.runs()).as(p + "runs").isEqualTo(s.get("runs").asInt());
        assertThat(got.fitness()).as(p + "fitness").isCloseTo(s.get("fitness").asDouble(), within(EPS));
        assertThat(got.winRate()).as(p + "winRate").isCloseTo(s.get("winRate").asDouble(), within(EPS));
        assertThat(got.avgHpFracOnWin()).as(p + "avgHpFracOnWin").isCloseTo(s.get("avgHpFracOnWin").asDouble(), within(EPS));
        assertThat(got.avgHpFracRetained()).as(p + "avgHpFracRetained").isCloseTo(s.get("avgHpFracRetained").asDouble(), within(EPS));
        assertThat(got.avgDamageDealt()).as(p + "avgDamageDealt").isCloseTo(s.get("avgDamageDealt").asDouble(), within(EPS));
        assertThat(got.avgRounds()).as(p + "avgRounds").isCloseTo(s.get("avgRounds").asDouble(), within(EPS));
        assertThat(got.avgRoundsEffective()).as(p + "avgRoundsEffective").isCloseTo(s.get("avgRoundsEffective").asDouble(), within(EPS));
        assertThat(got.avgActionsDenied()).as(p + "avgActionsDenied").isCloseTo(s.get("avgActionsDenied").asDouble(), within(EPS));
    }

    @Test
    void everyClassAtEveryCheckpointMatchesThroughTheSoloEvaluator() {
        JsonNode builds = expected.get("builds");
        assertThat(builds.size()).isEqualTo(4 * 12 * 4);
        for (JsonNode b : builds) {
            MartialCatalog cat = catalog(b.get("level").asInt());
            Genome g = genome(b.get("genome"));
            assertThat(Genomes.key(g)).isEqualTo(b.get("key").asString());
            EvalResult r = SoloEvaluator.evaluate(id -> Genomes.build(g, cat, id), cat.scenarios(), 2);
            assertEval(r, b.get("result"), "L" + b.get("level").asInt() + " " + b.get("key").asString() + " ");
        }
    }

    @Test
    void sortAndCrowdingCoresMatchOnTiedPoints() {
        for (JsonNode c : expected.get("sortCases")) {
            List<double[]> points = new ArrayList<>();
            c.get("points").forEach(p -> points.add(vec(p)));
            List<List<Integer>> fronts = Nsga2.fastNonDominatedSort(points);
            JsonNode wantFronts = c.get("fronts");
            assertThat(fronts).hasSize(wantFronts.size());
            for (int f = 0; f < fronts.size(); f++) {
                List<Integer> want = new ArrayList<>();
                wantFronts.get(f).forEach(i -> want.add(i.asInt()));
                assertThat(fronts.get(f)).as("front " + f).containsExactlyElementsOf(want);
                assertVec(Nsga2.crowdingDistances(points, fronts.get(f)), c.get("crowding").get(f), "crowding of front " + f);
            }
        }
    }

    private static void assertIndividuals(List<Nsga2.Individual> got, JsonNode want, String what) {
        assertThat(got).as(what + " size").hasSize(want.size());
        for (int i = 0; i < got.size(); i++) {
            Nsga2.Individual ind = got.get(i);
            JsonNode w = want.get(i);
            String p = what + "[" + i + "] ";
            assertThat(Genomes.key(ind.genome())).as(p + "key").isEqualTo(w.get("key").asString());
            assertThat(ind.genome()).as(p + "genome").isEqualTo(genome(w.get("genome")));
            assertVec(ind.objectives(), w.get("objectives"), p + "objectives");
            assertThat(ind.rank()).as(p + "rank").isEqualTo(w.get("rank").asInt());
            if (w.get("crowding").isString()) {
                assertThat(ind.crowding()).as(p + "crowding").isEqualTo(Double.POSITIVE_INFINITY);
            } else {
                assertThat(ind.crowding()).as(p + "crowding").isCloseTo(w.get("crowding").asDouble(), within(EPS));
            }
            assertThat(ind.result().fitness()).as(p + "fitness").isCloseTo(w.get("fitness").asDouble(), within(EPS));
        }
    }

    private static void assertEntries(List<Reports.Entry> got, JsonNode want, int level, String what) {
        assertThat(got).as(what + " size").hasSize(want.size());
        for (int i = 0; i < got.size(); i++) {
            Reports.Entry e = got.get(i);
            JsonNode w = want.get(i);
            String p = what + "[" + i + "] ";
            assertThat(e.key()).as(p + "key").isEqualTo(w.get("key").asString());
            assertThat(e.rank()).as(p + "rank").isEqualTo(w.get("rank").asInt());
            assertThat(e.genome()).as(p + "genome").isEqualTo(genome(w.get("genome")));
            // Upstream prints a placeholder "L?" for the level; this port prints the level.
            assertThat(e.description()).as(p + "description").isEqualTo(w.get("description").asString().replaceFirst("^L\\?", "L" + level));
            assertThat(e.metrics().winRate()).as(p + "winRate").isCloseTo(w.get("metrics").get("winRate").asDouble(), within(EPS));
            assertThat(e.metrics().avgDamageDealt()).as(p + "damage").isCloseTo(w.get("metrics").get("avgDamageDealt").asDouble(), within(EPS));
            assertThat(e.metrics().avgHpFracRetained()).as(p + "hp").isCloseTo(w.get("metrics").get("avgHpFracRetained").asDouble(), within(EPS));
            assertThat(e.metrics().avgRounds()).as(p + "rounds").isCloseTo(w.get("metrics").get("avgRounds").asDouble(), within(EPS));
            assertThat(e.metrics().runs()).as(p + "runs").isEqualTo(w.get("metrics").get("runs").asInt());
            w.get("objectives").properties().forEach(o ->
                    assertThat(e.objectives().get(o.getKey())).as(p + "objective " + o.getKey()).isCloseTo(o.getValue().asDouble(), within(EPS)));
            assertThat(e.weightedScore()).as(p + "score").isCloseTo(w.get("weightedScore").asDouble(), within(EPS));
            if (w.has("campaignDayWinRate")) {
                assertThat(e.campaignDayWinRate()).as(p + "campaignDayWinRate").isCloseTo(w.get("campaignDayWinRate").asDouble(), within(EPS));
            } else {
                assertThat(e.campaignDayWinRate()).as(p + "campaignDayWinRate").isNull();
            }
        }
    }

    private static void assertReport(Reports.Report got, JsonNode want, int level, String what) {
        assertThat(got.version()).isEqualTo(want.get("version").asInt());
        assertThat(got.runKey()).as(what + " runKey").isEqualTo(want.get("runKey").asString());
        assertThat(got.objectiveNames()).containsExactlyElementsOf(
                want.get("objectiveNames").valueStream().map(JsonNode::asString).toList());
        want.get("objectiveBounds").properties().forEach(b -> {
            assertThat(got.objectiveBounds().get(b.getKey())[0]).as(what + " min " + b.getKey()).isCloseTo(b.getValue().get(0).asDouble(), within(EPS));
            assertThat(got.objectiveBounds().get(b.getKey())[1]).as(what + " max " + b.getKey()).isCloseTo(b.getValue().get(1).asDouble(), within(EPS));
        });
        want.get("weights").properties().forEach(w ->
                assertThat(got.weights().get(w.getKey())).as(what + " weight " + w.getKey()).isEqualTo(w.getValue().asDouble()));
        assertEntries(got.paretoFront(), want.get("paretoFront"), level, what + " front");
        assertEntries(got.leaderboard(), want.get("leaderboard"), level, what + " leaderboard");
    }

    @Test
    void nsga2RunsReportsRescoringAndCampaignAnnotationMatch() {
        for (JsonNode run : expected.get("runs")) {
            JsonNode spec = run.get("spec");
            int level = spec.get("level").asInt();
            MartialCatalog cat = catalog(level);
            String what = "L" + level + " seed " + spec.get("seed").asInt();
            Nsga2.Options opts = Nsga2.Options.defaults()
                    .withPopulation(spec.get("populationSize").asInt())
                    .withGenerations(spec.get("generations").asInt())
                    .withEvalRuns(spec.get("evalRuns").asInt())
                    .withClasses(classes(spec.get("classes")));
            Nsga2.Result result = Nsga2.run(cat, new LabeledRandom(spec.get("seed").asLong()), opts);
            assertIndividuals(result.front(), run.get("front"), what + " front");
            assertIndividuals(result.population(), run.get("population"), what + " population");

            Map<String, Object> config = new LinkedHashMap<>();
            run.get("config").properties().forEach(e -> config.put(e.getKey(), e.getValue().asInt()));
            Reports.Report report = Reports.build(result, config, level);
            assertReport(report, run.get("report"), level, what + " report");
            assertReport(Reports.rescore(report, RolePresets.weights("tank")), run.get("rescored").get("tank"), level, what + " tank");
            assertReport(Reports.rescore(report, RolePresets.weights("healer")), run.get("rescored").get("healer"), level, what + " healer");
            assertReport(Campaign.annotate(report, cat, 3, Campaign.DEFAULT_SHORT_REST_HEAL_FRACTION), run.get("annotated"), level,
                    what + " annotated");
        }
    }

    @Test
    void adventuringDayMatches() {
        for (JsonNode c : expected.get("campaignCases")) {
            MartialCatalog cat = catalog(c.get("level").asInt());
            Campaign.Result r = Campaign.evaluateAdventuringDay(genome(c.get("genome")), cat, 4, Campaign.DEFAULT_SHORT_REST_HEAL_FRACTION);
            JsonNode w = c.get("result");
            String p = "L" + c.get("level").asInt() + " " + c.get("genome").get("classSlug").asString() + " ";
            assertThat(r.days()).as(p + "days").isEqualTo(w.get("days").asInt());
            assertThat(r.encountersPerDay()).as(p + "perDay").isEqualTo(w.get("encountersPerDay").asInt());
            assertThat(r.dayWinRate()).as(p + "dayWinRate").isCloseTo(w.get("dayWinRate").asDouble(), within(EPS));
            assertThat(r.avgEncountersCleared()).as(p + "cleared").isCloseTo(w.get("avgEncountersCleared").asDouble(), within(EPS));
            assertThat(r.dayWinRateCi().lo()).as(p + "ci.lo").isCloseTo(w.get("ci").get("dayWinRate").get("lo").asDouble(), within(EPS));
            assertThat(r.dayWinRateCi().hi()).as(p + "ci.hi").isCloseTo(w.get("ci").get("dayWinRate").get("hi").asDouble(), within(EPS));
        }
    }

    @Test
    void anchorAndNormalizationMatch() {
        for (JsonNode a : expected.get("anchors")) {
            MartialCatalog cat = catalog(a.get("level").asInt());
            double[] anchor = Anchor.compute(cat, 2);
            assertVec(anchor, a.get("anchor"), "anchor");
            for (int i = 0; i < a.get("vectors").size(); i++) {
                assertVec(Anchor.normalize(vec(a.get("vectors").get(i)), anchor), a.get("normalized").get(i), "normalized " + i);
            }
        }
    }
}
