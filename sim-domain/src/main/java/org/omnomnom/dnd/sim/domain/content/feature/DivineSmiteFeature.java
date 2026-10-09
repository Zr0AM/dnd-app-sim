package org.omnomnom.dnd.sim.domain.content.feature;

import java.util.List;
import org.omnomnom.dnd.sim.domain.combat.AttackKind;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.combat.ExtraDamage;
import org.omnomnom.dnd.sim.domain.combat.Feature;
import org.omnomnom.dnd.sim.domain.combat.OnHitContext;
import org.omnomnom.dnd.sim.domain.core.DamageType;
import org.omnomnom.dnd.sim.domain.dice.Dice;

/**
 * Divine Smite (Paladin): once per turn, on a melee hit, spend the lowest available spell slot to deal radiant damage
 * - 2d8, plus 1d8 per slot level above 1st.
 *
 * <p>Documented simplification (as upstream): it fires on the first melee hit of the turn that has a slot to spend (it
 * does not hold the smite for a later crit), and the extra damage against fiends and undead is omitted (combatants
 * carry no creature type). Heal and buff steps run before attacks, so those spells get first call on the slots.
 */
public final class DivineSmiteFeature implements Feature {

    private boolean usedThisTurn;

    @Override
    public String id() {
        return "divine-smite";
    }

    @Override
    public void onTurnStart(Combatant self) {
        usedThisTurn = false;
    }

    @Override
    public List<ExtraDamage> onHit(OnHitContext ctx) {
        if (usedThisTurn || ctx.weapon().kind() != AttackKind.MELEE) {
            return List.of();
        }
        List<Integer> levels = ctx.self().availableSlotLevels();
        if (levels.isEmpty() || !ctx.self().spendSlot(levels.get(0))) {
            return List.of();
        }
        usedThisTurn = true;
        int diceCount = 2 + Math.max(0, levels.get(0) - 1);
        return List.of(new ExtraDamage(Dice.of(diceCount, 8), DamageType.RADIANT));
    }
}
