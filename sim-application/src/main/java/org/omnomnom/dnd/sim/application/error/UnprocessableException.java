package org.omnomnom.dnd.sim.application.error;

import java.util.List;

/** A well-formed request that cannot be honored (an unknown monster, over capacity, an unsupported option). */
public final class UnprocessableException extends SimException {

    private static final long serialVersionUID = 1L;

    public UnprocessableException(String code, String message) {
        super(code, message, List.of());
    }

    public UnprocessableException(String code, String message, String field) {
        super(code, message, List.of(new FieldError(field, message, code)));
    }
}
