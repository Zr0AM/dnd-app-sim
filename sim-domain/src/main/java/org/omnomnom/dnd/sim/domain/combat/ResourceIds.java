package org.omnomnom.dnd.sim.domain.combat;

/**
 * The ids of the limited-use resource pools the engine itself reads or spends (see {@link ResourceSpec}): Rage uses,
 * Hunter's Mark free casts, the Lay on Hands pool, Focus and Sorcery Points, and Wild Shape uses. Content builds the
 * pools with these ids and the encounter, policy and features spend them, so a typo cannot make them disagree.
 */
public final class ResourceIds {

    public static final String RAGE = "rage";
    public static final String HUNTERS_MARK = "hunters-mark";
    public static final String LAY_ON_HANDS = "lay-on-hands";
    public static final String FOCUS = "focus";
    public static final String SORCERY = "sorcery";
    public static final String WILD_SHAPE = "wild-shape";

    private ResourceIds() {}
}
