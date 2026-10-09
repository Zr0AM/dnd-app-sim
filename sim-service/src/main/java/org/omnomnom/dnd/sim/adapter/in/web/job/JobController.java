package org.omnomnom.dnd.sim.adapter.in.web.job;

import java.net.URI;
import org.omnomnom.dnd.sim.application.job.JobService;
import org.omnomnom.dnd.sim.application.job.JobView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Status, progress and cancellation of long-running work started by the simulate and report endpoints. */
@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private final JobService jobs;

    JobController(JobService jobs) {
        this.jobs = jobs;
    }

    /** A {@code 202 Accepted} for a newly created job, pointing at its resource. */
    public static ResponseEntity<JobView> accepted(JobView job) {
        return ResponseEntity.accepted().location(URI.create("/api/v1/jobs/" + job.id())).body(job);
    }

    @GetMapping("/{jobId}")
    JobView get(@PathVariable String jobId) {
        return jobs.get(jobId);
    }

    @DeleteMapping("/{jobId}")
    JobView cancel(@PathVariable String jobId) {
        return jobs.cancel(jobId);
    }
}
