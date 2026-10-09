package org.omnomnom.dnd.sim.domain.content.feature;

import java.util.List;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.combat.ExtraDamage;
import org.omnomnom.dnd.sim.domain.combat.Feature;
import org.omnomnom.dnd.sim.domain.combat.OnHitContext;
import org.omnomnom.dnd.sim.domain.dice.Dice;

/** Colossus Slayer (Hunter Ranger): once per turn, +1d8 to a hit on a target that is missing Hit Points. */
public final class ColossusSlayerFeature implements Feature {

    private boolean usedThisTurn;

    @Override
    public String id() {
        return "colossus-slayer";
    }

    @Override
    public void onTurnStart(Combatant self) {
        usedThisTurn = false;
    }

    @Override
    public List<ExtraDamage> onHit(OnHitContext ctx) {
        if (usedThisTurn) {
            return List.of();
        }
        // Only a target already missing Hit Points qualifies.
        if (ctx.target().hp() >= ctx.target().maxHp()) {
            return List.of();
        }
        usedThisTurn = true;
        return List.of(new ExtraDamage(Dice.of(1, 8), ctx.weapon().damageType()));
    }
}
