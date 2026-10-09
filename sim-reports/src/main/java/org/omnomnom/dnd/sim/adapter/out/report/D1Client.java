package org.omnomnom.dnd.sim.adapter.out.report;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A minimal client for Cloudflare D1's HTTP query API. D1 has no JDBC driver or wire protocol: a statement and its bound
 * parameters are POSTed as JSON and the rows come back as JSON objects. The token is sent as a bearer credential and
 * never logged.
 */
public final class D1Client {

    /** A failed call: a transport problem, a non-2xx status, or {@code success: false} in the response. */
    public static final class D1Exception extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public D1Exception(String message, Throwable cause) {
            super(message, cause);
        }

        public D1Exception(String message) {
            super(message);
        }
    }

    private final HttpClient http;
    private final URI queryUri;
    private final String token;
    private final Duration timeout;
    private final ObjectMapper mapper;

    /**
     * @param baseUrl for example {@code https://api.cloudflare.com/client/v4}
     */
    public D1Client(HttpClient http, String baseUrl, String accountId, String databaseId, String token, Duration timeout, ObjectMapper mapper) {
        this.http = http;
        this.queryUri = URI.create(baseUrl + "/accounts/" + accountId + "/d1/database/" + databaseId + "/query");
        this.token = token;
        this.timeout = timeout;
        this.mapper = mapper;
    }

    /** Run one statement with positional parameters; returns the result rows (empty for statements that return none). */
    public List<JsonNode> query(String sql, List<Object> params) {
        ObjectNode body = mapper.createObjectNode();
        body.put("sql", sql);
        var array = body.putArray("params");
        for (Object p : params) {
            switch (p) {
                case null -> array.addNull();
                case String s -> array.add(s);
                case Integer i -> array.add(i);
                case Long l -> array.add(l);
                case Double d -> array.add(d);
                default -> throw new IllegalArgumentException("unsupported parameter type: " + p.getClass());
            }
        }
        HttpRequest request = HttpRequest.newBuilder(queryUri)
                .timeout(timeout)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new D1Exception("D1 request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new D1Exception("interrupted while calling D1", e);
        }
        JsonNode root;
        try {
            root = mapper.readTree(response.body());
        } catch (RuntimeException e) {
            throw new D1Exception("D1 returned status " + response.statusCode() + " with an unreadable body", e);
        }
        if (response.statusCode() / 100 != 2 || !root.path("success").asBoolean(false)) {
            throw new D1Exception("D1 returned status " + response.statusCode() + ": " + errorMessages(root));
        }
        List<JsonNode> rows = new ArrayList<>();
        JsonNode results = root.path("result").path(0).path("results");
        results.forEach(rows::add);
        return rows;
    }

    private static String errorMessages(JsonNode root) {
        List<String> messages = new ArrayList<>();
        root.path("errors").forEach(e -> messages.add(e.path("message").asString("unknown error")));
        return messages.isEmpty() ? "no error detail" : String.join("; ", messages);
    }
}
