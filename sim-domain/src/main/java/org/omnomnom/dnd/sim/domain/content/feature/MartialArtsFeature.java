package org.omnomnom.dnd.sim.domain.content.feature;

import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.combat.Feature;

/**
 * Martial Arts and Flurry of Blows (Monk): one free bonus-action unarmed strike each turn and, when the monk can
 * spare a Focus point, Flurry of Blows spends one for a second bonus strike. To avoid starving Stunning Strike (which
 * also costs Focus), the monk flurries only while it holds more than one point, keeping one in reserve.
 */
public final class MartialArtsFeature implements Feature {

    private boolean flurryThisTurn;

    @Override
    public String id() {
        return "martial-arts";
    }

    @Override
    public void onTurnStart(Combatant self) {
        // Flurry if we can keep a point in reserve for Stunning Strike.
        flurryThisTurn = self.resourceCount("focus") > 1 && self.spendResource("focus", 1);
    }

    @Override
    public int bonusAttackActions(Combatant self) {
        return 1 + (flurryThisTurn ? 1 : 0);
    }
}
