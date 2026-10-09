package org.omnomnom.dnd.sim.adapter.out.report;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * An in-JVM stand-in for Cloudflare D1's HTTP query API, backed by a real SQLite database (D1 is SQLite), so the
 * statements the service sends are executed by a real SQL engine. It checks the account, database and bearer token and
 * can be told to fail.
 */
final class FakeD1Server implements AutoCloseable {

    final String accountId = "acct123";
    final String databaseId = "db456";
    final String token = "secret-token";

    private final ObjectMapper mapper = org.omnomnom.dnd.sim.testsupport.TestMapper.mapper();
    private final HttpServer server;
    private final Connection db;

    /** Every statement received, in order. */
    final List<String> statements = new CopyOnWriteArrayList<>();
    volatile int failWithStatus = 0;
    volatile boolean reportFailureInBody = false;

    FakeD1Server() throws IOException, SQLException {
        db = DriverManager.getConnection("jdbc:sqlite::memory:");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/accounts/" + accountId + "/d1/database/" + databaseId + "/query", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            if (!("Bearer " + token).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                respond(ex, 403, "{\"success\":false,\"errors\":[{\"code\":10000,\"message\":\"Authentication error\"}],\"result\":[]}");
                return;
            }
            JsonNode req = mapper.readTree(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String sql = req.get("sql").asString();
            statements.add(sql);
            if (failWithStatus != 0) {
                respond(ex, failWithStatus, "{\"success\":false,\"errors\":[{\"code\":7500,\"message\":\"forced failure\"}],\"result\":[]}");
                return;
            }
            if (reportFailureInBody) {
                respond(ex, 200, "{\"success\":false,\"errors\":[{\"code\":7500,\"message\":\"body failure\"}],\"result\":[]}");
                return;
            }
            try (PreparedStatement ps = db.prepareStatement(sql)) {
                JsonNode params = req.path("params");
                for (int i = 0; i < params.size(); i++) {
                    JsonNode p = params.get(i);
                    if (p.isNull()) {
                        ps.setObject(i + 1, null);
                    } else if (p.isIntegralNumber()) {
                        ps.setLong(i + 1, p.asLong());
                    } else if (p.isNumber()) {
                        ps.setDouble(i + 1, p.asDouble());
                    } else {
                        ps.setString(i + 1, p.asString());
                    }
                }
                ObjectNode result = mapper.createObjectNode();
                ArrayNode rows = result.putArray("results");
                if (ps.execute()) {
                    try (ResultSet rs = ps.getResultSet()) {
                        ResultSetMetaData md = rs.getMetaData();
                        while (rs.next()) {
                            ObjectNode row = rows.addObject();
                            for (int c = 1; c <= md.getColumnCount(); c++) {
                                Object v = rs.getObject(c);
                                switch (v) {
                                    case null -> row.putNull(md.getColumnLabel(c));
                                    case Integer i -> row.put(md.getColumnLabel(c), i);
                                    case Long l -> row.put(md.getColumnLabel(c), l);
                                    case Double d -> row.put(md.getColumnLabel(c), d);
                                    default -> row.put(md.getColumnLabel(c), v.toString());
                                }
                            }
                        }
                    }
                }
                result.put("success", true);
                ObjectNode body = mapper.createObjectNode();
                body.put("success", true);
                body.putArray("errors");
                body.putArray("messages");
                body.putArray("result").add(result);
                respond(ex, 200, mapper.writeValueAsString(body));
            } catch (SQLException e) {
                respond(ex, 400, "{\"success\":false,\"errors\":[{\"code\":7500,\"message\":" + mapper.writeValueAsString(e.getMessage()) + "}],\"result\":[]}");
            }
        }
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    /** Rows of a table, for assertions on what was stored. */
    List<String> column(String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    @Override
    public void close() throws SQLException {
        server.stop(0);
        db.close();
    }
}
