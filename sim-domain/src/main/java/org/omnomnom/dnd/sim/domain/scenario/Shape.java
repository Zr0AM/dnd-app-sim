package org.omnomnom.dnd.sim.domain.scenario;

import org.omnomnom.dnd.sim.domain.core.Coded;

/** The make-up of a solo scenario's enemies: one foe, a pack of equals, a swarm of minions, or a mixed group. */
public enum Shape implements Coded {
    SINGLE("single"),
    PACK("pack"),
    SWARM("swarm"),
    MIXED("mixed");

    private final String code;

    Shape(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
