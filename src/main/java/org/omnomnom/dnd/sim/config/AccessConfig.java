package org.omnomnom.dnd.sim.config;

import java.time.Clock;
import org.omnomnom.dnd.sim.adapter.in.web.security.AccessFilter;
import org.omnomnom.dnd.sim.adapter.in.web.security.RateLimitInterceptor;
import org.omnomnom.dnd.sim.application.access.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires authentication (a servlet filter, before routing) and rate limiting (an interceptor, after routing). The service is
 * closed by default: unless {@code sim.security.mode=none} (the {@code local} profile), at least one API key must be
 * configured or startup fails, so a deployment cannot come up open by forgetting a setting.
 */
@Configuration
class AccessConfig {

    private static final Logger LOG = LoggerFactory.getLogger(AccessConfig.class);

    @Bean
    AccessFilter accessFilter(SimProperties props) {
        boolean authenticate = props.security().mode() == SimProperties.SecurityMode.API_KEY;
        if (authenticate && props.security().apiKeys().isEmpty()) {
            throw new IllegalStateException("sim.security.mode is api-key but no keys are configured; set SIM_API_KEYS "
                    + "(comma separated) or, for local development only, use the 'local' profile or sim.security.mode=none");
        }
        if (!authenticate) {
            LOG.warn("API authentication is OFF (sim.security.mode=none); do not expose this instance beyond localhost");
        }
        return new AccessFilter(authenticate, new AccessFilter.Keys(props.security().apiKeys()));
    }

    /** Registers the filter ahead of the rest of the chain; Boot then does not register the filter bean a second time. */
    @Bean
    FilterRegistrationBean<AccessFilter> accessFilterRegistration(AccessFilter filter) {
        FilterRegistrationBean<AccessFilter> bean = new FilterRegistrationBean<>(filter);
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        bean.addUrlPatterns("/*");
        return bean;
    }

    /** Present unless {@code sim.rate-limit.enabled=false}; the limiter runs after routing, so the endpoint decides the budget. */
    @Configuration
    @ConditionalOnBooleanProperty(name = "sim.rate-limit.enabled", matchIfMissing = true)
    static class RateLimiting {

        @Bean
        RateLimiter rateLimiter(SimProperties props, Clock clock) {
            SimProperties.RateLimit rl = props.rateLimit();
            return new RateLimiter(clock, rl.requestsPerMinute(), rl.simulationsPerMinute(), rl.maxClients(), rl.idleTimeout());
        }

        @Bean
        RateLimitInterceptor rateLimitInterceptor(RateLimiter limiter) {
            return new RateLimitInterceptor(limiter);
        }

        @Bean
        WebMvcConfigurer rateLimiting(RateLimitInterceptor interceptor) {
            return new WebMvcConfigurer() {
                @Override
                public void addInterceptors(InterceptorRegistry registry) {
                    registry.addInterceptor(interceptor).order(Ordered.HIGHEST_PRECEDENCE);
                }
            };
        }
    }
}
