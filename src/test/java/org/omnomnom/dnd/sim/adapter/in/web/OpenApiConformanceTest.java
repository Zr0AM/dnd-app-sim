package org.omnomnom.dnd.sim.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.testsupport.OpenApiSchema;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Checks real responses against {@code docs/api/openapi.yaml}, the contract the service was designed from: every
 * required property present, no property the schema does not declare, values of the declared type, enum, bounds and
 * discriminated variant. A small JSON Schema interpreter covers the subset the contract uses.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiConformanceTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    // ---- helpers ----------------------------------------------------------------------------------

    private JsonNode call(org.springframework.test.web.servlet.RequestBuilder request, int expectedStatus) throws Exception {
        var res = mvc.perform(request).andReturn().getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(expectedStatus);
        return mapper.readTree(res.getContentAsString());
    }

    private JsonNode send(String path, String body, int status) throws Exception {
        return call(post(path).contentType(MediaType.APPLICATION_JSON).content(body), status);
    }

    private static void conforms(JsonNode body, String schema) {
        OpenApiSchema.assertConforms(body, schema);
    }

    private static void conformsEach(JsonNode array, String schema) {
        assertThat(array.isArray() && array.size() > 0).isTrue();
        array.forEach(item -> conforms(item, schema));
    }

    // ---- the checks -------------------------------------------------------------------------------

    @Test
    void theInterpreterCatchesViolations() throws Exception {
        List<String> errors = new ArrayList<>();
        OpenApiSchema.check(mapper.readTree("{\"point\":1,\"lo\":0,\"hi\":2,\"extra\":true}"), Map.of("$ref", "#/components/schemas/Interval"), "i", errors);
        assertThat(errors).anyMatch(e -> e.contains("extra"));
        errors.clear();
        OpenApiSchema.check(mapper.readTree("{\"point\":1,\"lo\":0}"), Map.of("$ref", "#/components/schemas/Interval"), "i", errors);
        assertThat(errors).anyMatch(e -> e.contains("missing required property hi"));
        errors.clear();
        OpenApiSchema.check(mapper.readTree("{\"kind\":\"nonsense\"}"), Map.of("$ref", "#/components/schemas/CombatEvent"), "e", errors);
        assertThat(errors).anyMatch(e -> e.contains("unknown discriminator"));
        errors.clear();
        OpenApiSchema.check(mapper.readTree("{\"kind\":\"attack\",\"attacker\":\"a\",\"target\":\"b\",\"weapon\":\"w\",\"d20\":25,\"hit\":true,\"crit\":false,\"damage\":3}"),
                Map.of("$ref", "#/components/schemas/CombatEvent"), "e", errors);
        assertThat(errors).anyMatch(e -> e.contains("above 20"));
    }

    @Test
    void encounterResponses() throws Exception {
        JsonNode solo = send("/api/v1/simulate/encounter", SimulateApiTest.SOLO.formatted(3, true), 200);
        conforms(solo, "EncounterResponse");
        // Every event kind the engine emitted in this log is valid against its variant (checked inside EncounterResponse).
        JsonNode party = send("/api/v1/simulate/encounter", """
                {"level":11,"party":[{"type":"build","genome":{"classSlug":"druid"}},{"type":"filler","role":"tank"},{"type":"filler","role":"healer"},
                 {"type":"filler","role":"controller"}],"scenarioId":"boss-young-dragon","runs":2,"seed":3,"includeLog":true}""", 200);
        conforms(party, "EncounterResponse");
        Set<String> kinds = new TreeSet<>();
        party.get("log").get("events").forEach(e -> kinds.add(e.get("kind").asString()));
        assertThat(kinds).contains("initiative", "round", "turn", "attack", "end");
    }

    @Test
    void everyEventKindIsExercisedAndConforms() throws Exception {
        // Casters, a healer and a legendary boss between them produce most of the 18 kinds; collect across a few fights.
        Set<String> seen = new TreeSet<>();
        String[] bodies = {
            """
            {"level":11,"party":[{"type":"build","genome":{"classSlug":"paladin"}},{"type":"filler","role":"healer"},{"type":"filler","role":"buffer"},
             {"type":"filler","role":"controller"},{"type":"filler","role":"sustained-dps"}],"scenarioId":"boss-young-dragon","runs":4,"seed":21,"includeLog":true,"logRun":1}""",
            """
            {"level":5,"party":[{"type":"build","genome":{"classSlug":"ranger"}},{"type":"filler","role":"healer"},{"type":"filler","role":"buffer"},
             {"type":"filler","role":"controller"}],"scenarioId":"horde","runs":3,"seed":8,"includeLog":true}""",
            """
            {"level":3,"party":[{"type":"build","genome":{"classSlug":"warlock"}}],"enemies":[{"monsterSlug":"goblin-warrior","count":3}],"runs":1,"seed":4,"includeLog":true}"""
        };
        for (String b : bodies) {
            JsonNode r = send("/api/v1/simulate/encounter", b, 200);
            conforms(r, "EncounterResponse");
            r.get("log").get("events").forEach(e -> seen.add(e.get("kind").asString()));
        }
        assertThat(seen).contains("initiative", "round", "turn", "move", "attack", "spell", "down", "end", "legendary");
    }

    @Test
    void evalAndCampaignResponses() throws Exception {
        conforms(send("/api/v1/simulate/eval", "{\"level\":3,\"genome\":{\"classSlug\":\"rogue\"},\"runs\":2,\"seed\":1}", 200), "EvalResponse");
        conforms(send("/api/v1/simulate/eval",
                "{\"level\":5,\"genome\":{\"classSlug\":\"bard\"},\"context\":\"party\",\"role\":\"buffer\",\"runs\":2,\"seed\":1}", 200), "EvalResponse");
        conforms(send("/api/v1/simulate/eval", "{\"level\":3,\"genome\":{\"classSlug\":\"cleric\"},\"role\":\"healer\",\"runs\":1}", 200), "EvalResponse");
        conforms(send("/api/v1/simulate/campaign", "{\"level\":3,\"genome\":{\"classSlug\":\"monk\"},\"days\":2,\"seed\":1}", 200), "CampaignResult");
    }

    @Test
    void contentResponses() throws Exception {
        conformsEach(call(get("/api/v1/content/classes?level=3"), 200), "ClassInfo");
        conformsEach(call(get("/api/v1/content/roles"), 200), "RoleInfo");
        for (int level : new int[] {3, 5, 11, 17}) {
            conformsEach(call(get("/api/v1/content/scenarios?level=" + level), 200), "ScenarioInfo");
            conformsEach(call(get("/api/v1/content/weapons?level=" + level), 200), "WeaponInfo");
            conformsEach(call(get("/api/v1/content/armors?level=" + level), 200), "ArmorInfo");
        }
        conformsEach(call(get("/api/v1/content/maps"), 200), "MapInfo");
        conformsEach(call(get("/api/v1/content/party-templates"), 200), "PartyTemplateInfo");
        JsonNode page = call(get("/api/v1/content/monsters?limit=400"), 200);
        conformsEach(page.get("items"), "MonsterInfo");
        assertThat(page.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void problemResponses() throws Exception {
        conforms(send("/api/v1/simulate/encounter", "{\"level\":4}", 400), "Problem");
        conforms(send("/api/v1/simulate/encounter", "{oops", 400), "Problem");
        conforms(send("/api/v1/simulate/encounter",
                "{\"level\":3,\"party\":[{\"type\":\"filler\",\"role\":\"tank\"}],\"enemies\":[{\"monsterSlug\":\"nope\",\"count\":1}]}", 422), "Problem");
        conforms(call(get("/api/v1/content/classes?level=4"), 400), "Problem");
    }
}
