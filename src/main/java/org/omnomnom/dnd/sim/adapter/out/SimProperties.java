package org.omnomnom.dnd.sim.adapter.out;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Externalized settings for the outbound adapters and the simulation executor. */
@ConfigurationProperties(prefix = "sim")
public record SimProperties(
        @DefaultValue ReportStore reportStore, @DefaultValue D1 d1, @DefaultValue Executor executor, @DefaultValue Jobs jobs) {

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
}
