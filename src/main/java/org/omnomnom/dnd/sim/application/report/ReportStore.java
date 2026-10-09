package org.omnomnom.dnd.sim.application.report;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.omnomnom.dnd.sim.domain.opt.report.Reports;

/**
 * Where finished run reports live (a driven port). A report is addressed by its run key; saving a report with an
 * existing key overwrites it, as the CLI's {@code <runKey>.json} did. Implementations must be safe for concurrent use.
 */
public interface ReportStore {

    /** A report and when it was last saved. */
    record Stored(Reports.Report report, Instant savedAt) {}

    /** The list-view digest of a report. {@code top} is the leaderboard's best entry, or null when it is empty. */
    record Summary(String id, Instant createdAt, Map<String, Object> config, Top top) {}

    record Top(String description, double weightedScore) {}

    /**
     * One page of summaries, newest first.
     *
     * @param nextCursor opaque; pass it back to continue, or null when this is the last page
     */
    record Page(List<Summary> items, String nextCursor) {}

    /** Save (or overwrite) the report under its run key; returns when it is durable. */
    void save(Reports.Report report);

    Optional<Stored> find(String id);

    /**
     * @param limit page size, at least 1
     * @param cursor a previous page's {@code nextCursor}, or null for the first page
     */
    Page list(int limit, String cursor);
}
