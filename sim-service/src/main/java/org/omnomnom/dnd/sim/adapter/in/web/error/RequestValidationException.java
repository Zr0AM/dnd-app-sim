package org.omnomnom.dnd.sim.adapter.in.web.error;

import java.util.List;
import org.omnomnom.dnd.sim.application.error.SimException.FieldError;

/** A request that is well-formed JSON but fails a rule Bean Validation cannot express (for example a cross-field rule). */
public final class RequestValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<FieldError> errors;

    public RequestValidationException(List<FieldError> errors) {
        super("The request is invalid.");
        this.errors = List.copyOf(errors);
    }

    public List<FieldError> errors() {
        return errors;
    }
}
