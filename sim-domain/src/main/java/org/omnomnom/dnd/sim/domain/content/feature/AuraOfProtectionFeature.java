package org.omnomnom.dnd.sim.domain.content.feature;

import org.omnomnom.dnd.sim.domain.combat.Feature;

/**
 * Aura of Protection (Paladin, level 6+): a marker feature. While present, the encounter gives every ally (and the
 * paladin) within 10 ft a bonus to saving throws equal to the paladin's Charisma modifier; the bonus itself is applied
 * by the encounter at save time, so this class only marks the aura's presence.
 */
public final class AuraOfProtectionFeature implements Feature {

    public static final String ID = "aura-of-protection";

    @Override
    public String id() {
        return ID;
    }
}
