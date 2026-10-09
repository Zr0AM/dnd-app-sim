package org.omnomnom.dnd.sim.domain.opt.genome;

import java.util.Arrays;
import java.util.List;
import org.omnomnom.dnd.sim.domain.core.Coded;

/** The classes a genome can pick: six martial and six caster. The declaration order is part of the seeded behavior. */
public enum BuildClass implements Coded {
    FIGHTER("fighter", false),
    BARBARIAN("barbarian", false),
    ROGUE("rogue", false),
    RANGER("ranger", false),
    PALADIN("paladin", false),
    MONK("monk", false),
    WIZARD("wizard", true),
    CLERIC("cleric", true),
    BARD("bard", true),
    SORCERER("sorcerer", true),
    WARLOCK("warlock", true),
    DRUID("druid", true);

    /** All classes in declaration order (martial first), as the random picks index them. */
    public static final List<BuildClass> ALL_CLASSES = List.of(values());

    public static final List<BuildClass> MARTIAL_CLASSES = Arrays.stream(values()).filter(c -> !c.caster).toList();
    public static final List<BuildClass> CASTER_CLASSES = Arrays.stream(values()).filter(c -> c.caster).toList();

    private final String code;
    private final boolean caster;

    BuildClass(String code, boolean caster) {
        this.code = code;
        this.caster = caster;
    }

    @Override
    public String code() {
        return code;
    }

    /** Casters use a fixed gear and spell package; only their class and abilities vary. */
    public boolean isCaster() {
        return caster;
    }

    public static BuildClass fromCode(String code) {
        for (BuildClass c : values()) {
            if (c.code.equals(code)) {
                return c;
            }
        }
        throw new IllegalArgumentException("unknown class: " + code);
    }
}
