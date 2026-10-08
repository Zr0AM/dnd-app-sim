package org.omnomnom.dnd.sim.adapter.out;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Externalized settings for the outbound adapters and the simulation executor. */
@ConfigurationProperties(prefix = "sim")
public record SimProperties(
        @DefaultValue ReportStore reportStore, @DefaultValue D1 d1, @DefaultValue Executor executor) {

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

    public record Executor(@DefaultValue("0") int threads, @DefaultValue("64") int queueCapacity) {}
}
