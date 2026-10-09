package org.omnomnom.dnd.sim.application.job;

import java.time.Instant;
import java.util.List;
import org.omnomnom.dnd.sim.domain.core.Coded;

/**
 * A point-in-time snapshot of a job.
 *
 * @param reportId set once the job has succeeded
 * @param warnings for example a solo run scored for a party-only role
 * @param error set when the job failed
 */
public record JobView(
        String id,
        Kind kind,
        Status status,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Long seed,
        Progress progress,
        String reportId,
        List<String> warnings,
        Problem error) {

    public enum Kind implements org.omnomnom.dnd.sim.domain.core.Coded {
        OPTIMIZE("optimize"),
        CAMPAIGN("campaign");

        private final String code;

        Kind(String code) {
            this.code = code;
        }

        @Override
        public String code() {
            return code;
        }
    }

    public enum Status implements org.omnomnom.dnd.sim.domain.core.Coded {
        QUEUED("queued"),
        RUNNING("running"),
        SUCCEEDED("succeeded"),
        FAILED("failed"),
        CANCELLED("cancelled");

        private final String code;

        Status(String code) {
            this.code = code;
        }

        @Override
        public String code() {
            return code;
        }

        public boolean finished() {
            return this == SUCCEEDED || this == FAILED || this == CANCELLED;
        }
    }

    /** NSGA-II progress: the initial population (one step), then each generation, then the optional campaign pass. */
    public record Progress(String phase, int completed, int total) {}

    /** The failure that ended a job, in the shape of an RFC 9457 problem. */
    public record Problem(String type, String title, int status, String detail, String code) {}
}
