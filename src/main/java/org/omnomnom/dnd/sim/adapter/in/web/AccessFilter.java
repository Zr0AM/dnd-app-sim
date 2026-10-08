package org.omnomnom.dnd.sim.adapter.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.omnomnom.dnd.sim.application.RateLimiter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates by API key and applies the per-client rate limit, before any controller runs.
 *
 * <p>A key is read from {@code X-API-Key} or {@code Authorization: Bearer <key>} and compared by digest in constant time.
 * Health and info are always open. Rejections are {@code application/problem+json}. The client identity used for rate
 * limiting is a short digest of the key (never the key), or the remote address when authentication is off.
 */
public final class AccessFilter extends OncePerRequestFilter {

    /** Authentication settings: keys are held only as SHA-256 digests. */
    public static final class Keys {
        private final List<byte[]> digests;

        public Keys(List<String> keys) {
            this.digests = keys.stream().map(AccessFilter::sha256).toList();
        }

        boolean any() {
            return !digests.isEmpty();
        }

        boolean matches(byte[] candidate) {
            boolean found = false;
            for (byte[] d : digests) {
                found |= MessageDigest.isEqual(d, candidate); // no early exit: the time does not reveal which key matched
            }
            return found;
        }
    }

    private final boolean authenticate;
    private final Keys keys;
    private final RateLimiter limiter;

    /**
     * @param authenticate require an API key
     * @param limiter the rate limiter, or null to leave requests unlimited
     */
    public AccessFilter(boolean authenticate, Keys keys, RateLimiter limiter) {
        this.authenticate = authenticate;
        this.keys = keys;
        this.limiter = limiter;
    }

    static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/actuator/health") || path.startsWith("/actuator/health/") || path.equals("/actuator/info");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String client = request.getRemoteAddr();
        if (authenticate) {
            String presented = presentedKey(request);
            byte[] digest = presented == null ? null : sha256(presented);
            if (digest == null || !keys.matches(digest)) {
                response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                problem(response, 401, "unauthorized", "Unauthorized", "A valid API key is required (X-API-Key or Authorization: Bearer).");
                return;
            }
            client = "key:" + HexFormat.of().formatHex(digest, 0, 4);
        }
        if (limiter != null) {
            RateLimiter.Decision decision = limiter.tryAcquire(client, costOf(request));
            if (!decision.allowed()) {
                response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
                problem(response, 429, "rate-limited", "Too many requests", "The request budget is spent; retry after " + decision.retryAfterSeconds() + " s.");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static String presentedKey(HttpServletRequest request) {
        String header = request.getHeader("X-API-Key");
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = auth.substring(7).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }

    /** The expensive endpoints draw on the simulation budget: everything under simulate/ and a report's campaign job. */
    static RateLimiter.Cost costOf(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean post = HttpMethod.POST.matches(request.getMethod());
        if (post && (path.startsWith("/api/v1/simulate/") || (path.startsWith("/api/v1/reports/") && path.endsWith("/campaign")))) {
            return RateLimiter.Cost.SIMULATION;
        }
        return RateLimiter.Cost.ORDINARY;
    }

    private static void problem(HttpServletResponse response, int status, String code, String title, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"type\":\"urn:dnd-app-sim:problem:" + code + "\",\"title\":\"" + title + "\",\"status\":" + status
                + ",\"detail\":\"" + detail.replace("\"", "'") + "\",\"code\":\"" + code + "\"}");
    }
}
