package org.omnomnom.dnd.sim.domain.combat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs the shared scenarios through the Java engine and compares the full event log and final combatant states with
 * what the original TypeScript engine produced ({@code tools/reference/gen-encounters.mts}). The RNG is bit-exact
 * and every random draw is addressed by label, so the logs match event for event, which is a much stronger check than
 * the statistical equivalence the migration requires.
 */
class EncounterParityTest {

    static JsonNode scenarios;
    static JsonNode expected;

    @BeforeAll
    static void load() throws Exception {
        scenarios = ScenarioRunner.load("/reference/scenarios.json");
        expected = ScenarioRunner.load("/reference/encounters.json").get("results");
    }

    static Stream<String> scenarioNames() throws Exception {
        JsonNode s = ScenarioRunner.load("/reference/scenarios.json").get("scenarios");
        return Stream.iterate(0, i -> i < s.size(), i -> i + 1).map(i -> s.get(i).get("name").asString());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarioNames")
    void matchesTheTypeScriptEngineEventForEvent(String name) {
        JsonNode scenario = find(scenarios.get("scenarios"), name);
        JsonNode want = find(expected, name);
        ScenarioRunner.Outcome got = new ScenarioRunner(scenarios.get("spells")).run(scenario);

        JsonNode wantEvents = want.get("events");
        List<ObjectNode> gotEvents = got.events();
        int n = Math.min(wantEvents.size(), gotEvents.size());
        for (int i = 0; i < n; i++) {
            if (!wantEvents.get(i).equals(gotEvents.get(i))) {
                fail("%s: first divergence at event %d%n  previous: %s%n  expected: %s%n  actual:   %s",
                        name, i, i > 0 ? wantEvents.get(i - 1) : "-", wantEvents.get(i), gotEvents.get(i));
            }
        }
        assertThat(gotEvents).as("%s event count", name).hasSize(wantEvents.size());
        assertThat(got.rounds()).as("%s rounds", name).isEqualTo(want.get("rounds").asInt());
        assertThat(got.winner()).as("%s winner", name)
                .isEqualTo(want.get("winner").isNull() ? null : want.get("winner").asString());

        JsonNode wantFinal = want.get("final");
        assertThat(got.finalStates()).as("%s final states", name).hasSize(wantFinal.size());
        for (int i = 0; i < wantFinal.size(); i++) {
            assertThat((JsonNode) got.finalStates().get(i)).as("%s final state %d", name, i).isEqualTo(wantFinal.get(i));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarioNames")
    void runsAreReproducible(String name) {
        JsonNode scenario = find(scenarios.get("scenarios"), name);
        var a = new ScenarioRunner(scenarios.get("spells")).run(scenario);
        var b = new ScenarioRunner(scenarios.get("spells")).run(scenario);
        assertThat(a).isEqualTo(b);
    }

    private static JsonNode find(JsonNode list, String name) {
        for (JsonNode n : list) {
            if (n.get("name").asString().equals(name)) {
                return n;
            }
        }
        throw new IllegalArgumentException(name);
    }
}
