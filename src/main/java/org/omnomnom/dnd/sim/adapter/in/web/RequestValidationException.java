package org.omnomnom.dnd.sim.adapter.in.web;

import java.util.List;
import org.omnomnom.dnd.sim.application.SimException.FieldError;

/** A request that is well-formed JSON but fails a rule Bean Validation cannot express (for example a cross-field rule). */
final class RequestValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<FieldError> errors;

    RequestValidationException(List<FieldError> errors) {
        super("The request is invalid.");
        this.errors = List.copyOf(errors);
    }

    List<FieldError> errors() {
        return errors;
    }
}
