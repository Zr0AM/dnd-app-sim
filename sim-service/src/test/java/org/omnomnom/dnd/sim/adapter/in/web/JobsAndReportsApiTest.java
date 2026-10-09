package org.omnomnom.dnd.sim.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.testsupport.OpenApiSchema;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Optimization jobs, report storage, re-ranking and annotation through the whole stack, with the filesystem store. */
@SpringBootTest
@AutoConfigureMockMvc
class JobsAndReportsApiTest {

    static final Path REPORTS;

    static {
        try {
            REPORTS = Files.createTempDirectory("sim-reports");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void reportDirectory(DynamicPropertyRegistry registry) {
        registry.add("sim.report-store.directory", REPORTS::toString);
    }

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

    private JsonNode awaitJob(String id) {
        return Awaitility.await("job " + id)
                .atMost(Duration.ofSeconds(120))
                .pollInterval(Duration.ofMillis(25))
                .until(() -> json(mvc.perform(get("/api/v1/jobs/" + id)).andExpect(status().isOk())),
                        job -> !job.get("status").asString().matches("queued|running"));
    }

    /** Wait until the clock has moved on, so reports saved before and after it have distinct, ordered timestamps. */
    private static void waitForClockToAdvance() {
        Instant later = Instant.now().plusMillis(15);
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> Instant.now().isAfter(later));
    }

    static final String TINY = """
            {"level":3,"classes":["fighter","wizard"],"ga":{"populationSize":4,"generations":2,"evalRuns":1},"seed":%d%s}""";

    private String optimize(long seed) throws Exception {
        ResultActions accepted = send("/api/v1/simulate/optimize", TINY.formatted(seed, "")).andExpect(status().isAccepted());
        JsonNode job = json(accepted);
        accepted.andExpect(header().string("Location", "/api/v1/jobs/" + job.get("id").asString()));
        OpenApiSchema.assertConforms(job, "Job");
        JsonNode done = awaitJob(job.get("id").asString());
        OpenApiSchema.assertConforms(done, "Job");
        assertThat(done.get("status").asString()).isEqualTo("succeeded");
        return done.get("reportId").asString();
    }

    @Test
    void optimizeStoresAReportThatCanBeFetched() throws Exception {
        String reportId = optimize(101);
        assertThat(reportId).matches("[0-9a-f]{8}");
        assertThat(Files.exists(REPORTS.resolve(reportId + ".json"))).isTrue();

        JsonNode report = json(mvc.perform(get("/api/v1/reports/" + reportId)).andExpect(status().isOk()));
        OpenApiSchema.assertConforms(report, "RunReport");
        assertThat(report.get("version").asInt()).isEqualTo(1);
        assertThat(report.get("runKey").asString()).isEqualTo(reportId);
        assertThat(report.get("config").get("level").asInt()).isEqualTo(3);
        assertThat(report.get("config").get("classes")).hasSize(2);
        assertThat(report.get("paretoFront")).isNotEmpty();
        assertThat(report.get("leaderboard").get(0).get("description").asString()).startsWith("L3 ");
        assertThat(report.get("leaderboard").get(0).has("campaignDayWinRate")).isFalse();
        assertThat(report.get("objectiveBounds").get("offense")).hasSize(2);
    }

    /** Re-rank without re-simulating; the stored report is untouched unless save is true. */
    @Test
    void rescoringReRanksWithoutResimulatingAndSavesOnlyWhenAsked() throws Exception {
        String reportId = optimize(103);
        JsonNode tank = json(send("/api/v1/reports/" + reportId + "/rescore", "{\"role\":\"tank\"}").andExpect(status().isOk()));
        OpenApiSchema.assertConforms(tank, "RunReport");
        assertThat(tank.get("weights").get("survival").asDouble()).isEqualTo(3.0);
        JsonNode weighted = json(send("/api/v1/reports/" + reportId + "/rescore", "{\"weights\":{\"offense\":1}}").andExpect(status().isOk()));
        assertThat(weighted.get("weights").propertyNames()).containsExactly("offense");
        JsonNode stillEqual = json(mvc.perform(get("/api/v1/reports/" + reportId)));
        assertThat(stillEqual.get("weights").get("survival").asDouble()).isEqualTo(1.0);
        send("/api/v1/reports/" + reportId + "/rescore", "{\"role\":\"tank\",\"save\":true}").andExpect(status().isOk());
        assertThat(json(mvc.perform(get("/api/v1/reports/" + reportId))).get("weights").get("survival").asDouble()).isEqualTo(3.0);
    }

    /** Annotate with adventuring days: a job, then the same report id carries the rates. */
    @Test
    void annotatingWithAdventuringDaysIsAJobThatUpdatesTheSameReport() throws Exception {
        String reportId = optimize(104);
        ResultActions accepted = send("/api/v1/reports/" + reportId + "/campaign", "{\"days\":2}").andExpect(status().isAccepted());
        JsonNode job = json(accepted);
        assertThat(job.get("kind").asString()).isEqualTo("campaign");
        JsonNode done = awaitJob(job.get("id").asString());
        assertThat(done.get("status").asString()).isEqualTo("succeeded");
        assertThat(done.get("reportId").asString()).isEqualTo(reportId);
        JsonNode annotated = json(mvc.perform(get("/api/v1/reports/" + reportId)));
        OpenApiSchema.assertConforms(annotated, "RunReport");
        assertThat(annotated.get("leaderboard").get(0).get("campaignDayWinRate").asDouble()).isBetween(0.0, 1.0);
        // The no-body form also works.
        send("/api/v1/reports/" + reportId + "/campaign", "").andExpect(status().isAccepted());
    }

    @Test
    void listingReportsNewestFirstWithPaging() throws Exception {
        String first = optimize(201);
        waitForClockToAdvance();
        String second = optimize(202);
        JsonNode page = json(mvc.perform(get("/api/v1/reports?limit=1")).andExpect(status().isOk()));
        assertThat(page.get("items")).hasSize(1);
        assertThat(page.get("items").get(0).get("id").asString()).isEqualTo(second);
        assertThat(page.get("nextCursor").isNull()).isFalse();
        JsonNode next = json(mvc.perform(get("/api/v1/reports?limit=50&cursor=" + page.get("nextCursor").asString())).andExpect(status().isOk()));
        assertThat(next.get("items").get(0).get("id").asString()).isNotEqualTo(second);
        assertThat(next.get("nextCursor").isNull()).isTrue();
        JsonNode all = json(mvc.perform(get("/api/v1/reports")).andExpect(status().isOk()));
        all.get("items").forEach(item -> OpenApiSchema.assertConforms(item, "ReportSummary"));
        assertThat(all.get("items")).extracting(n -> n.get("id").asString()).contains(first, second);
        JsonNode top = all.get("items").get(0).get("top");
        assertThat(top.get("description").asString()).startsWith("L3 ");
        assertThat(all.get("items").get(0).get("config").get("seed").asLong()).isEqualTo(202);
        mvc.perform(get("/api/v1/reports?limit=0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/reports?cursor=abc")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-request"))
                .andExpect(jsonPath("$.errors[0].field").value("cursor"))
                .andExpect(jsonPath("$.errors[0].code").value("invalid-cursor"));
        mvc.perform(get("/api/v1/reports?limit=201")).andExpect(status().isBadRequest());
    }

    @Test
    void jobProblemsAndCancellation() throws Exception {
        // A long run is cancelled while running (or, if it has not started, while queued).
        JsonNode job = json(send("/api/v1/simulate/optimize",
                "{\"level\":3,\"ga\":{\"populationSize\":16,\"generations\":100,\"evalRuns\":4},\"seed\":7}").andExpect(status().isAccepted()));
        String id = job.get("id").asString();
        JsonNode cancel = json(mvc.perform(delete("/api/v1/jobs/" + id)).andExpect(status().isOk()));
        assertThat(cancel.get("status").asString()).isIn("queued", "running", "cancelled");
        JsonNode done = awaitJob(id);
        assertThat(done.get("status").asString()).isEqualTo("cancelled");
        assertThat(done.has("reportId")).isFalse();
        mvc.perform(delete("/api/v1/jobs/" + id)).andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("job-finished"));
        mvc.perform(get("/api/v1/jobs/nope")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("job-not-found"));
        mvc.perform(delete("/api/v1/jobs/nope")).andExpect(status().isNotFound());
    }

    @Test
    void missingReportsAre404AndBadIdsAre400() throws Exception {
        mvc.perform(get("/api/v1/reports/deadbeef")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("report-not-found"));
        mvc.perform(get("/api/v1/reports/NOT-AN-ID")).andExpect(status().isBadRequest());
        send("/api/v1/reports/deadbeef/rescore", "{\"role\":\"tank\"}").andExpect(status().isNotFound());
        send("/api/v1/reports/deadbeef/campaign", "{}").andExpect(status().isNotFound());
    }

    @Test
    void rescoreValidation() throws Exception {
        String reportId = optimize(301);
        String url = "/api/v1/reports/" + reportId + "/rescore";
        send(url, "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("invalid-weighting"));
        send(url, "{\"role\":\"tank\",\"weights\":{\"offense\":1}}").andExpect(status().isBadRequest());
        send(url, "{\"role\":\"jester\"}").andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("unknown-role"));
        send(url, "{\"weights\":{\"charisma\":1}}").andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("unknown-objective"));
        send(url, "{\"weights\":{\"offense\":-1}}").andExpect(status().isBadRequest());
        send(url, "{\"role\":\"equal\"}").andExpect(status().isOk());
    }

    @Test
    void optimizeValidation() throws Exception {
        send("/api/v1/simulate/optimize", "{\"level\":4}").andExpect(status().isBadRequest());
        send("/api/v1/simulate/optimize", "{\"level\":3,\"ga\":{\"populationSize\":1}}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("ga.populationSize"));
        send("/api/v1/simulate/optimize", "{\"level\":3,\"ga\":{\"generations\":101}}").andExpect(status().isBadRequest());
        send("/api/v1/simulate/optimize", "{\"level\":3,\"preset\":\"ludicrous\"}").andExpect(status().isBadRequest());
        send("/api/v1/simulate/optimize", "{\"level\":3,\"classes\":[\"jester\"]}").andExpect(status().isBadRequest());
        send("/api/v1/simulate/optimize", "{\"level\":3,\"context\":\"party\"}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("unsupported-context"));
        send("/api/v1/simulate/optimize", "{\"level\":3,\"role\":\"jester\"}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("unknown-role"));
    }
}
