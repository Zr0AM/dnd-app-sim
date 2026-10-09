package org.omnomnom.dnd.sim.adapter.out.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.omnomnom.dnd.sim.application.error.BadRequestException;
import org.omnomnom.dnd.sim.application.report.ReportStore;
import org.omnomnom.dnd.sim.domain.opt.report.Reports;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Keeps each report as {@code <runKey>.json} in a directory, the layout the CLI used. Writes go to a temporary file and
 * are moved into place, so a reader never sees a half-written report. The file's modification time is the save time.
 */
public final class FilesystemReportStore implements ReportStore {

    private static final Logger LOG = LoggerFactory.getLogger(FilesystemReportStore.class);

    /** Run keys are lowercase hex; anything else is rejected before it can reach the filesystem. */
    static final Pattern ID = Pattern.compile("[0-9a-f]{8,64}");

    /** {@code <epoch millis>:<id>} of the last report on the previous page. */
    private static final Pattern CURSOR = Pattern.compile("(\\d{1,18}):([0-9a-f]{8,64})");

    private final Path directory;
    private final ObjectMapper mapper;

    public FilesystemReportStore(Path directory, ObjectMapper mapper) {
        this.directory = directory;
        this.mapper = mapper;
    }

    @Override
    public void save(Reports.Report report) {
        String id = report.runKey();
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("invalid report id: " + id);
        }
        try {
            Files.createDirectories(directory);
            Path temp = Files.createTempFile(directory, id, ".tmp");
            try {
                mapper.writerWithDefaultPrettyPrinter().writeValue(temp, report);
                Files.move(temp, file(id), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not save report " + id, e);
        }
    }

    @Override
    public Optional<Stored> find(String id) {
        if (!ID.matcher(id).matches() || !Files.isRegularFile(file(id))) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Stored(mapper.readValue(file(id), Reports.Report.class), Files.getLastModifiedTime(file(id)).toInstant()));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read report " + id, e);
        }
    }

    @Override
    public Page list(int limit, String cursor) {
        record Entry(String id, Instant savedAt) {}
        List<Entry> entries = new ArrayList<>();
        if (Files.isDirectory(directory)) {
            try (Stream<Path> files = Files.list(directory)) {
                for (Path p : (Iterable<Path>) files::iterator) {
                    String name = p.getFileName().toString();
                    if (name.endsWith(".json") && ID.matcher(name.substring(0, name.length() - 5)).matches()) {
                        // Millisecond precision throughout: the sort, the cursor and the comparison must all agree.
                        entries.add(new Entry(name.substring(0, name.length() - 5),
                                Files.getLastModifiedTime(p).toInstant().truncatedTo(ChronoUnit.MILLIS)));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("could not list reports", e);
            }
        }
        // Newest first; the id breaks ties so pages are stable.
        entries.sort(Comparator.comparing(Entry::savedAt).thenComparing(Entry::id).reversed());

        Instant afterTime = null;
        String afterId = null;
        if (cursor != null) {
            Matcher m = CURSOR.matcher(cursor);
            if (!m.matches()) {
                throw new BadRequestException("invalid-cursor", "cursor is not one this service issued", "cursor");
            }
            afterTime = Instant.ofEpochMilli(Long.parseLong(m.group(1)));
            afterId = m.group(2);
        }
        List<Summary> items = new ArrayList<>();
        String next = null;
        for (Entry e : entries) {
            if (afterTime != null) {
                int cmp = e.savedAt().compareTo(afterTime);
                if (cmp > 0 || (cmp == 0 && e.id().compareTo(afterId) >= 0)) {
                    continue;
                }
            }
            if (items.size() == limit) {
                Summary last = items.get(items.size() - 1);
                next = last.createdAt().toEpochMilli() + ":" + last.id();
                break;
            }
            try {
                Reports.Report r = mapper.readValue(file(e.id()), Reports.Report.class);
                Top top = r.leaderboard().isEmpty() ? null
                        : new Top(r.leaderboard().get(0).description(), r.leaderboard().get(0).weightedScore());
                items.add(new Summary(e.id(), e.savedAt(), r.config(), top));
            } catch (RuntimeException ex) {
                LOG.warn("skipping unreadable report file {}: {}", e.id(), ex.toString());
            }
        }
        return new Page(items, next);
    }

    private Path file(String id) {
        return directory.resolve(id + ".json");
    }
}
