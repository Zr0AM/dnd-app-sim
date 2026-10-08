package org.omnomnom.dnd.sim.domain.combat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.testsupport.TestJson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 300 seeded small fights (sweep.json.gz), every combatant on both sides driven by the real tactical AI, with
 * varied levels, spell loadouts, slot counts, wounds, positions and starting conditions. Because the AI is a pure
 * function of state, hundreds of varied states exercise its decision boundaries (spell versus weapon, which slot to
 * upcast into, who to heal) far better than a few hand-written fights.
 *
 * <p>Each fight is compared with the original TypeScript engine by a SHA-256 of the canonical JSON of its events and
 * final states ({@code tools/reference/gen-encounters.mts}). On a mismatch the actual events are written to
 * {@code build/parity/<name>.json}; print the expected ones with {@code DUMP=<name> node ... gen-encounters.mts}.
 */
class SweepParityTest {

    static ObjectNode fightDocument(ScenarioRunner.Outcome o) {
        ObjectNode doc = ScenarioRunner.MAPPER.createObjectNode();
        var events = doc.putArray("events");
        o.events().forEach(events::add);
        var fin = doc.putArray("final");
        o.finalStates().forEach(fin::add);
        doc.put("rounds", o.rounds());
        if (o.winner() == null) {
            doc.putNull("winner");
        } else {
            doc.put("winner", o.winner());
        }
        return doc;
    }

    @Test
    void everySweepFightMatchesTheTypeScriptEngine() throws Exception {
        JsonNode spells = ScenarioRunner.load("/reference/scenarios.json").get("spells");
        JsonNode expected = ScenarioRunner.load("/reference/sweep-expected.json").get("sweep");
        JsonNode fights;
        try (InputStream in = new GZIPInputStream(getClass().getResourceAsStream("/reference/sweep.json.gz"))) {
            fights = ScenarioRunner.MAPPER.readTree(in).get("scenarios");
        }
        assertThat(fights.size()).isEqualTo(expected.size()).isGreaterThanOrEqualTo(300);

        List<String> mismatches = new ArrayList<>();
        int totalEvents = 0;
        for (int i = 0; i < fights.size(); i++) {
            JsonNode want = expected.get(i);
            assertThat(fights.get(i).get("name").asString()).isEqualTo(want.get("name").asString());
            ScenarioRunner.Outcome got = new ScenarioRunner(spells).run(fights.get(i));
            totalEvents += got.events().size();
            ObjectNode doc = fightDocument(got);
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
                .as("%d of %d sweep fights diverged from the TypeScript engine (actual events in build/parity/; expected via"
                        + " DUMP=<name> node --experimental-strip-types --import ./tools/reference/register-ts.mjs"
                        + " tools/reference/gen-encounters.mts)", mismatches.size(), fights.size())
                .isEmpty();
        assertThat(totalEvents).as("sweep should exercise a large number of events").isGreaterThan(5000);
    }
}
