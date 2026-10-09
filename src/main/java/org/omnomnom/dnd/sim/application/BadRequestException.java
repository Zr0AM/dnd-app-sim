package org.omnomnom.dnd.sim.application;

import java.util.List;

/** A request value that is malformed (for example a paging cursor that was not issued by the service). */
public final class BadRequestException extends SimException {

    private static final long serialVersionUID = 1L;

    public BadRequestException(String code, String message, String field) {
        super(code, message, List.of(new FieldError(field, message, code)));
    }
}
