package org.omnomnom.dnd.sim.domain.combat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.adapter.out.content.SqliteContentSource;
import org.omnomnom.dnd.sim.testsupport.TestJson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 220 seeded fights whose combatants are built by the real content layer (seed data, build compilers, class features,
 * spell catalog, reference fillers and compiled SRD monsters) and driven by the tactical AI. The same recipes are
 * built by the original TypeScript content code ({@code tools/reference/gen-encounters.mts}); a SHA-256 of each
 * fight's canonical events and final states must match, so a wrong feature, AC, hit-point or slot value anywhere in
 * the content layer shows up as a diverging fight. Debugging works as for {@link SweepParityTest}.
 */
class BuildSweepParityTest {

    static SqliteContentSource source;

    @BeforeAll
    static void open() {
        source = SqliteContentSource.open();
    }

    @AfterAll
    static void close() {
        source.close();
    }

    @Test
    void everyBuildFightMatchesTheTypeScriptEngine() throws Exception {
        JsonNode spells = ScenarioRunner.load("/reference/scenarios.json").get("spells");
        JsonNode expected = ScenarioRunner.load("/reference/build-sweep-expected.json").get("sweep");
        JsonNode fights;
        try (InputStream in = new GZIPInputStream(getClass().getResourceAsStream("/reference/build-sweep.json.gz"))) {
            fights = ScenarioRunner.MAPPER.readTree(in).get("scenarios");
        }
        assertThat(fights.size()).isEqualTo(expected.size()).isGreaterThanOrEqualTo(200);

        RecipeFactory recipes = new RecipeFactory(source);
        List<String> mismatches = new ArrayList<>();
        int totalEvents = 0;
        for (int i = 0; i < fights.size(); i++) {
            JsonNode want = expected.get(i);
            assertThat(fights.get(i).get("name").asString()).isEqualTo(want.get("name").asString());
            ScenarioRunner.Outcome got = new ScenarioRunner(spells, recipes).run(fights.get(i));
            totalEvents += got.events().size();
            ObjectNode doc = SweepParityTest.fightDocument(got);
            boolean same = got.events().size() == want.get("events").asInt()
                    && got.rounds() == want.get("rounds").asInt()
                    && TestJson.sha256(TestJson.canonical(doc)).equals(want.get("sha256").asString());
            if (!same) {
                mismatches.add(want.get("name").asString());
                Path dir = Path.of("build", "parity");
                Files.createDirectories(dir);
                Files.writeString(dir.resolve(want.get("name").asString() + ".json"),
                        ScenarioRunner.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(doc.get("events")));
            }
        }
        assertThat(mismatches)
                .as("%d of %d build fights diverged from the TypeScript engine (actual events in build/parity/; expected via"
                        + " DUMP=<name> node --experimental-transform-types --import ./tools/reference/register-ts.mjs"
                        + " tools/reference/gen-encounters.mts)", mismatches.size(), fights.size())
                .isEmpty();
        assertThat(totalEvents).as("sweep should exercise a large number of events").isGreaterThan(5000);
    }
}
