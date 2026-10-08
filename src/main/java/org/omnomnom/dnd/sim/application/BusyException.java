package org.omnomnom.dnd.sim.application;

import java.util.List;

/** The simulation executor's queue is full; the caller should retry later. */
public final class BusyException extends SimException {

    private static final long serialVersionUID = 1L;

    private final int retryAfterSeconds;

    public BusyException(int retryAfterSeconds) {
        super("busy", "The simulation queue is full; retry later.", List.of());
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
