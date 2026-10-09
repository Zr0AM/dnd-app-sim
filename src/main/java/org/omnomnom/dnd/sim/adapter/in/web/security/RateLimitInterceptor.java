package org.omnomnom.dnd.sim.adapter.in.web.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.omnomnom.dnd.sim.application.access.RateLimiter;
import org.springframework.http.HttpHeaders;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies the per-client rate limit after routing. Endpoints marked {@link Expensive} draw on the simulation budget and
 * everything else on the ordinary one, so neither path tricks (matrix parameters, percent-escapes) nor new routes can
 * move a simulation onto the cheaper budget: the cost belongs to the handler that runs.
 */
public final class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter limiter;

    public RateLimitInterceptor(RateLimiter limiter) {
        this.limiter = limiter;
    }

    static RateLimiter.Cost costOf(Object handler) {
        return handler instanceof HandlerMethod m && m.hasMethodAnnotation(Expensive.class) ? RateLimiter.Cost.SIMULATION : RateLimiter.Cost.ORDINARY;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        Object attribute = request.getAttribute(AccessFilter.CLIENT);
        String client = attribute != null ? attribute.toString() : request.getRemoteAddr();
        RateLimiter.Decision decision = limiter.tryAcquire(client, costOf(handler));
        if (decision.allowed()) {
            return true;
        }
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
        AccessFilter.problem(response, 429, "rate-limited", "Too many requests",
                "The request budget is spent; retry after " + decision.retryAfterSeconds() + " s.");
        return false;
    }
}
