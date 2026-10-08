package org.omnomnom.dnd.sim.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.testsupport.OpenApiSchema;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/** API-key authentication and rate limiting through the real filter chain, with deliberately tiny budgets. */
@SpringBootTest(properties = {
    "sim.security.mode=api-key",
    "sim.security.api-keys=alpha-key,beta-key",
    "sim.rate-limit.enabled=true",
    "sim.rate-limit.requests-per-minute=8",
    "sim.rate-limit.simulations-per-minute=2"
})
@AutoConfigureMockMvc
class AccessApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    private static final String ENCOUNTER = """
            {"level":3,"party":[{"type":"filler","role":"tank"}],"enemies":[{"monsterSlug":"goblin-warrior","count":1}],"seed":1}""";

    @Test
    void healthAndInfoAreOpen() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().is(org.hamcrest.Matchers.not(401)));
    }

    @Test
    void everythingElseNeedsAKey() throws Exception {
        var response = mvc.perform(get("/api/v1/content/maps"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("unauthorized"))
                .andReturn().getResponse();
        OpenApiSchema.assertConforms(mapper.readTree(response.getContentAsString()), "Problem");
        mvc.perform(post("/api/v1/simulate/encounter").contentType(MediaType.APPLICATION_JSON).content(ENCOUNTER)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/jobs/x")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/reports")).andExpect(status().isUnauthorized());
    }

    @Test
    void aValidKeyGetsInByEitherHeader() throws Exception {
        mvc.perform(get("/api/v1/content/maps").header("X-API-Key", "alpha-key")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/content/maps").header("Authorization", "Bearer beta-key")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/content/maps").header("Authorization", "bearer  alpha-key ")).andExpect(status().isOk());
    }

    @Test
    void wrongOrMalformedCredentialsAreRefusedWithoutEchoingThem() throws Exception {
        for (var request : new org.springframework.test.web.servlet.RequestBuilder[] {
            get("/api/v1/content/maps").header("X-API-Key", "nope-key"),
            get("/api/v1/content/maps").header("X-API-Key", "alpha-key-extra"),
            get("/api/v1/content/maps").header("X-API-Key", "alph"),
            get("/api/v1/content/maps").header("X-API-Key", ""),
            get("/api/v1/content/maps").header("Authorization", "Bearer "),
            get("/api/v1/content/maps").header("Authorization", "Basic YWxwaGEta2V5"),
            get("/api/v1/content/maps").header("Authorization", "alpha-key")
        }) {
            String body = mvc.perform(request).andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("alpha-key").doesNotContain("nope-key");
        }
    }

    @Test
    void simulationsHaveTheirOwnSmallerBudgetPerKey() throws Exception {
        String key = "alpha-key";
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/simulate/encounter").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON).content(ENCOUNTER))
                    .andExpect(status().isOk());
        }
        var refused = mvc.perform(post("/api/v1/simulate/encounter").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON).content(ENCOUNTER))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("rate-limited"))
                .andReturn().getResponse();
        assertThat(Integer.parseInt(refused.getHeader("Retry-After"))).isBetween(1, 60);
        OpenApiSchema.assertConforms(mapper.readTree(refused.getContentAsString()), "Problem");
        // Other endpoints still draw on the ordinary budget, and another key has a fresh simulation budget.
        mvc.perform(get("/api/v1/content/maps").header("X-API-Key", key)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/simulate/encounter").header("X-API-Key", "beta-key").contentType(MediaType.APPLICATION_JSON).content(ENCOUNTER))
                .andExpect(status().isOk());
    }

    @Test
    void ordinaryRequestsAreLimitedToo() throws Exception {
        String key = "beta-key";
        // beta-key may already have spent a few tokens in other tests of this context; keep going until refused.
        int ok = 0;
        while (ok < 20) {
            var r = mvc.perform(get("/api/v1/content/roles").header("X-API-Key", key)).andReturn().getResponse();
            if (r.getStatus() == 429) {
                break;
            }
            assertThat(r.getStatus()).isEqualTo(200);
            ok++;
        }
        assertThat(ok).isLessThanOrEqualTo(8);
        mvc.perform(get("/api/v1/content/roles").header("X-API-Key", key)).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }

    @Test
    void rejectedRequestsDoNotSpendTheirClientsBudget() throws Exception {
        // Unauthenticated floods are turned away before the limiter, so they cannot starve a real key.
        for (int i = 0; i < 30; i++) {
            mvc.perform(get("/api/v1/content/weapons?level=3")).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
