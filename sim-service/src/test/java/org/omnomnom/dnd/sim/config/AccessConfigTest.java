package org.omnomnom.dnd.sim.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.adapter.in.web.security.AccessFilter;
import org.omnomnom.dnd.sim.adapter.in.web.security.RateLimitInterceptor;
import org.omnomnom.dnd.sim.application.access.RateLimiter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class AccessConfigTest {

    private final AccessConfig config = new AccessConfig();

    private static SimProperties props(SimProperties.SecurityMode mode, List<String> keys) {
        return new SimProperties(
                new SimProperties.ReportStore(SimProperties.StoreType.FILESYSTEM, "out/test-reports"),
                new SimProperties.D1(false, "https://api.cloudflare.com/client/v4", null, null, null, Duration.ofSeconds(5)),
                new SimProperties.Executor(1, 1, Duration.ofSeconds(5)),
                new SimProperties.Jobs(200, 12, false),
                new SimProperties.Limits(2000, 200, 100, 128, 100, 64, 2_000_000L),
                new SimProperties.Security(mode, keys),
                new SimProperties.RateLimit(true, 600, 60, 10_000, Duration.ofMinutes(10)));
    }

    @Test
    void theServiceDoesNotStartOpenByForgettingAKey() {
        SimProperties noKeys = props(SimProperties.SecurityMode.API_KEY, List.of());
        SimProperties blankKeys = props(SimProperties.SecurityMode.API_KEY, java.util.Arrays.asList(" ", "", null));
        assertThatThrownBy(() -> config.accessFilter(noKeys))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SIM_API_KEYS");
        assertThatThrownBy(() -> config.accessFilter(blankKeys)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keysOrAnExplicitOptOutLetItStart() {
        assertThat(config.accessFilter(props(SimProperties.SecurityMode.API_KEY, List.of("k")))).isNotNull();
        assertThat(config.accessFilter(props(SimProperties.SecurityMode.NONE, List.of()))).isNotNull();
    }

    @Test
    void rateLimitingIsOnUnlessDisabled() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(Properties.class, AccessConfig.class)
                .withBean(Clock.class, Clock::systemUTC)
                .withSystemProperties("sim.rate-limit.enabled") // the test task turns it off for every other test
                .withPropertyValues("sim.security.mode=none");
        runner.run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(AccessFilter.class).hasSingleBean(RateLimiter.class)
                .hasSingleBean(RateLimitInterceptor.class));
        runner.withPropertyValues("sim.rate-limit.enabled=false").run(ctx -> assertThat(ctx).hasNotFailed()
                .hasSingleBean(AccessFilter.class).doesNotHaveBean(RateLimiter.class).doesNotHaveBean(RateLimitInterceptor.class));
    }

    @Configuration
    @EnableConfigurationProperties(SimProperties.class)
    static class Properties {}

    @Test
    void theDefaultPropertiesAreClosedAndLimited() {
        SimProperties.Security security = new SimProperties.Security(SimProperties.SecurityMode.API_KEY, null);
        assertThat(security.apiKeys()).isEmpty();
        assertThat(new SimProperties.Security(SimProperties.SecurityMode.API_KEY, java.util.Arrays.asList("a", " ", null, "b")).apiKeys())
                .containsExactly("a", "b");
    }
}
