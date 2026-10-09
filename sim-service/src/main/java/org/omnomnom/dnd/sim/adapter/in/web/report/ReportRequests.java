package org.omnomnom.dnd.sim.adapter.in.web.report;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.omnomnom.dnd.sim.adapter.in.web.error.RequestValidationException;
import org.omnomnom.dnd.sim.application.error.SimException.FieldError;

/** Request bodies of the report endpoints. */
final class ReportRequests {

    private ReportRequests() {}

    static final long MAX_SEED = 4294967295L;

    record RescoreBody(@Size(max = 32) String role, @Size(max = 6) Map<String, @NotNull @DecimalMin("0") Double> weights, Boolean save) {

        void check() {
            if ((role == null) == (weights == null)) {
                throw new RequestValidationException(List.of(new FieldError("role", "give exactly one of role or weights", "invalid-weighting")));
            }
        }
    }

    record ReportCampaignBody(@Min(1) @Max(100) Integer days, @Min(0) @Max(MAX_SEED) Long seed) {}
}
