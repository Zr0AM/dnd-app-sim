package org.omnomnom.dnd.sim.domain.scenario;

import org.omnomnom.dnd.sim.domain.core.Coded;

public enum Difficulty implements Coded {
    LOW("low"),
    MODERATE("moderate"),
    HIGH("high");

    private final String code;

    Difficulty(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
