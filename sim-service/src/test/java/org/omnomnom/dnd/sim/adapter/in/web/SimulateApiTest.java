package org.omnomnom.dnd.sim.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The synchronous simulate endpoints through the whole stack: JSON in, content catalogs, engine, JSON out. */
@SpringBootTest
@AutoConfigureMockMvc
class SimulateApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    private ResultActions send(String path, String body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode json(ResultActions r) throws Exception {
        return mapper.readTree(r.andReturn().getResponse().getContentAsString());
    }

    public static final String SOLO = """
            {"level":3,"party":[{"type":"build","genome":{"classSlug":"fighter","weaponName":"Longsword","armorName":"Chain Mail",
              "shield":true,"fightingStyle":"defense"}}],
             "enemies":[{"monsterSlug":"goblin-warrior","count":2}],"map":"open-field","runs":%d,"seed":7,"includeLog":%s}""";

    // ---- encounter -------------------------------------------------------------------------------

    @Test
    void soloEncounterWithALoggedRun() throws Exception {
        JsonNode r = json(send("/api/v1/simulate/encounter", SOLO.formatted(1, true)).andExpect(status().isOk()));
        assertThat(r.get("seed").asLong()).isEqualTo(7);
        assertThat(r.get("level").asInt()).isEqualTo(3);
        assertThat(r.get("map").asString()).isEqualTo("open-field");
        assertThat(r.get("runs").asInt()).isEqualTo(1);
        assertThat(r.get("roundCap").asInt()).isEqualTo(50);
        assertThat(r.get("winRate").has("point")).isTrue();
        assertThat(r.get("winRate").has("halfWidth")).isFalse();

        JsonNode members = r.get("members");
        assertThat(members).hasSize(3); // hero + 2 goblins, party first
        assertThat(members.get(0).get("id").asString()).isEqualTo("hero");
        assertThat(members.get(0).get("side").asString()).isEqualTo("party");
        assertThat(members.get(0).get("genome").get("classSlug").asString()).isEqualTo("fighter");
        assertThat(members.get(0).get("genome").get("fightingStyle").asString()).isEqualTo("defense");
        assertThat(members.get(1).get("id").asString()).isEqualTo("enemy-0");
        assertThat(members.get(1).get("side").asString()).isEqualTo("enemy");
        assertThat(members.get(1).has("genome")).isFalse();

        JsonNode log = r.get("log");
        assertThat(log.get("runIndex").asInt()).isZero();
        JsonNode events = log.get("events");
        assertThat(events.get(0).get("kind").asString()).isEqualTo("initiative");
        assertThat(events.get(0).get("order").get(0).has("id")).isTrue();
        JsonNode last = events.get(events.size() - 1);
        assertThat(last.get("kind").asString()).isEqualTo("end");
        assertThat(last.get("winner").isNull() || last.get("winner").asString().matches("party|enemy")).isTrue();
        boolean sawAttack = false;
        for (JsonNode e : events) {
            if (e.get("kind").asString().equals("attack")) {
                sawAttack = true;
                assertThat(e.get("d20").asInt()).isBetween(1, 20);
            }
            if (e.get("kind").asString().equals("move")) {
                assertThat(e.get("from").has("x")).isTrue();
            }
        }
        assertThat(sawAttack).isTrue();
    }

    @Test
    void theSameRequestGivesTheSameAnswer() throws Exception {
        String a = send("/api/v1/simulate/encounter", SOLO.formatted(20, false)).andReturn().getResponse().getContentAsString();
        String b = send("/api/v1/simulate/encounter", SOLO.formatted(20, false)).andReturn().getResponse().getContentAsString();
        assertThat(a).isEqualTo(b);
        JsonNode r = mapper.readTree(a);
        assertThat(r.has("log")).isTrue();
        assertThat(r.get("log").isNull()).isTrue();
        assertThat(r.get("runs").asInt()).isEqualTo(20);
        double win = r.get("winRate").get("point").asDouble();
        assertThat(win).isBetween(0.0, 1.0);
        assertThat(r.get("avgRoundsEffective").asDouble()).isGreaterThanOrEqualTo(r.get("avgRounds").asDouble());
    }

    @Test
    void aMissingSeedIsDrawnAndEchoed() throws Exception {
        String body = SOLO.formatted(2, false).replace(",\"seed\":7", "");
        JsonNode r = json(send("/api/v1/simulate/encounter", body).andExpect(status().isOk()));
        long seed = r.get("seed").asLong();
        assertThat(seed).isBetween(0L, 4294967295L);
        // Replaying with the echoed seed reproduces the result.
        JsonNode again = json(send("/api/v1/simulate/encounter", body.replace("\"runs\"", "\"seed\":" + seed + ",\"runs\"")).andExpect(status().isOk()));
        assertThat(again.get("winRate")).isEqualTo(r.get("winRate"));
        assertThat(again.get("members")).isEqualTo(r.get("members"));
    }

    @Test
    void differentPartiesFaceTheSameEnemyRollsUnderTheSameSeed() throws Exception {
        String fighter = SOLO.formatted(1, true);
        String wizard = fighter.replace("\"classSlug\":\"fighter\"", "\"classSlug\":\"wizard\"");
        JsonNode a = json(send("/api/v1/simulate/encounter", fighter).andExpect(status().isOk())).get("log").get("events");
        JsonNode b = json(send("/api/v1/simulate/encounter", wizard).andExpect(status().isOk())).get("log").get("events");
        // Each enemy's initiative d20 comes from its own labeled stream, so it does not depend on the hero.
        for (String enemy : new String[] {"enemy-0", "enemy-1"}) {
            assertThat(initiative(a, enemy)).isEqualTo(initiative(b, enemy));
        }
    }

    private static int initiative(JsonNode events, String id) {
        for (JsonNode entry : events.get(0).get("order")) {
            if (entry.get("id").asString().equals(id)) {
                return entry.get("total").asInt();
            }
        }
        throw new AssertionError("no initiative for " + id);
    }

    @Test
    void aReferencePartyAgainstALibraryScenario() throws Exception {
        String body = """
                {"level":5,"party":[{"type":"build","genome":{"classSlug":"wizard"}},{"type":"filler","role":"tank"},
                  {"type":"filler","role":"healer"},{"type":"filler","role":"burst"},{"type":"filler","role":"sustained-dps"},
                  {"type":"filler","role":"buffer"}],"scenarioId":"horde","runs":8,"seed":11}""";
        JsonNode r = json(send("/api/v1/simulate/encounter", body).andExpect(status().isOk()));
        assertThat(r.get("map").asString()).isEqualTo("party-field");
        assertThat(r.get("members")).hasSize(6 + 30);
        assertThat(r.get("members").get(0).get("id").asString()).isEqualTo("hero");
        assertThat(r.get("members").get(1).get("id").asString()).isEqualTo("ally-tank-1");
        assertThat(r.get("members").get(3).get("id").asString()).isEqualTo("ally-burst-3");
        double healing = 0;
        for (JsonNode m : r.get("members")) {
            if (m.get("id").asString().equals("ally-healer-2")) {
                healing = m.get("avgHealingDone").asDouble();
            }
        }
        assertThat(healing).isPositive();
    }

    @Test
    void aSoloLibraryScenarioUsesItsOwnMap() throws Exception {
        String body = """
                {"level":3,"party":[{"type":"build","genome":{"classSlug":"barbarian"}}],"scenarioId":"l3-choke-gnolls","runs":2,"seed":1}""";
        JsonNode r = json(send("/api/v1/simulate/encounter", body).andExpect(status().isOk()));
        assertThat(r.get("map").asString()).isEqualTo("corridor-chokepoint");
        assertThat(r.get("members")).hasSize(3);
    }

    @Test
    void heroIdsFollowTheDocumentedDefaults() throws Exception {
        String body = """
                {"level":3,"party":[{"type":"build","genome":{"classSlug":"fighter"}},{"type":"build","genome":{"classSlug":"cleric"}},
                  {"type":"build","id":"zed","genome":{"classSlug":"rogue"}}],
                 "enemies":[{"monsterSlug":"goblin-warrior","count":1}],"runs":1,"seed":2}""";
        JsonNode r = json(send("/api/v1/simulate/encounter", body).andExpect(status().isOk()));
        assertThat(r.get("map").asString()).isEqualTo("party-field"); // more than one hero
        assertThat(r.get("members").get(0).get("id").asString()).isEqualTo("hero");
        assertThat(r.get("members").get(1).get("id").asString()).isEqualTo("hero-2");
        assertThat(r.get("members").get(2).get("id").asString()).isEqualTo("zed");
    }

    // ---- errors ----------------------------------------------------------------------------------

    private static final String GOBLINS = "\"enemies\":[{\"monsterSlug\":\"goblin-warrior\",\"count\":1}]";
    private static final String FIGHTER = "{\"type\":\"build\",\"genome\":{\"classSlug\":\"fighter\"}}";

    @Test
    void unknownMonsterIs422() throws Exception {
        send("/api/v1/simulate/encounter", "{\"level\":3,\"party\":[" + FIGHTER + "],\"enemies\":[{\"monsterSlug\":\"nope\",\"count\":1}]}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("unknown-monster"))
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    void aMonsterWithNoAttackIs422() throws Exception {
        String slug = null;
        JsonNode page = json(mvc.perform(MockMvcRequestBuilders.get("/api/v1/content/monsters?limit=400")).andExpect(status().isOk()));
        for (JsonNode m : page.get("items")) {
            if (!m.get("hasAttack").asBoolean()) {
                slug = m.get("slug").asString();
                break;
            }
        }
        assertThat(slug).as("the SRD has monsters without a usable attack").isNotNull();
        send("/api/v1/simulate/encounter", "{\"level\":3,\"party\":[" + FIGHTER + "],\"enemies\":[{\"monsterSlug\":\"" + slug + "\",\"count\":1}]}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("monster-has-no-attacks"));
    }

    @Test
    void overCapacityIs422() throws Exception {
        send("/api/v1/simulate/encounter",
                "{\"level\":3,\"party\":[" + FIGHTER + "," + FIGHTER + "]," + GOBLINS + ",\"map\":\"open-field\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("over-capacity"));
        send("/api/v1/simulate/encounter",
                "{\"level\":3,\"party\":[" + FIGHTER + "],\"enemies\":[{\"monsterSlug\":\"goblin-warrior\",\"count\":7}],\"map\":\"open-field\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("over-capacity"));
    }

    @Test
    void unknownScenarioMapMismatchAndDuplicateIdsAre422() throws Exception {
        send("/api/v1/simulate/encounter", "{\"level\":3,\"party\":[" + FIGHTER + "],\"scenarioId\":\"nope\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("unknown-scenario"));
        send("/api/v1/simulate/encounter", "{\"level\":3,\"party\":[" + FIGHTER + "],\"scenarioId\":\"l3-choke-gnolls\",\"map\":\"open-field\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("map-mismatch"));
        send("/api/v1/simulate/encounter",
                "{\"level\":3,\"party\":[{\"type\":\"filler\",\"id\":\"a\",\"role\":\"tank\"},{\"type\":\"filler\",\"id\":\"a\",\"role\":\"healer\"}]," + GOBLINS + "}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("duplicate-id"));
        send("/api/v1/simulate/encounter", "{\"level\":3,\"party\":[" + FIGHTER + "]," + GOBLINS + ",\"runs\":2,\"includeLog\":true,\"logRun\":2}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-log-run"));
    }

    @Test
    void badGenomesAre422() throws Exception {
        send("/api/v1/simulate/encounter",
                "{\"level\":3,\"party\":[{\"type\":\"build\",\"genome\":{\"classSlug\":\"fighter\",\"weaponName\":\"Banana\"}}]," + GOBLINS + "}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("unknown-weapon"));
        send("/api/v1/simulate/encounter",
                "{\"level\":3,\"party\":[{\"type\":\"build\",\"genome\":{\"classSlug\":\"fighter\",\"abilityAssignment\":[0,0,1,2,3,4]}}]," + GOBLINS + "}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("invalid-ability-assignment"));
    }

    @Test
    void invalidFieldsAre400WithFieldErrors() throws Exception {
        send("/api/v1/simulate/encounter", "{\"level\":4,\"party\":[" + FIGHTER + "]," + GOBLINS + "}")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("invalid-request"))
                .andExpect(jsonPath("$.errors[0].field").value("level"));
        send("/api/v1/simulate/encounter", "{\"level\":3,\"party\":[" + FIGHTER + "]," + GOBLINS + ",\"seed\":4294967296}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("seed"));
        send("/api/v1/simulate/encounter", "{\"level\":3,\"party\":[" + FIGHTER + "]," + GOBLINS + ",\"runs\":2001}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("runs"));
        send("/api/v1/simulate/encounter", "{\"level\":3,\"party\":[]," + GOBLINS + "}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("party"));
        send("/api/v1/simulate/encounter", "{\"level\":3,\"party\":[{\"type\":\"build\",\"genome\":{\"classSlug\":\"fighter\",\"abilityAssignment\":[1,2]}}]," + GOBLINS + "}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("party[0].genome.abilityAssignment"));
    }

    @Test
    void crossFieldRulesAre400() throws Exception {
        // Neither enemies nor a scenario.
        send("/api/v1/simulate/encounter", "{\"level\":3,\"party\":[" + FIGHTER + "]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("invalid-enemy-spec"));
        // Both.
        send("/api/v1/simulate/encounter", "{\"level\":3,\"party\":[" + FIGHTER + "]," + GOBLINS + ",\"scenarioId\":\"horde\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("invalid-enemy-spec"));
        // A build without a genome and a filler without a role.
        send("/api/v1/simulate/encounter", "{\"level\":3,\"party\":[{\"type\":\"build\"},{\"type\":\"filler\"}]," + GOBLINS + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("party[0].genome"))
                .andExpect(jsonPath("$.errors[1].field").value("party[1].role"));
    }

    @Test
    void malformedJsonAndUnknownEnumValuesAre400() throws Exception {
        send("/api/v1/simulate/encounter", "{not json").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("malformed-json"));
        send("/api/v1/simulate/encounter",
                "{\"level\":3,\"party\":[{\"type\":\"build\",\"genome\":{\"classSlug\":\"jester\"}}]," + GOBLINS + "}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("malformed-json"));
    }

    // ---- eval and campaign -----------------------------------------------------------------------

    @Test
    void soloEvaluation() throws Exception {
        JsonNode r = json(send("/api/v1/simulate/eval",
                "{\"level\":3,\"genome\":{\"classSlug\":\"barbarian\"},\"runs\":4,\"seed\":5,\"role\":\"tank\"}").andExpect(status().isOk()));
        assertThat(r.get("context").asString()).isEqualTo("solo");
        assertThat(r.get("seed").asLong()).isEqualTo(5);
        assertThat(r.get("description").asString()).startsWith("L3 barbarian");
        assertThat(r.get("genome").get("armorName").isNull()).isTrue(); // barbarians fight unarmored
        assertThat(r.get("runs").asInt()).isEqualTo(4 * 5);
        assertThat(r.get("winRate").get("lo").asDouble()).isLessThanOrEqualTo(r.get("winRate").get("hi").asDouble());
        assertThat(r.get("objectives").propertyNames()).containsExactly("reliability", "offense", "survival", "efficiency", "control", "support");
        assertThat(r.get("objectives").get("support").asDouble()).isZero();
        assertThat(r.get("objectives").get("efficiency").asDouble()).isLessThanOrEqualTo(0);
        assertThat(r.get("ci").has("damage")).isTrue();
        assertThat(r.get("warnings")).isEmpty();
    }

    @Test
    void aSoloEvaluationForAPartyOnlyRoleWarns() throws Exception {
        JsonNode r = json(send("/api/v1/simulate/eval",
                "{\"level\":3,\"genome\":{\"classSlug\":\"cleric\"},\"runs\":2,\"seed\":5,\"role\":\"healer\"}").andExpect(status().isOk()));
        assertThat(r.get("warnings")).hasSize(1);
        assertThat(r.get("warnings").get(0).asString()).contains("healer");
    }

    @Test
    void partyEvaluationCarriesSupportSignal() throws Exception {
        JsonNode r = json(send("/api/v1/simulate/eval",
                "{\"level\":5,\"genome\":{\"classSlug\":\"cleric\"},\"context\":\"party\",\"role\":\"healer\",\"runs\":3,\"seed\":5}")
                .andExpect(status().isOk()));
        assertThat(r.get("context").asString()).isEqualTo("party");
        assertThat(r.get("runs").asInt()).isEqualTo(3 * 3 * 2); // three templates, two scenarios, three runs
        assertThat(r.get("objectives").get("support").asDouble()).isPositive();
        assertThat(r.has("ci")).isFalse();
    }

    @Test
    void anUnknownRoleIs422() throws Exception {
        send("/api/v1/simulate/eval", "{\"level\":3,\"genome\":{\"classSlug\":\"fighter\"},\"role\":\"jester\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("unknown-role"));
    }

    @Test
    void anEvaluationMatchesTheBuildItDescribes() throws Exception {
        // The effective genome echoed by one call reproduces the same numbers when sent back in full.
        JsonNode first = json(send("/api/v1/simulate/eval", "{\"level\":3,\"genome\":{\"classSlug\":\"paladin\"},\"runs\":3,\"seed\":9}"));
        JsonNode g = first.get("genome");
        JsonNode second = json(send("/api/v1/simulate/eval",
                "{\"level\":3,\"genome\":" + g + ",\"runs\":3,\"seed\":123}").andExpect(status().isOk()));
        assertThat(second.get("objectives")).isEqualTo(first.get("objectives"));
        assertThat(second.get("genome")).isEqualTo(g);
    }

    @Test
    void adventuringDay() throws Exception {
        JsonNode r = json(send("/api/v1/simulate/campaign", "{\"level\":5,\"genome\":{\"classSlug\":\"barbarian\"},\"days\":4,\"seed\":3}")
                .andExpect(status().isOk()));
        assertThat(r.get("days").asInt()).isEqualTo(4);
        assertThat(r.get("encountersPerDay").asInt()).isEqualTo(5);
        assertThat(r.get("dayWinRate").get("point").asDouble()).isBetween(0.0, 1.0);
        assertThat(r.get("avgEncountersCleared").asDouble()).isBetween(0.0, 5.0);
        send("/api/v1/simulate/campaign", "{\"level\":5,\"genome\":{\"classSlug\":\"barbarian\"},\"days\":0}").andExpect(status().isBadRequest());
        send("/api/v1/simulate/campaign", "{\"level\":5,\"genome\":{\"classSlug\":\"barbarian\"},\"shortRestHealFrac\":1.5}").andExpect(status().isBadRequest());
    }

    // ---- defaults and problem bodies (found by mutation testing) ---------------------------------

    @Test
    void omittedOptionsTakeTheirDefaults() throws Exception {
        JsonNode enc = json(send("/api/v1/simulate/encounter",
                "{\"level\":3,\"party\":[{\"type\":\"build\",\"genome\":{\"classSlug\":\"fighter\"}}],"
                        + "\"enemies\":[{\"monsterSlug\":\"goblin-warrior\",\"count\":1}],\"seed\":1,\"includeLog\":true}").andExpect(status().isOk()));
        assertThat(enc.get("runs").asInt()).isEqualTo(1);
        assertThat(enc.get("roundCap").asInt()).isEqualTo(50);
        // With a single run, the averages are that run's own values.
        JsonNode events = enc.get("log").get("events");
        int endRound = events.get(events.size() - 1).get("round").asInt();
        assertThat(enc.get("avgRounds").asDouble()).isEqualTo(endRound).isEqualTo(enc.get("log").get("rounds").asInt());
        JsonNode day = json(send("/api/v1/simulate/campaign", "{\"level\":3,\"genome\":{\"classSlug\":\"fighter\"},\"seed\":1}").andExpect(status().isOk()));
        assertThat(day.get("days").asInt()).isEqualTo(16);
    }

    @Test
    void aFullMapIsNotOverCapacity() throws Exception {
        send("/api/v1/simulate/encounter", "{\"level\":3,\"party\":[" + FIGHTER + "],\"enemies\":[{\"monsterSlug\":\"goblin-warrior\",\"count\":6}],"
                + "\"map\":\"open-field\",\"seed\":1}").andExpect(status().isOk());
        send("/api/v1/simulate/encounter", "{\"level\":3,\"party\":[" + FIGHTER + "],\"enemies\":[{\"monsterSlug\":\"goblin-warrior\",\"count\":6}],"
                + "\"map\":\"corridor-chokepoint\",\"seed\":1}").andExpect(status().isOk());
    }

    @Test
    void problemBodiesCarryTheirTypeAndCode() throws Exception {
        send("/api/v1/simulate/encounter", "{\"level\":3,\"party\":[" + FIGHTER + "],\"enemies\":[{\"monsterSlug\":\"nope\",\"count\":1}]}")
                .andExpect(jsonPath("$.type").value("urn:dnd-app-sim:problem:unknown-monster"))
                .andExpect(jsonPath("$.title").value("Unprocessable request"))
                .andExpect(jsonPath("$.errors[0].field").value("enemies"));
        send("/api/v1/simulate/encounter", "{\"level\":3,\"party\":[" + FIGHTER + "]}")
                .andExpect(jsonPath("$.type").value("urn:dnd-app-sim:problem:invalid-request"))
                .andExpect(jsonPath("$.code").value("invalid-request"))
                .andExpect(jsonPath("$.title").value("Invalid request"));
        send("/api/v1/simulate/encounter", "{oops")
                .andExpect(jsonPath("$.type").value("urn:dnd-app-sim:problem:malformed-json"));
        // Parameter problems name the constraint, not the controller method.
        for (var probe : new String[][] {
            {"/api/v1/content/monsters?limit=0", "limit", "Min"},
            {"/api/v1/content/classes?level=4", "level", "ValidLevel"},
            {"/api/v1/content/classes?level=abc", "level", "type-mismatch"},
            {"/api/v1/content/classes", "level", "required"}
        }) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(probe[0]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:dnd-app-sim:problem:invalid-request"))
                    .andExpect(jsonPath("$.code").value("invalid-request"))
                    .andExpect(jsonPath("$.errors[0].field").value(probe[1]))
                    .andExpect(jsonPath("$.errors[0].code").value(probe[2]));
        }
    }

    // ---- adversarial review fixes ----------------------------------------------------------------

    @Test
    void springsOwnClientErrorsKeepTheirStatus() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/nope"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("not-found"))
                .andExpect(jsonPath("$.status").value(404));
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/simulate/encounter"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("method-not-allowed"))
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("POST")));
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/simulate/encounter").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("unsupported-media-type"));
    }

    @Test
    void partyIdsMayNotImpersonateAnEnemy() throws Exception {
        send("/api/v1/simulate/encounter",
                "{\"level\":3,\"party\":[{\"type\":\"filler\",\"id\":\"enemy-0\",\"role\":\"tank\"}]," + GOBLINS + ",\"seed\":1}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("reserved-id"))
                .andExpect(jsonPath("$.errors[0].field").value("party[0].id"));
        send("/api/v1/simulate/encounter",
                "{\"level\":3,\"party\":[{\"type\":\"build\",\"id\":\"enemy-x\",\"genome\":{\"classSlug\":\"fighter\"}}]," + GOBLINS + "}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("reserved-id"));
        // An id that merely contains the word is fine.
        send("/api/v1/simulate/encounter",
                "{\"level\":3,\"party\":[{\"type\":\"filler\",\"id\":\"my-enemy-0\",\"role\":\"tank\"}]," + GOBLINS + ",\"seed\":1}")
                .andExpect(status().isOk());
    }

    @Test
    void theCampaignSeedChoosesTheDays() throws Exception {
        // A fully specified genome, so the seed only affects the days.
        String genome = "{\"classSlug\":\"barbarian\",\"abilityAssignment\":[0,2,1,3,4,5],\"weaponName\":\"Greataxe\",\"armorName\":null,"
                + "\"shield\":false,\"twoHanded\":true}";
        java.util.function.LongFunction<String> day = seed -> {
            try {
                JsonNode r = json(send("/api/v1/simulate/campaign", "{\"level\":5,\"genome\":" + genome + ",\"days\":6,\"seed\":" + seed + "}")
                        .andExpect(status().isOk()));
                return r.get("dayWinRate").get("point").asString() + "/" + r.get("avgEncountersCleared").asString();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        };
        String first = day.apply(1);
        assertThat(day.apply(1)).isEqualTo(first);
        boolean differs = false;
        for (long seed = 2; seed <= 8 && !differs; seed++) {
            differs = !day.apply(seed).equals(first);
        }
        assertThat(differs).isTrue();
    }
}
