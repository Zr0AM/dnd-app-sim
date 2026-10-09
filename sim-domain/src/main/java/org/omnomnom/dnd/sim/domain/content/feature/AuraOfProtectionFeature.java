package org.omnomnom.dnd.sim.domain.content.feature;

import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.combat.Feature;
import org.omnomnom.dnd.sim.domain.core.Ability;

/**
 * Aura of Protection (Paladin, level 6+): every ally (and the paladin) within 10 ft adds the paladin's Charisma
 * modifier to its saving throws. The encounter collects the bonus through {@link #allySaveBonus}, so the engine
 * never needs to know this feature exists.
 */
public final class AuraOfProtectionFeature implements Feature {

    public static final String FEATURE_ID = "aura-of-protection";

    private static final int RANGE_FT = 10;

    @Override
    public String id() {
        return FEATURE_ID;
    }

    @Override
    public int allySaveBonus(Combatant self, Combatant saver, int distanceFt) {
        return distanceFt <= RANGE_FT ? self.abilityMod(Ability.CHA) : 0;
    }
}
