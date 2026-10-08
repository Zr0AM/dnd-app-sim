package org.omnomnom.dnd.sim.application;

import java.util.List;

/** A job or report that does not exist. */
public final class NotFoundException extends SimException {

    private static final long serialVersionUID = 1L;

    public NotFoundException(String code, String message) {
        super(code, message, List.of());
    }
}
