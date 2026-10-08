package org.omnomnom.dnd.sim.domain.core;

import java.util.Map;

/** The 13 damage types (SRD DamageType), alphabetical. */
public enum DamageType implements Coded {
    ACID("acid"),
    BLUDGEONING("bludgeoning"),
    COLD("cold"),
    FIRE("fire"),
    FORCE("force"),
    LIGHTNING("lightning"),
    NECROTIC("necrotic"),
    PIERCING("piercing"),
    POISON("poison"),
    PSYCHIC("psychic"),
    RADIANT("radiant"),
    SLASHING("slashing"),
    THUNDER("thunder");

    private static final Map<String, DamageType> BY_CODE = Codes.index(values());

    private final String code;

    DamageType(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }

    public static DamageType fromCode(String code) {
        return Codes.lookup(BY_CODE, code, "damage type");
    }
}
