package org.omnomnom.dnd.sim.adapter.in.web;

import jakarta.validation.Valid;
import org.omnomnom.dnd.sim.application.EncounterResult;
import org.omnomnom.dnd.sim.application.EncounterService;
import org.omnomnom.dnd.sim.application.EvaluationService;
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

    SimulateController(EncounterService encounters, EvaluationService evaluations) {
        this.encounters = encounters;
        this.evaluations = evaluations;
    }

    @PostMapping("/encounter")
    EncounterResult encounter(@Valid @RequestBody Requests.EncounterBody body) {
        return encounters.simulate(body.toCommand());
    }

    @PostMapping("/eval")
    EvaluationService.EvalOutcome eval(@Valid @RequestBody Requests.EvalBody body) {
        return evaluations.evaluate(body.toCommand());
    }

    @PostMapping("/campaign")
    EvaluationService.CampaignOutcome campaign(@Valid @RequestBody Requests.CampaignBody body) {
        return evaluations.campaign(body.toCommand());
    }
}
