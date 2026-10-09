package org.omnomnom.dnd.sim.adapter.out.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.application.error.BadRequestException;
import org.omnomnom.dnd.sim.application.report.ReportStore;
import org.omnomnom.dnd.sim.testsupport.TestMapper;
import org.omnomnom.dnd.sim.testsupport.TestReports;

/** The D1 store against a fake of the D1 HTTP API that runs the SQL on a real SQLite database. */
class D1ReportStoreTest {

    FakeD1Server d1;
    Instant now = Instant.parse("2026-10-08T12:00:00.000Z");
    D1ReportStore store;

    @BeforeEach
    void open() throws Exception {
        d1 = new FakeD1Server();
        store = new D1ReportStore(client(d1.token), TestMapper.mapper(), new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        });
    }

    private D1Client client(String token) {
        return new D1Client(HttpClient.newHttpClient(), d1.baseUrl(), d1.accountId, d1.databaseId, token, Duration.ofSeconds(5), TestMapper.mapper());
    }

    @AfterEach
    void close() throws Exception {
        d1.close();
    }

    @Test
    void createsItsTableOnFirstUseOnly() {
        store.find("0123abcd");
        store.find("0123abcd");
        store.save(TestReports.report("0123abcd", 0.5));
        assertThat(d1.statements.stream().filter(s -> s.startsWith("CREATE TABLE"))).hasSize(1);
        assertThat(d1.statements.stream().filter(s -> s.startsWith("CREATE INDEX"))).hasSize(1);
    }

    @Test
    void savesAndReadsBackAReport() throws Exception {
        store.save(TestReports.report("0123abcd", 0.75));
        ReportStore.Stored stored = store.find("0123abcd").orElseThrow();
        assertThat(stored.report().runKey()).isEqualTo("0123abcd");
        assertThat(stored.report().leaderboard().get(0).weightedScore()).isEqualTo(0.75);
        assertThat(stored.report().config()).containsEntry("level", 3);
        assertThat(stored.savedAt()).isEqualTo(now);
        assertThat(d1.column("SELECT top_description FROM sim_report")).singleElement().asString().startsWith("L3 fighter");
        assertThat(d1.column("SELECT saved_at FROM sim_report")).containsExactly("2026-10-08T12:00:00.000Z");
    }

    @Test
    void anUnknownOrMalformedIdIsAbsent() {
        assertThat(store.find("deadbeef")).isEmpty();
        assertThat(store.find("x'; DROP TABLE sim_report; --")).isEmpty();
        assertThatThrownBy(() -> store.save(TestReports.report("bad id", 0.1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void savingAgainOverwritesAndRefreshesTheTimestamp() throws Exception {
        store.save(TestReports.report("0123abcd", 0.25));
        now = now.plusSeconds(60);
        store.save(TestReports.report("0123abcd", 0.99));
        assertThat(d1.column("SELECT COUNT(*) FROM sim_report")).containsExactly("1");
        ReportStore.Stored stored = store.find("0123abcd").orElseThrow();
        assertThat(stored.report().leaderboard().get(0).weightedScore()).isEqualTo(0.99);
        assertThat(stored.savedAt()).isEqualTo(now);
    }

    @Test
    void listsNewestFirstAndPages() {
        for (int i = 1; i <= 5; i++) {
            now = now.plusSeconds(10);
            store.save(TestReports.report("0000000" + i, i / 10.0));
        }
        ReportStore.Page first = store.list(2, null);
        assertThat(first.items()).extracting(ReportStore.Summary::id).containsExactly("00000005", "00000004");
        ReportStore.Page second = store.list(2, first.nextCursor());
        assertThat(second.items()).extracting(ReportStore.Summary::id).containsExactly("00000003", "00000002");
        ReportStore.Page third = store.list(2, second.nextCursor());
        assertThat(third.items()).extracting(ReportStore.Summary::id).containsExactly("00000001");
        assertThat(third.nextCursor()).isNull();
        assertThat(first.items().get(0).top().weightedScore()).isEqualTo(0.5);
        assertThat(first.items().get(0).config()).containsEntry("seed", 7);
    }

    @Test
    void reportsSavedInTheSameMillisecondPageStablyById() {
        for (String id : List.of("aaaaaaaa", "bbbbbbbb", "cccccccc")) {
            store.save(TestReports.report(id, 0.5));
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
    void aBadTokenFailsWithoutLeakingIt() {
        D1ReportStore wrong = new D1ReportStore(client("wrong-token"), TestMapper.mapper(), Clock.systemUTC());
        assertThatThrownBy(() -> wrong.save(TestReports.report("0123abcd", 0.5)))
                .isInstanceOf(D1Client.D1Exception.class)
                .hasMessageContaining("403")
                .hasMessageContaining("Authentication error")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("wrong-token"));
    }

    @Test
    void serverAndBodyFailuresSurface() {
        d1.failWithStatus = 500;
        assertThatThrownBy(() -> store.find("0123abcd")).isInstanceOf(D1Client.D1Exception.class).hasMessageContaining("500").hasMessageContaining("forced failure");
        d1.failWithStatus = 0;
        d1.reportFailureInBody = true;
        assertThatThrownBy(() -> store.list(5, null)).isInstanceOf(D1Client.D1Exception.class).hasMessageContaining("body failure");
    }

    @Test
    void anUnreachableServerIsAD1Exception() throws Exception {
        d1.close();
        assertThatThrownBy(() -> store.find("0123abcd")).isInstanceOf(D1Client.D1Exception.class).hasMessageContaining("D1 request failed");
        d1 = new FakeD1Server(); // so @AfterEach has something to close
    }

    @Test
    void aCursorTheServiceDidNotIssueIsABadRequestAndNeverReachesD1() {
        store.list(1, null); // creates the schema
        int before = d1.statements.size();
        for (String bad : List.of("abc", "2026-10-08T12:00:00Z|0123abcd", "2026-10-08T12:00:00.000Z|bad id", "x|y", "' OR 1=1 --|0123abcd")) {
            assertThatThrownBy(() -> store.list(5, bad))
                    .as(bad)
                    .isInstanceOfSatisfying(org.omnomnom.dnd.sim.application.error.BadRequestException.class,
                            e -> assertThat(e.code()).isEqualTo("invalid-cursor"));
        }
        assertThat(d1.statements).hasSize(before);
    }
}
