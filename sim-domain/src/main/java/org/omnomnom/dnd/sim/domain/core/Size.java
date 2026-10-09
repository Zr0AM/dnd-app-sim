package org.omnomnom.dnd.sim.domain.core;

import java.util.Map;

/** Creature sizes with the edge length, in feet, of the square each occupies (SRD CreatureSize). */
public enum Size implements Coded {
    TINY("tiny", 2.5),
    SMALL("small", 5),
    MEDIUM("medium", 5),
    LARGE("large", 10),
    HUGE("huge", 15),
    GARGANTUAN("gargantuan", 20);

    private static final Map<String, Size> BY_CODE = Codes.index(values());

    private final String code;
    private final double spaceFt;

    Size(String code, double spaceFt) {
        this.code = code;
        this.spaceFt = spaceFt;
    }

    @Override
    public String code() {
        return code;
    }

    /** Edge length in feet of the square a creature of this size occupies. */
    public double spaceFt() {
        return spaceFt;
    }

    public static Size fromCode(String code) {
        return Codes.lookup(BY_CODE, code, "size");
    }
}
