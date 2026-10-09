package org.omnomnom.dnd.sim.domain.combat;

/**
 * The ids of features the engine itself looks for, as opposed to features it merely runs through the {@link Feature}
 * hooks. The content layer gives the feature this id and the encounter finds it by it.
 */
public final class FeatureIds {

    /** Paladin Aura of Protection: allies near the paladin add its Charisma modifier to saving throws. */
    public static final String AURA_OF_PROTECTION = "aura-of-protection";

    private FeatureIds() {}
}
