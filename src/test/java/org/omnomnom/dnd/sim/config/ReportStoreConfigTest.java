package org.omnomnom.dnd.sim.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.adapter.out.report.D1Client;
import org.omnomnom.dnd.sim.adapter.out.report.D1ReportStore;
import org.omnomnom.dnd.sim.adapter.out.report.FilesystemReportStore;
import org.omnomnom.dnd.sim.application.report.ReportStore;
import org.omnomnom.dnd.sim.testsupport.TestReports;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

class ReportStoreConfigTest {

    @Configuration
    @EnableConfigurationProperties(SimProperties.class)
    static class Properties {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Properties.class, ReportStoreConfig.class)
            .withBean(ObjectMapper.class, TestReports::mapper)
            .withBean(Clock.class, Clock::systemUTC)
            .withPropertyValues("sim.report-store.directory=out/test-reports");

    @Test
    void filesystemIsTheDefaultAndNeedsNoCredentials() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(ReportStore.class);
            assertThat(ctx.getBean(ReportStore.class)).isInstanceOf(FilesystemReportStore.class);
            assertThat(ctx).doesNotHaveBean(D1Client.class).doesNotHaveBean(HttpClient.class);
        });
        runner.withPropertyValues("sim.report-store.type=filesystem")
                .run(ctx -> assertThat(ctx.getBean(ReportStore.class)).isInstanceOf(FilesystemReportStore.class));
    }

    @Test
    void d1NeedsItsFlagAndAllThreeCredentials() {
        String[][] incomplete = {
            {"sim.d1.enabled=false", "sim.d1.account-id=a", "sim.d1.database-id=d", "sim.d1.api-token=t"},
            {"sim.d1.enabled=true", "sim.d1.account-id=", "sim.d1.database-id=d", "sim.d1.api-token=t"},
            {"sim.d1.enabled=true", "sim.d1.account-id=a", "sim.d1.api-token=t"},
            {"sim.d1.enabled=true", "sim.d1.account-id=a", "sim.d1.database-id=d", "sim.d1.api-token= "}
        };
        for (String[] settings : incomplete) {
            runner.withPropertyValues("sim.report-store.type=d1").withPropertyValues(settings).run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("CF_API_TOKEN")
                        .hasMessageNotContaining("secret");
            });
        }
    }

    @Test
    void d1IsUsedWhenConfiguredAndOwnsItsHttpClient() {
        runner.withPropertyValues("sim.report-store.type=d1", "sim.d1.enabled=true", "sim.d1.account-id=acct",
                "sim.d1.database-id=db", "sim.d1.api-token=secret").run(ctx -> {
                    assertThat(ctx).hasNotFailed().hasSingleBean(ReportStore.class).hasSingleBean(D1Client.class);
                    assertThat(ctx.getBean(ReportStore.class)).isInstanceOf(D1ReportStore.class);
                    assertThat(((AbstractBeanDefinition) ctx.getBeanFactory().getBeanDefinition("d1HttpClient")).isDefaultCandidate())
                            .as("the store's own client, not offered for autowiring").isFalse();
                });
    }
}
