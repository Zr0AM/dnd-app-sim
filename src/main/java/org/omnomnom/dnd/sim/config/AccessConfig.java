package org.omnomnom.dnd.sim.config;

import java.time.Clock;
import org.omnomnom.dnd.sim.adapter.in.web.AccessFilter;
import org.omnomnom.dnd.sim.adapter.out.SimProperties;
import org.omnomnom.dnd.sim.application.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Wires authentication and rate limiting. The service is closed by default: unless {@code sim.security.mode=none} (the
 * {@code local} profile), at least one API key must be configured or startup fails, so a deployment cannot come up open by
 * forgetting a setting.
 */
@Configuration
class AccessConfig {

    private static final Logger LOG = LoggerFactory.getLogger(AccessConfig.class);

    @Bean
    FilterRegistrationBean<AccessFilter> accessFilter(SimProperties props, Clock clock) {
        boolean authenticate = props.security().mode() == SimProperties.SecurityMode.API_KEY;
        if (authenticate && props.security().apiKeys().isEmpty()) {
            throw new IllegalStateException("sim.security.mode is api-key but no keys are configured; set SIM_API_KEYS "
                    + "(comma separated) or, for local development only, use the 'local' profile or sim.security.mode=none");
        }
        if (!authenticate) {
            LOG.warn("API authentication is OFF (sim.security.mode=none); do not expose this instance beyond localhost");
        }
        SimProperties.RateLimit rl = props.rateLimit();
        RateLimiter limiter = rl.enabled() ? new RateLimiter(clock, rl.requestsPerMinute(), rl.simulationsPerMinute()) : null;
        FilterRegistrationBean<AccessFilter> bean = new FilterRegistrationBean<>(
                new AccessFilter(authenticate, new AccessFilter.Keys(props.security().apiKeys()), limiter));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        bean.addUrlPatterns("/*");
        return bean;
    }
}
