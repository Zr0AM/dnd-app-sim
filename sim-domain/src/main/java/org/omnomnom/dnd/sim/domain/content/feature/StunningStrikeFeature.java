package org.omnomnom.dnd.sim.domain.content.feature;

import java.util.Optional;
import org.omnomnom.dnd.sim.domain.combat.AttackKind;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.combat.Feature;
import org.omnomnom.dnd.sim.domain.combat.HitEffect;
import org.omnomnom.dnd.sim.domain.combat.OnHitContext;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.Condition;

/**
 * Stunning Strike (Monk): once per turn, on a melee hit, spend 1 Focus to force a Constitution save (DC 8 +
 * proficiency + Wisdom) or the target is Stunned until the start of the monk's next turn (about one round). Denying
 * that turn feeds the control metric, attributed to the monk.
 */
public final class StunningStrikeFeature implements Feature {

    private boolean usedThisTurn;

    @Override
    public String id() {
        return "stunning-strike";
    }

    @Override
    public void onTurnStart(Combatant self) {
        usedThisTurn = false;
    }

    @Override
    public Optional<HitEffect> onHitEffect(OnHitContext ctx) {
        if (usedThisTurn || ctx.weapon().kind() != AttackKind.MELEE) {
            return Optional.empty();
        }
        if (!ctx.self().spendResource("focus", 1)) {
            return Optional.empty();
        }
        usedThisTurn = true;
        int dc = 8 + ctx.self().proficiencyBonus() + ctx.self().abilityMod(Ability.WIS);
        return Optional.of(new HitEffect(Ability.CON, dc, Condition.STUNNED, 1));
    }
}
