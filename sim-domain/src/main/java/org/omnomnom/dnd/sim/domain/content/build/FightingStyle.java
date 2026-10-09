package org.omnomnom.dnd.sim.domain.content.build;

import org.omnomnom.dnd.sim.domain.core.Coded;

/** Numeric fighting styles the character compiler models. */
public enum FightingStyle implements Coded {
    ARCHERY("archery"),
    DEFENSE("defense"),
    GREAT_WEAPON("great-weapon"),
    TWO_WEAPON("two-weapon");

    private final String code;

    FightingStyle(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
