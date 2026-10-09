package org.omnomnom.dnd.sim.domain.core;


/** Which team a combatant fights for. */
public enum Side implements Coded {
    PARTY("party"),
    ENEMY("enemy");

    private final String code;

    Side(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
