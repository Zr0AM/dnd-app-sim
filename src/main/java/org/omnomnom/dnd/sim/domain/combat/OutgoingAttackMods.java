package org.omnomnom.dnd.sim.domain.combat;

/** Modifiers a feature contributes to its owner's outgoing attack roll. */
public record OutgoingAttackMods(boolean advantage, boolean disadvantage, int toHit) {

    public static final OutgoingAttackMods NONE = new OutgoingAttackMods(false, false, 0);
}
