package org.omnomnom.dnd.sim.adapter.out.report;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.omnomnom.dnd.sim.application.error.BadRequestException;
import org.omnomnom.dnd.sim.application.report.ReportStore;
import org.omnomnom.dnd.sim.domain.opt.report.Reports;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Keeps reports in a Cloudflare D1 table named {@code sim_report}, one row per run key holding the full JSON report plus
 * the few columns the list view needs. This is the service's own table; mapping reports into the app's planned
 * {@code SimRun}/{@code SimResult} tables is a separate integration. The table is created on first use.
 *
 * <p>Timestamps are stored as fixed-width UTC text with millisecond precision, so ordering and the paging cursor are plain
 * string comparisons that SQLite evaluates correctly.
 */
public final class D1ReportStore implements ReportStore {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final Pattern ID = FilesystemReportStore.ID;

    /** {@code <saved_at>|<id>} of the last report on the previous page. */
    private static final Pattern CURSOR = Pattern.compile("(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z)\\|([0-9a-f]{8,64})");

    private final D1Client client;
    private final ObjectMapper mapper;
    private final Clock clock;
    private volatile boolean schemaReady;

    public D1ReportStore(D1Client client, ObjectMapper mapper, Clock clock) {
        this.client = client;
        this.mapper = mapper;
        this.clock = clock;
    }

    private void ensureSchema() {
        if (schemaReady) {
            return;
        }
        synchronized (this) {
            if (!schemaReady) {
                client.query("CREATE TABLE IF NOT EXISTS sim_report ("
                        + "id TEXT PRIMARY KEY, saved_at TEXT NOT NULL, config TEXT NOT NULL, "
                        + "top_description TEXT, top_score REAL, body TEXT NOT NULL)", List.of());
                client.query("CREATE INDEX IF NOT EXISTS sim_report_saved_at ON sim_report (saved_at DESC, id DESC)", List.of());
                schemaReady = true;
            }
        }
    }

    @Override
    public void save(Reports.Report report) {
        if (!ID.matcher(report.runKey()).matches()) {
            throw new IllegalArgumentException("invalid report id: " + report.runKey());
        }
        ensureSchema();
        Reports.Entry top = report.leaderboard().isEmpty() ? null : report.leaderboard().get(0);
        List<Object> params = new ArrayList<>();
        params.add(report.runKey());
        params.add(TIMESTAMP.format(clock.instant().truncatedTo(ChronoUnit.MILLIS)));
        params.add(mapper.writeValueAsString(report.config()));
        params.add(top == null ? null : top.description());
        params.add(top == null ? null : (Object) top.weightedScore());
        params.add(mapper.writeValueAsString(report));
        client.query("INSERT INTO sim_report (id, saved_at, config, top_description, top_score, body) VALUES (?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT(id) DO UPDATE SET saved_at = excluded.saved_at, config = excluded.config, "
                + "top_description = excluded.top_description, top_score = excluded.top_score, body = excluded.body", params);
    }

    @Override
    public Optional<Stored> find(String id) {
        if (!ID.matcher(id).matches()) {
            return Optional.empty();
        }
        ensureSchema();
        List<JsonNode> rows = client.query("SELECT body, saved_at FROM sim_report WHERE id = ?", List.of(id));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        JsonNode row = rows.get(0);
        return Optional.of(new Stored(mapper.readValue(row.get("body").asString(), Reports.Report.class), Instant.parse(row.get("saved_at").asString())));
    }

    @Override
    public Page list(int limit, String cursor) {
        ensureSchema();
        List<Object> params = new ArrayList<>();
        String where = "";
        if (cursor != null) {
            Matcher m = CURSOR.matcher(cursor);
            if (!m.matches()) {
                throw new BadRequestException("invalid-cursor", "cursor is not one this service issued", "cursor");
            }
            String savedAt = m.group(1);
            String id = m.group(2);
            where = " WHERE saved_at < ? OR (saved_at = ? AND id < ?)";
            params.add(savedAt);
            params.add(savedAt);
            params.add(id);
        }
        params.add(limit + 1);
        List<JsonNode> rows = client.query("SELECT id, saved_at, config, top_description, top_score FROM sim_report" + where
                + " ORDER BY saved_at DESC, id DESC LIMIT ?", params);
        List<Summary> items = new ArrayList<>();
        String next = null;
        for (JsonNode row : rows) {
            if (items.size() == limit) {
                JsonNode last = rows.get(limit - 1);
                next = last.get("saved_at").asString() + "|" + last.get("id").asString();
                break;
            }
            Map<String, Object> config = mapper.readValue(row.get("config").asString(), new TypeReference<Map<String, Object>>() {});
            Top top = row.path("top_description").isNull() ? null : new Top(row.get("top_description").asString(), row.get("top_score").asDouble());
            items.add(new Summary(row.get("id").asString(), Instant.parse(row.get("saved_at").asString()), config, top));
        }
        return new Page(items, next);
    }
}
