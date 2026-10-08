package org.omnomnom.dnd.sim.application;

import java.util.List;

/** Base of the errors a use case reports to its caller; the web adapter maps each subtype to an HTTP status. */
public abstract class SimException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** A field-level detail of a problem. */
    public record FieldError(String field, String message, String code) {}

    private final String code;
    private final transient List<FieldError> errors;

    protected SimException(String code, String message, List<FieldError> errors) {
        super(message);
        this.code = code;
        this.errors = List.copyOf(errors);
    }

    /** A stable machine-readable code, for example {@code unknown-monster}. */
    public String code() {
        return code;
    }

    public List<FieldError> errors() {
        return errors;
    }
}
