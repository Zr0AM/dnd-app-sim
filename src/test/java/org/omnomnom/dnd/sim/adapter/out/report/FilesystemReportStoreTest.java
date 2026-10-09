package org.omnomnom.dnd.sim.adapter.out.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.omnomnom.dnd.sim.application.ReportStore;
import org.omnomnom.dnd.sim.domain.opt.Reports;
import org.omnomnom.dnd.sim.testsupport.TestReports;

class FilesystemReportStoreTest {

    @TempDir
    Path dir;

    FilesystemReportStore store;

    @BeforeEach
    void open() {
        store = new FilesystemReportStore(dir.resolve("reports"), TestReports.mapper());
    }

    private void stamp(String id, long epochMillis) throws IOException {
        Files.setLastModifiedTime(dir.resolve("reports").resolve(id + ".json"), FileTime.fromMillis(epochMillis));
    }

    @Test
    void savesAndReadsBackTheSameReport() {
        Reports.Report report = TestReports.report("0123abcd", 0.75);
        store.save(report);
        ReportStore.Stored stored = store.find("0123abcd").orElseThrow();
        assertThat(stored.report().runKey()).isEqualTo("0123abcd");
        assertThat(stored.report().config()).isEqualTo(report.config());
        assertThat(stored.report().leaderboard().get(0)).usingRecursiveComparison().ignoringFields("objectives").isEqualTo(report.leaderboard().get(0));
        assertThat(stored.report().leaderboard().get(0).objectives()).isEqualTo(report.leaderboard().get(0).objectives());
        assertThat(stored.report().objectiveBounds().get("offense")).containsExactly(0, 1);
        assertThat(stored.savedAt()).isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(60));
    }

    @Test
    void anUnknownOrMalformedIdIsAbsentNotAnError() {
        assertThat(store.find("deadbeef")).isEmpty();
        assertThat(store.find("../../etc/passwd")).isEmpty();
        assertThat(store.find("UPPERCASE1")).isEmpty();
        assertThat(store.list(10, null).items()).isEmpty(); // no directory yet
    }

    @Test
    void refusesToSaveUnderAnUnsafeId() {
        assertThatThrownBy(() -> store.save(TestReports.report("../evil", 0.1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.save(TestReports.report("short", 0.1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void savingAgainOverwrites() {
        store.save(TestReports.report("0123abcd", 0.25));
        store.save(TestReports.report("0123abcd", 0.99));
        assertThat(store.find("0123abcd").orElseThrow().report().leaderboard().get(0).weightedScore()).isEqualTo(0.99);
        assertThat(store.list(10, null).items()).hasSize(1);
    }

    @Test
    void leavesNoTemporaryFilesBehind() throws IOException {
        store.save(TestReports.report("0123abcd", 0.5));
        try (Stream<Path> files = Files.list(dir.resolve("reports"))) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("0123abcd.json");
        }
    }

    @Test
    void listsNewestFirstAndPagesWithACursor() throws IOException {
        for (int i = 1; i <= 5; i++) {
            store.save(TestReports.report("0000000" + i, i / 10.0));
            stamp("0000000" + i, 1_000_000L * i);
        }
        ReportStore.Page first = store.list(2, null);
        assertThat(first.items()).extracting(ReportStore.Summary::id).containsExactly("00000005", "00000004");
        assertThat(first.nextCursor()).isNotNull();
        ReportStore.Page second = store.list(2, first.nextCursor());
        assertThat(second.items()).extracting(ReportStore.Summary::id).containsExactly("00000003", "00000002");
        ReportStore.Page third = store.list(2, second.nextCursor());
        assertThat(third.items()).extracting(ReportStore.Summary::id).containsExactly("00000001");
        assertThat(third.nextCursor()).isNull();
        assertThat(first.items().get(0).top().weightedScore()).isEqualTo(0.5);
        assertThat(first.items().get(0).top().description()).startsWith("L3 fighter");
        assertThat(first.items().get(0).config()).containsEntry("level", 3);
    }

    @Test
    void reportsSavedInTheSameInstantPageStablyById() throws IOException {
        for (String id : List.of("aaaaaaaa", "bbbbbbbb", "cccccccc")) {
            store.save(TestReports.report(id, 0.5));
            stamp(id, 5_000);
        }
        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            ReportStore.Page page = store.list(1, cursor);
            page.items().forEach(s -> seen.add(s.id()));
            cursor = page.nextCursor();
        } while (cursor != null);
        assertThat(seen).containsExactly("cccccccc", "bbbbbbbb", "aaaaaaaa");
    }

    @Test
    void skipsFilesThatAreNotReports() throws IOException {
        store.save(TestReports.report("0123abcd", 0.5));
        Files.writeString(dir.resolve("reports").resolve("deadbeef.json"), "{ not json");
        Files.writeString(dir.resolve("reports").resolve("notes.txt"), "hello");
        Files.writeString(dir.resolve("reports").resolve("NOT-HEX.json"), "{}");
        assertThat(store.list(10, null).items()).extracting(ReportStore.Summary::id).containsExactly("0123abcd");
    }

    // ---- adversarial review fixes ----------------------------------------------------------------

    @Test
    void reportsSavedWithinOneMillisecondAreAllListed() throws IOException {
        // A sorts first by nanoseconds but last by id; with mixed precision the second page used to skip B.
        store.save(TestReports.report("bbbbbbbb", 0.5));
        store.save(TestReports.report("zzzzzzzz".replace('z', 'f'), 0.5));
        Files.setLastModifiedTime(dir.resolve("reports").resolve("bbbbbbbb.json"), FileTime.from(Instant.ofEpochSecond(1, 500_000)));
        Files.setLastModifiedTime(dir.resolve("reports").resolve("ffffffff.json"), FileTime.from(Instant.ofEpochSecond(1, 100_000)));
        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            ReportStore.Page page = store.list(1, cursor);
            page.items().forEach(s -> seen.add(s.id()));
            cursor = page.nextCursor();
        } while (cursor != null);
        assertThat(seen).containsExactly("ffffffff", "bbbbbbbb");
    }

    @Test
    void aCursorTheServiceDidNotIssueIsABadRequest() {
        for (String bad : List.of("abc", "x:y", "12:NOTHEX", ":0123abcd", "99999999999999999999:0123abcd", "1:../../x")) {
            assertThatThrownBy(() -> store.list(5, bad))
                    .as(bad)
                    .isInstanceOfSatisfying(org.omnomnom.dnd.sim.application.BadRequestException.class,
                            e -> assertThat(e.code()).isEqualTo("invalid-cursor"));
        }
    }
}
