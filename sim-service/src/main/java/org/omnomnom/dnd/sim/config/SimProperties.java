package org.omnomnom.dnd.sim.config;

import java.time.Duration;
import java.util.List;
import org.omnomnom.dnd.sim.application.report.ReportStore;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Externalized settings for the outbound adapters and the simulation executor. */
@ConfigurationProperties(prefix = "sim")
public record SimProperties(
        @DefaultValue ReportStore reportStore,
        @DefaultValue D1 d1,
        @DefaultValue Executor executor,
        @DefaultValue Jobs jobs,
        @DefaultValue Limits limits,
        @DefaultValue Security security,
        @DefaultValue RateLimit rateLimit) {

    public enum StoreType {
        FILESYSTEM,
        D1
    }

    public record ReportStore(
            @DefaultValue("filesystem") StoreType type, @DefaultValue("out/reports") String directory) {}

    public record D1(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("https://api.cloudflare.com/client/v4") String baseUrl,
            String accountId,
            String databaseId,
            String apiToken,
            @DefaultValue("30s") Duration timeout) {

        /** True when every credential needed to call the D1 query API is present. */
        public boolean isConfigured() {
            return notBlank(accountId) && notBlank(databaseId) && notBlank(apiToken);
        }

        private static boolean notBlank(String s) {
            return s != null && !s.isBlank();
        }
    }

    /**
     * @param busyRetryAfter the {@code Retry-After} given when the queue is full
     */
    public record Executor(@DefaultValue("0") int threads, @DefaultValue("64") int queueCapacity, @DefaultValue("5s") Duration busyRetryAfter) {}

    /**
     * @param retainedFinished finished jobs remembered for polling before the oldest are forgotten
     * @param campaignDays simulated days for an optimization's campaign pass and the default for report annotation
     * @param exposeErrorDetail show a failed job's exception message to API clients (off: it may name internal paths)
     */
    public record Jobs(
            @DefaultValue("200") int retainedFinished,
            @DefaultValue("12") int campaignDays,
            @DefaultValue("false") boolean exposeErrorDetail) {}

    /** Ceilings on how much work one request may ask for; see {@code SimLimits}. */
    public record Limits(
            @DefaultValue("2000") int encounterRuns,
            @DefaultValue("200") int evalRunsPerScenario,
            @DefaultValue("100") int campaignDays,
            @DefaultValue("128") int optimizePopulation,
            @DefaultValue("100") int optimizeGenerations,
            @DefaultValue("64") int optimizeEvalRuns,
            @DefaultValue("2000000") long optimizeMaxFights) {}

    public enum SecurityMode {
        /** Every request except health and info needs a configured API key. */
        API_KEY,
        /** No authentication; for local development only. */
        NONE
    }

    /**
     * @param mode defaults to requiring an API key, so a deployment is closed unless it is told otherwise
     * @param apiKeys the accepted keys (environment {@code SIM_API_KEYS}, comma separated); never logged
     */
    public record Security(@DefaultValue("api-key") SecurityMode mode, List<String> apiKeys) {

        public Security {
            apiKeys = apiKeys == null ? List.of() : apiKeys.stream().filter(k -> k != null && !k.isBlank()).toList();
        }
    }

    /**
     * Per-client request budgets, a token bucket refilled continuously and holding one minute's worth.
     *
     * @param requestsPerMinute for ordinary requests (reads, rescoring, job polling)
     * @param simulationsPerMinute for the expensive endpoints (simulate/*, report campaign)
     * @param maxClients clients tracked before idle (then least recently seen) ones are forgotten; bounds memory
     * @param idleTimeout how long a client must be silent to count as idle
     */
    public record RateLimit(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("600") int requestsPerMinute,
            @DefaultValue("60") int simulationsPerMinute,
            @DefaultValue("10000") int maxClients,
            @DefaultValue("10m") Duration idleTimeout) {}
}
