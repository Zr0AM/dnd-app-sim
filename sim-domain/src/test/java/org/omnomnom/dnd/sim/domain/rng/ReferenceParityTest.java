package org.omnomnom.dnd.sim.domain.rng;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Checks the Java RNG against values produced by the original TypeScript implementation
 * ({@code tools/reference/gen-rng.mts}). Statistical equivalence is all the migration requires; the RNG is a
 * 70-line algorithm, so it is ported bit-exact and these comparisons are exact.
 */
class ReferenceParityTest {

    static JsonNode ref;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = ReferenceParityTest.class.getResourceAsStream("/reference/rng.json")) {
            ref = new ObjectMapper().readTree(in);
        }
    }

    @Test
    void deriveSeedMatchesTypeScript() {
        assertThat(ref.get("deriveSeed").size()).isGreaterThan(30);
        for (JsonNode c : ref.get("deriveSeed")) {
            assertThat(Seeds.deriveSeed(c.get("root").asLong(), c.get("label").asString()))
                    .as("deriveSeed(%s, %s)", c.get("root"), c.get("label"))
                    .isEqualTo(c.get("value").asLong());
        }
    }

    @Test
    void seedFromMatchesTypeScript() {
        for (JsonNode c : ref.get("seedFrom")) {
            List<Object> parts = new ArrayList<>();
            for (JsonNode p : c.get("parts")) {
                parts.add(p.isString() ? p.asString() : (Object) p.asLong());
            }
            assertThat(Seeds.seedFrom(parts.toArray())).as("seedFrom(%s)", parts).isEqualTo(c.get("value").asLong());
        }
    }

    @Test
    void streamsMatchTypeScriptExactly() {
        for (JsonNode c : ref.get("streams")) {
            Rng rng = new LabeledRandom(c.get("root").asLong()).stream(c.get("label").asString());
            for (JsonNode expected : c.get("first")) {
                assertThat(rng.next()).isEqualTo(expected.asDouble());
            }
        }
    }

    @Test
    void childStreamsMatchTypeScriptExactly() {
        for (JsonNode c : ref.get("child")) {
            Rng rng = new LabeledRandom(c.get("root").asLong())
                    .child(c.get("child").asString())
                    .stream(c.get("label").asString());
            for (JsonNode expected : c.get("first")) {
                assertThat(rng.next()).isEqualTo(expected.asDouble());
            }
        }
    }
}
