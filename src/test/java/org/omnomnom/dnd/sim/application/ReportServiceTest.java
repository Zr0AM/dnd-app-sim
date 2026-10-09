package org.omnomnom.dnd.sim.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.domain.opt.Reports;
import org.omnomnom.dnd.sim.testsupport.InMemoryReportStore;
import org.omnomnom.dnd.sim.testsupport.TestReports;

class ReportServiceTest {

    InMemoryReportStore store = new InMemoryReportStore();
    ReportService service = new ReportService(store);

    @BeforeEach
    void seed() {
        store.save(TestReports.report("0123abcd", 0.5));
    }

    private static void assertCode(Runnable call, Class<? extends SimException> type, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(type, e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void getsAndMissesReports() {
        assertThat(service.get("0123abcd").runKey()).isEqualTo("0123abcd");
        assertCode(() -> service.get("deadbeef"), NotFoundException.class, "report-not-found");
        assertThat(service.list(5, null).items()).hasSize(1);
    }

    @Test
    void rescoreNeedsExactlyOneOfRoleAndWeights() {
        assertCode(() -> service.rescore("0123abcd", null, null, false), UnprocessableException.class, "invalid-weighting");
        assertCode(() -> service.rescore("0123abcd", "tank", Map.of("offense", 1.0), false), UnprocessableException.class, "invalid-weighting");
    }

    @Test
    void weightsMustNameObjectivesAndBeNonNegativeNumbers() {
        assertCode(() -> service.rescore("0123abcd", null, Map.of("charisma", 1.0), false), UnprocessableException.class, "unknown-objective");
        assertCode(() -> service.rescore("0123abcd", null, Map.of("offense", -0.5), false), UnprocessableException.class, "invalid-weight");
        Map<String, Double> withNull = new HashMap<>();
        withNull.put("offense", null);
        assertCode(() -> service.rescore("0123abcd", null, withNull, false), UnprocessableException.class, "invalid-weight");
        // Zero and fractional weights are legal.
        assertThatCode(() -> service.rescore("0123abcd", null, Map.of("offense", 0.0), false)).doesNotThrowAnyException();
        assertThatCode(() -> service.rescore("0123abcd", null, Map.of("offense", 0.5, "survival", 0.25), false)).doesNotThrowAnyException();
    }

    @Test
    void anUnknownReportIs404EvenWithValidWeights() {
        assertCode(() -> service.rescore("deadbeef", "tank", null, false), NotFoundException.class, "report-not-found");
    }

    @Test
    void savingReplacesTheStoredReportOnlyWhenAsked() {
        Reports.Report unsaved = service.rescore("0123abcd", "tank", null, false);
        assertThat(unsaved.weights().get("survival")).isEqualTo(3.0);
        assertThat(service.get("0123abcd").weights().get("survival")).isEqualTo(1.0);
        service.rescore("0123abcd", "tank", null, true);
        assertThat(service.get("0123abcd").weights().get("survival")).isEqualTo(3.0);
        service.rescore("0123abcd", "equal", null, true);
        assertThat(service.get("0123abcd").weights()).isEqualTo(Reports.equalWeights());
    }
}
