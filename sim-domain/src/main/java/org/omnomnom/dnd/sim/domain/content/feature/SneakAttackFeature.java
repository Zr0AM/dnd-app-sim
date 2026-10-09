package org.omnomnom.dnd.sim.domain.content.feature;

import java.util.List;
import org.omnomnom.dnd.sim.domain.combat.AttackKind;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.combat.ExtraDamage;
import org.omnomnom.dnd.sim.domain.combat.Feature;
import org.omnomnom.dnd.sim.domain.combat.OnHitContext;
import org.omnomnom.dnd.sim.domain.dice.Advantage;
import org.omnomnom.dnd.sim.domain.dice.Dice;

/**
 * Sneak Attack: once per turn, extra dice on a finesse or ranged weapon hit made with advantage, or with an ally next
 * to the target and no disadvantage. Exact.
 */
public final class SneakAttackFeature implements Feature {

    private final int diceCount;
    private boolean usedThisTurn;

    public SneakAttackFeature(int diceCount) {
        this.diceCount = diceCount;
    }

    @Override
    public String id() {
        return "sneak-attack";
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
        boolean qualifies = ctx.weapon().kind() == AttackKind.RANGED || ctx.weapon().finesse();
        if (!qualifies) {
            return List.of();
        }
        boolean eligible = ctx.rollAdvantage() == Advantage.ADVANTAGE
                || (ctx.allyAdjacentToTarget() && ctx.rollAdvantage() != Advantage.DISADVANTAGE);
        if (!eligible) {
            return List.of();
        }
        usedThisTurn = true;
        return List.of(new ExtraDamage(Dice.of(diceCount, 6), ctx.weapon().damageType()));
    }
}
