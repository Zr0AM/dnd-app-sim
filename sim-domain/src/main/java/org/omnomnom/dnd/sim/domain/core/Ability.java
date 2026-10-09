package org.omnomnom.dnd.sim.domain.core;

import java.util.Map;

/** The six ability scores, in the SRD order. */
public enum Ability implements Coded {
    STR("str"),
    DEX("dex"),
    CON("con"),
    INT("int"),
    WIS("wis"),
    CHA("cha");

    private static final Map<String, Ability> BY_CODE = Codes.index(values());

    private final String code;

    Ability(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }

    public static Ability fromCode(String code) {
        return Codes.lookup(BY_CODE, code, "ability");
    }
}
