package org.omnomnom.dnd.sim.domain.content.feature;

import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.combat.Feature;
import org.omnomnom.dnd.sim.domain.core.Ability;

/**
 * Dark One's Blessing (Fiend Warlock): when the warlock reduces an enemy to 0 HP it gains temporary Hit Points equal to
 * its Charisma modifier plus its level.
 */
public final class DarkOnesBlessingFeature implements Feature {

    @Override
    public String id() {
        return "dark-ones-blessing";
    }

    @Override
    public void onKill(Combatant self, Combatant victim) {
        self.grantTempHp(Math.max(1, self.abilityMod(Ability.CHA) + self.level()));
    }
}
