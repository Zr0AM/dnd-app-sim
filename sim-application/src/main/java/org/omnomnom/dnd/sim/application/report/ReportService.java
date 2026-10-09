package org.omnomnom.dnd.sim.application.report;

import java.util.Map;
import org.omnomnom.dnd.sim.application.error.NotFoundException;
import org.omnomnom.dnd.sim.application.error.UnprocessableException;
import org.omnomnom.dnd.sim.application.evaluation.EvaluationService;
import org.omnomnom.dnd.sim.domain.opt.evaluation.Objectives;
import org.omnomnom.dnd.sim.domain.opt.report.Reports;
import org.omnomnom.dnd.sim.domain.opt.report.RolePresets;

/** Reads saved reports and re-ranks them under new weights without re-simulating (the CLI {@code rescore}). */
public final class ReportService {

    private final ReportStore store;

    public ReportService(ReportStore store) {
        this.store = store;
    }

    public ReportStore.Page list(int limit, String cursor) {
        return store.list(limit, cursor);
    }

    public Reports.Report get(String id) {
        return store.find(id).map(ReportStore.Stored::report)
                .orElseThrow(() -> new NotFoundException("report-not-found", "no report with id " + id));
    }

    /**
     * Re-rank a report. Provide a role (a preset or {@code equal}) or explicit weights, not both.
     *
     * @param save persist the re-ranked report, replacing the stored one
     */
    public Reports.Report rescore(String id, String role, Map<String, Double> weights, boolean save) {
        if ((role == null) == (weights == null)) {
            throw new UnprocessableException("invalid-weighting", "give exactly one of role or weights", "role");
        }
        Map<String, Double> effective;
        if (role != null) {
            EvaluationService.requireKnownRole(role);
            effective = role.equals("equal") ? Reports.equalWeights() : RolePresets.weights(role);
        } else {
            for (Map.Entry<String, Double> e : weights.entrySet()) {
                if (!Objectives.NAMES.contains(e.getKey())) {
                    throw new UnprocessableException("unknown-objective", "unknown objective: " + e.getKey(), "weights." + e.getKey());
                }
                if (e.getValue() == null || e.getValue() < 0) {
                    throw new UnprocessableException("invalid-weight", "weights must be non-negative", "weights." + e.getKey());
                }
            }
            effective = new java.util.LinkedHashMap<>(weights);
        }
        Reports.Report rescored = Reports.rescore(get(id), effective);
        if (save) {
            store.save(rescored);
        }
        return rescored;
    }
}
