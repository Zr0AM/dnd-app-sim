package org.omnomnom.dnd.sim.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.adapter.out.SimProperties;

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
                new SimProperties.RateLimit(true, 600, 60));
    }

    @Test
    void theServiceDoesNotStartOpenByForgettingAKey() {
        assertThatThrownBy(() -> config.accessFilter(props(SimProperties.SecurityMode.API_KEY, List.of()), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SIM_API_KEYS");
        assertThatThrownBy(() -> config.accessFilter(props(SimProperties.SecurityMode.API_KEY, java.util.Arrays.asList(" ", "", null)), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keysOrAnExplicitOptOutLetItStart() {
        assertThat(config.accessFilter(props(SimProperties.SecurityMode.API_KEY, List.of("k")), Clock.systemUTC())).isNotNull();
        assertThat(config.accessFilter(props(SimProperties.SecurityMode.NONE, List.of()), Clock.systemUTC())).isNotNull();
    }

    @Test
    void theDefaultPropertiesAreClosedAndLimited() {
        SimProperties.Security security = new SimProperties.Security(SimProperties.SecurityMode.API_KEY, null);
        assertThat(security.apiKeys()).isEmpty();
        assertThat(new SimProperties.Security(SimProperties.SecurityMode.API_KEY, java.util.Arrays.asList("a", " ", null, "b")).apiKeys())
                .containsExactly("a", "b");
    }
}
