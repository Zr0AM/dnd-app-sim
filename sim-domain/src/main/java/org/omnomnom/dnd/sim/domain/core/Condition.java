package org.omnomnom.dnd.sim.domain.core;

import java.util.Map;

/** The 15 conditions (SRD Condition). Exhaustion also carries a level elsewhere. */
public enum Condition implements Coded {
    BLINDED("blinded"),
    CHARMED("charmed"),
    DEAFENED("deafened"),
    EXHAUSTION("exhaustion"),
    FRIGHTENED("frightened"),
    GRAPPLED("grappled"),
    INCAPACITATED("incapacitated"),
    INVISIBLE("invisible"),
    PARALYZED("paralyzed"),
    PETRIFIED("petrified"),
    POISONED("poisoned"),
    PRONE("prone"),
    RESTRAINED("restrained"),
    STUNNED("stunned"),
    UNCONSCIOUS("unconscious");

    private static final Map<String, Condition> BY_CODE = Codes.index(values());

    private final String code;

    Condition(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }

    public static Condition fromCode(String code) {
        return Codes.lookup(BY_CODE, code, "condition");
    }
}
