package org.omnomnom.dnd.sim.testsupport;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.omnomnom.dnd.sim.application.report.ReportStore;
import org.omnomnom.dnd.sim.domain.opt.report.Reports;

/** A trivial report store for tests of the application layer. Can be told to fail on save. */
public final class InMemoryReportStore implements ReportStore {

    private final Map<String, Stored> reports = new ConcurrentHashMap<>();
    public final AtomicBoolean failOnSave = new AtomicBoolean();
    /** Makes save throw an Error (as an out-of-memory or stack overflow would) rather than an exception. */
    public final AtomicBoolean errorOnSave = new AtomicBoolean();

    @Override
    public void save(Reports.Report report) {
        if (failOnSave.get()) {
            throw new IllegalStateException("disk on fire");
        }
        if (errorOnSave.get()) {
            throw new AssertionError("heap on fire");
        }
        reports.put(report.runKey(), new Stored(report, Instant.now()));
    }

    @Override
    public Optional<Stored> find(String id) {
        return Optional.ofNullable(reports.get(id));
    }

    @Override
    public Page list(int limit, String cursor) {
        List<Summary> all = new ArrayList<>();
        reports.forEach((id, s) -> all.add(new Summary(id, s.savedAt(), s.report().config(), null)));
        all.sort(Comparator.comparing(Summary::createdAt).reversed().thenComparing(Summary::id));
        return new Page(all.subList(0, Math.min(limit, all.size())), null);
    }

    public int size() {
        return reports.size();
    }
}
