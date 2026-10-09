package org.omnomnom.dnd.sim.config;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import org.omnomnom.dnd.sim.adapter.out.report.D1Client;
import org.omnomnom.dnd.sim.adapter.out.report.D1ReportStore;
import org.omnomnom.dnd.sim.adapter.out.report.FilesystemReportStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Chooses where reports are persisted with {@code sim.report-store.type}: a directory (the default, and the only choice in
 * the {@code local} profile) or Cloudflare D1 over its HTTP query API. Only the chosen store's beans exist. Choosing D1
 * without credentials fails at startup rather than at the first save.
 */
@Configuration
class ReportStoreConfig {

    static final String TYPE = "sim.report-store.type";

    @Configuration
    @ConditionalOnProperty(name = TYPE, havingValue = "filesystem", matchIfMissing = true)
    static class Filesystem {

        @Bean
        FilesystemReportStore reportStore(SimProperties props, ObjectMapper mapper) {
            return new FilesystemReportStore(Path.of(props.reportStore().directory()), mapper);
        }
    }

    @Configuration
    @ConditionalOnProperty(name = TYPE, havingValue = "d1")
    static class D1 {

        /** Checked first, so a missing credential stops startup before any client is built. */
        @Bean
        SimProperties.D1 d1Settings(SimProperties props) {
            SimProperties.D1 d1 = props.d1();
            if (!d1.enabled() || !d1.isConfigured()) {
                throw new IllegalStateException("sim.report-store.type=d1 needs sim.d1.enabled=true and the environment variables "
                        + "CF_ACCOUNT_ID, CF_D1_DATABASE_ID and CF_API_TOKEN");
            }
            return d1;
        }

        /** Not a default candidate: it is the D1 store's own connection, not a shared HTTP client. Closed with the context. */
        @Bean(defaultCandidate = false)
        HttpClient d1HttpClient(SimProperties.D1 d1) {
            return HttpClient.newBuilder().connectTimeout(d1.timeout()).build();
        }

        @Bean
        D1Client d1Client(@Qualifier("d1HttpClient") HttpClient http, SimProperties.D1 d1, ObjectMapper mapper) {
            return new D1Client(http, d1.baseUrl(), d1.accountId(), d1.databaseId(), d1.apiToken(), d1.timeout(), mapper);
        }

        @Bean
        D1ReportStore reportStore(D1Client client, ObjectMapper mapper, Clock clock) {
            return new D1ReportStore(client, mapper, clock);
        }
    }

}
