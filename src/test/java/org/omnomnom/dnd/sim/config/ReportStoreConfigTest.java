package org.omnomnom.dnd.sim.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.adapter.out.report.D1ReportStore;
import org.omnomnom.dnd.sim.adapter.out.report.FilesystemReportStore;
import org.omnomnom.dnd.sim.application.report.ReportStore;
import org.omnomnom.dnd.sim.testsupport.TestReports;

class ReportStoreConfigTest {

    private final ReportStoreConfig config = new ReportStoreConfig();

    private static SimProperties props(SimProperties.StoreType type, boolean d1Enabled, String account, String database, String token) {
        return new SimProperties(
                new SimProperties.ReportStore(type, "out/test-reports"),
                new SimProperties.D1(d1Enabled, "https://api.cloudflare.com/client/v4", account, database, token, Duration.ofSeconds(5)),
                new SimProperties.Executor(1, 1, Duration.ofSeconds(5)),
                new SimProperties.Jobs(200, 12, false),
                new SimProperties.Limits(2000, 200, 100, 128, 100, 64, 2_000_000L),
                new SimProperties.Security(SimProperties.SecurityMode.NONE, null),
                new SimProperties.RateLimit(false, 600, 60, 10_000, Duration.ofMinutes(10)));
    }

    @Test
    void filesystemIsTheDefaultAndNeedsNoCredentials() {
        assertThat(config.reportStore(props(SimProperties.StoreType.FILESYSTEM, false, null, null, null), TestReports.mapper(), Clock.systemUTC()))
                .isInstanceOf(FilesystemReportStore.class);
    }

    @Test
    void d1NeedsItsFlagAndAllThreeCredentials() {
        for (SimProperties p : new SimProperties[] {
            props(SimProperties.StoreType.D1, false, "a", "d", "t"),
            props(SimProperties.StoreType.D1, true, "", "d", "t"),
            props(SimProperties.StoreType.D1, true, "a", null, "t"),
            props(SimProperties.StoreType.D1, true, "a", "d", " ")
        }) {
            assertThatThrownBy(() -> config.reportStore(p, TestReports.mapper(), Clock.systemUTC()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CF_API_TOKEN")
                    .hasMessageNotContaining("secret");
        }
    }

    @Test
    void d1IsUsedWhenConfigured() {
        assertThat(config.reportStore(props(SimProperties.StoreType.D1, true, "acct", "db", "secret"), TestReports.mapper(), Clock.systemUTC()))
                .isInstanceOf(D1ReportStore.class);
    }
}
