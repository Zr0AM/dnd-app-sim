package org.omnomnom.dnd.sim.domain.content;

/**
 * Numeric feature values pulled from the class tables for a class at a level. A zero means "none": the feature is
 * absent or its table column does not apply to the class.
 */
public record BuildProgression(int rageUses, int rageDamageBonus, int sneakAttackDice, int extraAttacks) {

    public static final BuildProgression NONE = new BuildProgression(0, 0, 0, 0);
}
