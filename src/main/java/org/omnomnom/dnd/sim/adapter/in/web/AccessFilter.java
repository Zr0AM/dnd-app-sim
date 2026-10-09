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
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates by API key before any controller runs, and names the client for the rate limiter.
 *
 * <p>A key is read from {@code X-API-Key} or {@code Authorization: Bearer <key>} and compared by digest in constant time.
 * Health and info are always open. Rejections are {@code application/problem+json}. The client identity, stored in the
 * request attribute {@link #CLIENT}, is a short digest of the key (never the key), or the remote address when
 * authentication is off. Rate limiting itself happens in {@link RateLimitInterceptor}, after the request is routed, so
 * the cost is decided by the endpoint that will actually run and not by the shape of the URL.
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

    /** The request attribute naming the client, for the rate limiter. */
    public static final String CLIENT = AccessFilter.class.getName() + ".client";

    private final boolean authenticate;
    private final Keys keys;

    /**
     * @param authenticate require an API key
     */
    public AccessFilter(boolean authenticate, Keys keys) {
        this.authenticate = authenticate;
        this.keys = keys;
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
        request.setAttribute(CLIENT, client);
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

    static void problem(HttpServletResponse response, int status, String code, String title, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"type\":\"urn:dnd-app-sim:problem:" + code + "\",\"title\":\"" + title + "\",\"status\":" + status
                + ",\"detail\":\"" + detail.replace("\"", "'") + "\",\"code\":\"" + code + "\"}");
    }
}
