package org.omnomnom.dnd.sim.domain.opt.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.adapter.out.content.SqliteContentSource;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.content.build.Fillers;
import org.omnomnom.dnd.sim.domain.content.build.Role;
import org.omnomnom.dnd.sim.domain.content.monster.MonsterCatalog;
import org.omnomnom.dnd.sim.domain.scenario.PartyTemplate;
import org.omnomnom.dnd.sim.domain.scenario.Scenario;
import org.omnomnom.dnd.sim.domain.scenario.ScenarioLibrary;
import org.omnomnom.dnd.sim.testsupport.RecipeFactory;
import org.omnomnom.dnd.sim.testsupport.TestJson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Compares the solo and party evaluators with the original TypeScript ones ({@code tools/reference/gen-eval.mts}) for
 * 24 caster heroes (6 classes at levels 3, 5, 11 and 17) over the real scenario library and reference parties. The
 * heroes are built here from recipes through the Java content layer, while upstream builds them from a genome and its
 * catalog, so a match also shows the two hero definitions agree. Every metric, confidence interval and run count must
 * match, so any change to a scenario, a party template, the CRN seeding or the metric extraction shows up.
 */
class EvaluatorParityTest {

    private static final double[] STANDARD_ARRAY = {15, 14, 13, 12, 10, 8};
    private static final double EPS = 1e-9;

    static SqliteContentSource source;
    static MonsterCatalog monsters;
    static RecipeFactory recipes;

    @BeforeAll
    static void open() {
        source = SqliteContentSource.open();
        monsters = MonsterCatalog.load(source);
        recipes = new RecipeFactory(source);
    }

    @AfterAll
    static void close() {
        source.close();
    }

    /** The hero as a combatant recipe: the genome's standard-array permutation expanded to ability scores. */
    private static Function<String, Combatant> heroFactory(JsonNode h) {
        ObjectNode caster = TestJson.MAPPER.createObjectNode();
        caster.put("class", h.get("class").asString());
        caster.put("subclass", h.get("subclass").asString());
        caster.put("level", h.get("level").asInt());
        var abilities = caster.putArray("abilities");
        for (JsonNode idx : h.get("assignment")) {
            abilities.add((int) STANDARD_ARRAY[idx.asInt()]);
        }
        h.get("caster").properties().forEach(e -> caster.set(e.getKey(), e.getValue()));
        return id -> {
            ObjectNode c = TestJson.MAPPER.createObjectNode();
            c.put("id", id);
            c.put("side", "party");
            c.putArray("position").add(0).add(0);
            c.set("caster", caster);
            return recipes.make(c);
        };
    }

    private static void assertInterval(Interval got, JsonNode want, String what) {
        assertThat(got.point()).as(what + ".point").isCloseTo(want.get("point").asDouble(), within(EPS));
        assertThat(got.lo()).as(what + ".lo").isCloseTo(want.get("lo").asDouble(), within(EPS));
        assertThat(got.hi()).as(what + ".hi").isCloseTo(want.get("hi").asDouble(), within(EPS));
        assertThat(got.halfWidth()).as(what + ".halfWidth").isCloseTo(want.get("halfWidth").asDouble(), within(EPS));
    }

    private static void close(double got, JsonNode want, String what) {
        assertThat(got).as(what).isCloseTo(want.asDouble(), within(EPS));
    }

    @Test
    void soloAndPartyEvaluatorsMatchTheTypeScriptOnes() throws Exception {
        JsonNode input = TestJson.load("/reference/eval-input.json");
        JsonNode expected = TestJson.load("/reference/eval-expected.json").get("results");
        int soloRuns = input.get("soloRuns").asInt();
        int partyRuns = input.get("partyRuns").asInt();
        assertThat(expected.size()).isEqualTo(input.get("heroes").size()).isEqualTo(24);

        Map<Integer, List<Scenario>> scenarios = new HashMap<>();
        Map<Integer, PartyEvaluator.Harness> harnesses = new HashMap<>();
        for (int i = 0; i < expected.size(); i++) {
            JsonNode h = input.get("heroes").get(i);
            JsonNode want = expected.get(i);
            String name = h.get("name").asString();
            assertThat(want.get("name").asString()).isEqualTo(name);
            int level = h.get("level").asInt();
            Function<String, Combatant> hero = heroFactory(h);

            List<Scenario> lib = scenarios.computeIfAbsent(level, l -> ScenarioLibrary.load(monsters, source.xpByChallengeRating(), l));
            EvalResult solo = SoloEvaluator.evaluate(hero, lib, soloRuns);
            JsonNode s = want.get("solo");
            String p = name + " solo ";
            assertThat(solo.runs()).as(p + "runs").isEqualTo(s.get("runs").asInt());
            close(solo.fitness(), s.get("fitness"), p + "fitness");
            close(solo.winRate(), s.get("winRate"), p + "winRate");
            close(solo.avgHpFracOnWin(), s.get("avgHpFracOnWin"), p + "avgHpFracOnWin");
            close(solo.avgHpFracRetained(), s.get("avgHpFracRetained"), p + "avgHpFracRetained");
            close(solo.avgDamageDealt(), s.get("avgDamageDealt"), p + "avgDamageDealt");
            close(solo.avgRounds(), s.get("avgRounds"), p + "avgRounds");
            close(solo.avgRoundsEffective(), s.get("avgRoundsEffective"), p + "avgRoundsEffective");
            close(solo.avgActionsDenied(), s.get("avgActionsDenied"), p + "avgActionsDenied");
            assertInterval(solo.ci().winRate(), s.get("ci").get("winRate"), p + "ci.winRate");
            assertInterval(solo.ci().damage(), s.get("ci").get("damage"), p + "ci.damage");
            assertInterval(solo.ci().hpRetained(), s.get("ci").get("hpRetained"), p + "ci.hpRetained");

            PartyEvaluator.Harness harness = harnesses.computeIfAbsent(level,
                    l -> PartyEvaluator.Harness.load(Fillers.load(source, l), monsters, l));
            PartyEvalResult party = PartyEvaluator.evaluate(hero, harness, Role.fromCode(h.get("role").asString()), PartyTemplate.ALL, partyRuns);
            JsonNode t = want.get("party");
            p = name + " party ";
            assertThat(party.runs()).as(p + "runs").isEqualTo(t.get("runs").asInt());
            close(party.winRate(), t.get("winRate"), p + "winRate");
            close(party.avgHeroDamage(), t.get("avgHeroDamage"), p + "avgHeroDamage");
            close(party.avgHeroHealing(), t.get("avgHeroHealing"), p + "avgHeroHealing");
            close(party.avgHeroBuffAssists(), t.get("avgHeroBuffAssists"), p + "avgHeroBuffAssists");
            close(party.avgHeroSupport(), t.get("avgHeroSupport"), p + "avgHeroSupport");
            close(party.avgHeroActionsDenied(), t.get("avgHeroActionsDenied"), p + "avgHeroActionsDenied");
            close(party.avgHeroHpFracRetained(), t.get("avgHeroHpFracRetained"), p + "avgHeroHpFracRetained");
            close(party.avgRoundsEffective(), t.get("avgRoundsEffective"), p + "avgRoundsEffective");
            close(party.avgAlliesAliveFrac(), t.get("avgAlliesAliveFrac"), p + "avgAlliesAliveFrac");
            assertInterval(party.winRateCi(), t.get("ci").get("winRate"), p + "ci.winRate");
        }
    }
}
