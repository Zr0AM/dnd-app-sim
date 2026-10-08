package org.omnomnom.dnd.sim.config;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import org.omnomnom.dnd.sim.adapter.out.SimProperties;
import org.omnomnom.dnd.sim.adapter.out.report.D1Client;
import org.omnomnom.dnd.sim.adapter.out.report.D1ReportStore;
import org.omnomnom.dnd.sim.adapter.out.report.FilesystemReportStore;
import org.omnomnom.dnd.sim.application.ReportStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Chooses where reports are persisted: a directory (the default, and the only choice in the {@code local} profile) or
 * Cloudflare D1 over its HTTP query API. Choosing D1 without credentials fails at startup rather than at the first save.
 */
@Configuration
class ReportStoreConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ReportStore reportStore(SimProperties props, ObjectMapper mapper, Clock clock) {
        SimProperties.ReportStore store = props.reportStore();
        if (store.type() == SimProperties.StoreType.D1) {
            SimProperties.D1 d1 = props.d1();
            if (!d1.enabled() || !d1.isConfigured()) {
                throw new IllegalStateException("sim.report-store.type=d1 needs sim.d1.enabled=true and the environment variables "
                        + "CF_ACCOUNT_ID, CF_D1_DATABASE_ID and CF_API_TOKEN");
            }
            HttpClient http = HttpClient.newBuilder().connectTimeout(d1.timeout()).build();
            return new D1ReportStore(new D1Client(http, d1.baseUrl(), d1.accountId(), d1.databaseId(), d1.apiToken(), d1.timeout(), mapper),
                    mapper, clock);
        }
        return new FilesystemReportStore(Path.of(store.directory()), mapper);
    }
}
