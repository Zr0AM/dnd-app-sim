package org.omnomnom.dnd.sim.application.error;

import java.util.List;

/** The request conflicts with the resource's state (cancelling a job that already finished). */
public final class ConflictException extends SimException {

    private static final long serialVersionUID = 1L;

    public ConflictException(String code, String message) {
        super(code, message, List.of());
    }
}
