package org.omnomnom.dnd.sim.adapter.in.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.omnomnom.dnd.sim.application.JobService;
import org.omnomnom.dnd.sim.application.JobView;
import org.omnomnom.dnd.sim.application.ReportService;
import org.omnomnom.dnd.sim.application.ReportStore;
import org.omnomnom.dnd.sim.domain.opt.Reports;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Saved run reports: list, fetch, re-rank under new weights, and annotate with adventuring-day win rates. */
@RestController
@RequestMapping("/api/v1/reports")
class ReportController {

    private static final String ID = "[0-9a-f]{8,64}";

    private final ReportService reports;
    private final JobService jobs;

    ReportController(ReportService reports, JobService jobs) {
        this.reports = reports;
        this.jobs = jobs;
    }

    @GetMapping
    ReportStore.Page list(@RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit, @RequestParam(required = false) String cursor) {
        return reports.list(limit, cursor);
    }

    @GetMapping("/{reportId}")
    Reports.Report get(@PathVariable @Pattern(regexp = ID) String reportId) {
        return reports.get(reportId);
    }

    @PostMapping("/{reportId}/rescore")
    Reports.Report rescore(@PathVariable @Pattern(regexp = ID) String reportId, @Valid @RequestBody Requests.RescoreBody body) {
        body.check();
        return reports.rescore(reportId, body.role(), body.weights(), body.save() != null && body.save());
    }

    @PostMapping("/{reportId}/campaign")
    ResponseEntity<JobView> campaign(@PathVariable @Pattern(regexp = ID) String reportId,
            @Valid @RequestBody(required = false) Requests.ReportCampaignBody body) {
        return JobController.accepted(jobs.startReportCampaign(reportId, body == null ? null : body.days(), body == null ? null : body.seed()));
    }
}
