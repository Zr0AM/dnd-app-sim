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

    /** A report file on disk: its id and save time, at the millisecond precision the sort and the cursor share. */
    private record Entry(String id, Instant savedAt) {

        /** Newest first; the id breaks ties so pages are stable. */
        static final Comparator<Entry> NEWEST_FIRST = Comparator.comparing(Entry::savedAt).thenComparing(Entry::id).reversed();
    }

    /** Where the previous page ended. */
    private record Cursor(Instant savedAt, String id) {

        static Cursor parse(String text) {
            Matcher m = CURSOR.matcher(text);
            if (!m.matches()) {
                throw new BadRequestException("invalid-cursor", "cursor is not one this service issued", "cursor");
            }
            return new Cursor(Instant.ofEpochMilli(Long.parseLong(m.group(1))), m.group(2));
        }

        /** Whether {@code e} comes after this cursor in newest-first order. */
        boolean precedes(Entry e) {
            int cmp = e.savedAt().compareTo(savedAt);
            return cmp < 0 || (cmp == 0 && e.id().compareTo(id) < 0);
        }
    }

    @Override
    public Page list(int limit, String cursor) {
        Cursor after = cursor == null ? null : Cursor.parse(cursor);
        List<Entry> candidates = scan().stream().filter(e -> after == null || after.precedes(e)).toList();
        List<Summary> items = new ArrayList<>();
        String next = null;
        for (Entry e : candidates) {
            if (items.size() == limit) {
                Summary last = items.get(items.size() - 1);
                next = last.createdAt().toEpochMilli() + ":" + last.id();
                break;
            }
            summaryOf(e).ifPresent(items::add);
        }
        return new Page(items, next);
    }

    /** Every report file in the directory, newest first. */
    private List<Entry> scan() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            List<Entry> entries = new ArrayList<>();
            for (Path p : (Iterable<Path>) files::iterator) {
                entryOf(p).ifPresent(entries::add);
            }
            entries.sort(Entry.NEWEST_FIRST);
            return entries;
        } catch (IOException e) {
            throw new UncheckedIOException("could not list reports", e);
        }
    }

    private static Optional<Entry> entryOf(Path p) throws IOException {
        String name = p.getFileName().toString();
        if (!name.endsWith(".json") || !ID.matcher(name.substring(0, name.length() - 5)).matches()) {
            return Optional.empty();
        }
        // Millisecond precision throughout: the sort, the cursor and the comparison must all agree.
        return Optional.of(new Entry(name.substring(0, name.length() - 5), Files.getLastModifiedTime(p).toInstant().truncatedTo(ChronoUnit.MILLIS)));
    }

    /** The summary of a report file, or empty if the file cannot be read (it is skipped, not fatal). */
    private Optional<Summary> summaryOf(Entry e) {
        try {
            Reports.Report r = mapper.readValue(file(e.id()), Reports.Report.class);
            Top top = r.leaderboard().isEmpty() ? null
                    : new Top(r.leaderboard().get(0).description(), r.leaderboard().get(0).weightedScore());
            return Optional.of(new Summary(e.id(), e.savedAt(), r.config(), top));
        } catch (RuntimeException ex) {
            LOG.warn("skipping unreadable report file {}: {}", e.id(), ex.toString());
            return Optional.empty();
        }
    }

    private Path file(String id) {
        return directory.resolve(id + ".json");
    }
}
