package org.omnomnom.dnd.sim.domain.content;

import org.omnomnom.dnd.sim.domain.core.Coded;

/** A reference-party role (plan decision on reference parties). */
public enum Role implements Coded {
    TANK("tank"),
    SUSTAINED_DPS("sustained-dps"),
    BURST("burst"),
    HEALER("healer"),
    CONTROLLER("controller"),
    BUFFER("buffer");

    private final String code;

    Role(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }

    public static Role fromCode(String code) {
        for (Role r : values()) {
            if (r.code.equals(code)) {
                return r;
            }
        }
        throw new IllegalArgumentException("unknown role: " + code);
    }
}
