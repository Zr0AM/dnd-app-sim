package org.omnomnom.dnd.sim.adapter.in.web.simulate;

import jakarta.validation.Valid;
import org.omnomnom.dnd.sim.adapter.in.web.job.JobController;
import org.omnomnom.dnd.sim.adapter.in.web.security.Expensive;
import org.omnomnom.dnd.sim.application.encounter.EncounterResult;
import org.omnomnom.dnd.sim.application.encounter.EncounterService;
import org.omnomnom.dnd.sim.application.evaluation.EvaluationService;
import org.omnomnom.dnd.sim.application.job.JobService;
import org.omnomnom.dnd.sim.application.job.JobView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The synchronous simulation endpoints: an encounter, one build's evaluation, one build's adventuring day. */
@RestController
@RequestMapping("/api/v1/simulate")
class SimulateController {

    private final EncounterService encounters;
    private final EvaluationService evaluations;
    private final JobService jobs;

    SimulateController(EncounterService encounters, EvaluationService evaluations, JobService jobs) {
        this.encounters = encounters;
        this.evaluations = evaluations;
        this.jobs = jobs;
    }

    @Expensive
    @PostMapping("/encounter")
    EncounterResult encounter(@Valid @RequestBody SimulateRequests.EncounterBody body) {
        return encounters.simulate(body.toCommand());
    }

    @Expensive
    @PostMapping("/eval")
    EvaluationService.EvalOutcome eval(@Valid @RequestBody SimulateRequests.EvalBody body) {
        return evaluations.evaluate(body.toCommand());
    }

    @Expensive
    @PostMapping("/campaign")
    EvaluationService.CampaignOutcome campaign(@Valid @RequestBody SimulateRequests.CampaignBody body) {
        return evaluations.campaign(body.toCommand());
    }

    @Expensive
    @PostMapping("/optimize")
    ResponseEntity<JobView> optimize(@Valid @RequestBody SimulateRequests.OptimizeBody body) {
        return JobController.accepted(jobs.startOptimization(body.toCommand()));
    }
}
