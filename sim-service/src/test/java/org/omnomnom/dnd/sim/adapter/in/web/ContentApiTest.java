package org.omnomnom.dnd.sim.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The read-only content endpoints (the CLI {@code browse} mode and the lists a client needs to build requests). */
@SpringBootTest
@AutoConfigureMockMvc
class ContentApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    private JsonNode fetch(String url) throws Exception {
        return mapper.readTree(mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void twelveClassesWithTheirSubclass() throws Exception {
        JsonNode classes = fetch("/api/v1/content/classes?level=3");
        assertThat(classes).hasSize(12);
        assertThat(classes.get(0).get("slug").asString()).isEqualTo("fighter");
        assertThat(classes.get(0).get("subclass").asString()).isEqualTo("champion");
        assertThat(classes.get(0).get("kind").asString()).isEqualTo("martial");
        assertThat(classes.get(11).get("slug").asString()).isEqualTo("druid");
        assertThat(classes.get(11).get("kind").asString()).isEqualTo("caster");
    }

    @Test
    void rolesIncludeEqualAndMarkPartyOnlyOnes() throws Exception {
        JsonNode roles = fetch("/api/v1/content/roles");
        assertThat(roles).hasSize(8);
        assertThat(roles.get(0).get("name").asString()).isEqualTo("equal");
        assertThat(roles.get(0).get("weights").get("support").asDouble()).isEqualTo(1.0);
        List<String> partyOnly = new ArrayList<>();
        for (JsonNode r : roles) {
            if (r.get("partyOnly").asBoolean()) {
                partyOnly.add(r.get("name").asString());
            }
        }
        assertThat(partyOnly).containsExactlyInAnyOrder("healer", "buffer", "controller");
        JsonNode tank = null;
        for (JsonNode r : roles) {
            if (r.get("name").asString().equals("tank")) {
                tank = r;
            }
        }
        assertThat(tank.get("weights").get("survival").asDouble()).isEqualTo(3.0);
    }

    @Test
    void scenariosListSoloAndPartyEncounters() throws Exception {
        JsonNode scenarios = fetch("/api/v1/content/scenarios?level=3");
        assertThat(scenarios).hasSize(5 + 2);
        JsonNode pair = scenarios.get(0);
        assertThat(pair.get("id").asString()).isEqualTo("l3-pair-goblins");
        assertThat(pair.get("kind").asString()).isEqualTo("solo");
        assertThat(pair.get("difficulty").asString()).isEqualTo("low");
        assertThat(pair.get("xp").asInt()).isEqualTo(100);
        assertThat(pair.get("enemies").get(0).get("monsterSlug").asString()).isEqualTo("goblin-warrior");
        assertThat(pair.get("enemies").get(0).get("count").asInt()).isEqualTo(2);
        JsonNode horde = scenarios.get(5);
        assertThat(horde.get("id").asString()).isEqualTo("horde");
        assertThat(horde.get("kind").asString()).isEqualTo("party");
        assertThat(horde.get("mapId").asString()).isEqualTo("party-field");
        assertThat(horde.get("enemies").get(0).get("perMember").asInt()).isEqualTo(5);
        assertThat(horde.has("count") || horde.get("enemies").get(0).has("count")).isFalse();
        JsonNode boss = null;
        for (JsonNode s : fetch("/api/v1/content/scenarios?level=17")) {
            if (s.get("id").asString().equals("boss-adult-dragon")) {
                boss = s;
            }
        }
        assertThat(boss.get("enemies").get(0).get("count").asInt()).isEqualTo(1);
    }

    @Test
    void monstersPageBySlug() throws Exception {
        JsonNode first = fetch("/api/v1/content/monsters?limit=5");
        assertThat(first.get("items")).hasSize(5);
        assertThat(first.get("nextCursor").asString()).isEqualTo(first.get("items").get(4).get("slug").asString());
        JsonNode second = fetch("/api/v1/content/monsters?limit=5&cursor=" + first.get("nextCursor").asString());
        assertThat(second.get("items").get(0).get("slug").asString()).isGreaterThan(first.get("nextCursor").asString());

        JsonNode wolves = fetch("/api/v1/content/monsters?q=WOLF");
        assertThat(wolves.get("nextCursor").isNull()).isTrue();
        for (JsonNode m : wolves.get("items")) {
            assertThat(m.get("slug").asString() + m.get("name").asString().toLowerCase()).contains("wolf");
        }
        JsonNode dragons = fetch("/api/v1/content/monsters?q=red-dragon&minCr=10&maxCr=17&limit=400");
        assertThat(dragons.get("items")).isNotEmpty();
        dragons.get("items").forEach(m -> assertThat(m.get("cr").asDouble()).isBetween(10.0, 17.0));
        JsonNode all = fetch("/api/v1/content/monsters?limit=400");
        assertThat(all.get("items")).hasSize(341);
        long withAttack = 0;
        for (JsonNode m : all.get("items")) {
            if (m.get("hasAttack").asBoolean()) {
                withAttack++;
            }
        }
        assertThat(withAttack).isEqualTo(321); // 341 compiled, 321 with a usable attack
    }

    @Test
    void weaponsArmorsMapsAndPartyTemplates() throws Exception {
        JsonNode weapons = fetch("/api/v1/content/weapons?level=3");
        assertThat(weapons).hasSize(8);
        JsonNode longsword = null;
        for (JsonNode w : weapons) {
            if (w.get("name").asString().equals("Longsword")) {
                longsword = w;
            }
        }
        assertThat(longsword.get("damage").asString()).isEqualTo("1d8");
        assertThat(longsword.get("versatileDamage").asString()).isEqualTo("1d10");
        assertThat(longsword.get("damageType").asString()).isEqualTo("slashing");
        assertThat(longsword.get("category").asString()).isEqualTo("martial");
        assertThat(longsword.get("range").asString()).isEqualTo("melee");
        assertThat(longsword.get("properties")).extracting(JsonNode::asString).contains("versatile");

        JsonNode armors = fetch("/api/v1/content/armors?level=3");
        assertThat(armors).hasSize(4);
        assertThat(armors.get(3).get("name").asString()).isEqualTo("Chain Mail");
        assertThat(armors.get(3).get("baseAc").asInt()).isEqualTo(16);
        assertThat(armors.get(3).get("category").asString()).isEqualTo("heavy");

        JsonNode maps = fetch("/api/v1/content/maps");
        assertThat(maps).hasSize(3);
        JsonNode party = maps.get(2);
        assertThat(party.get("id").asString()).isEqualTo("party-field");
        assertThat(party.get("width").asInt()).isEqualTo(22);
        assertThat(party.get("partyCapacity").asInt()).isEqualTo(6);
        assertThat(party.get("enemyCapacity").asInt()).isEqualTo(30);
        assertThat(maps.get(0).get("partyCapacity").asInt()).isEqualTo(1);
        assertThat(maps.get(0).get("enemyCapacity").asInt()).isEqualTo(6);

        JsonNode templates = fetch("/api/v1/content/party-templates");
        assertThat(templates).hasSize(3);
        assertThat(templates.get(0).get("id").asString()).isEqualTo("R6");
        assertThat(templates.get(0).get("roles")).hasSize(6);
        assertThat(templates.get(0).get("flex").asString()).isEqualTo("burst");
        assertThat(templates.get(2).get("weight").asInt()).isEqualTo(1);
    }

    @Test
    void invalidParametersAre400() throws Exception {
        mvc.perform(get("/api/v1/content/classes?level=4")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid-request"));
        mvc.perform(get("/api/v1/content/classes")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/content/classes?level=abc")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/content/monsters?limit=0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/content/monsters?limit=401")).andExpect(status().isBadRequest());
    }
}
