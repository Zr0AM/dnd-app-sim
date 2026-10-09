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
 * Rage: resistance to bludgeoning, piercing and slashing and a damage bonus on melee attacks while raging.
 *
 * <p>Documented simplification (as upstream): Rage auto-activates on the owner's first turn if a use is available and
 * stays active for the rest of the fight, since the extend-each-turn rule almost always keeps it up in combat. The
 * bonus is applied to every melee attack, which assumes Strength-based melee for these builds.
 */
public final class RageFeature implements Feature {

    private final int damageBonus;
    private boolean raging;

    public RageFeature(int damageBonus) {
        this.damageBonus = damageBonus;
    }

    @Override
    public String id() {
        return "rage";
    }

    @Override
    public void onTurnStart(Combatant self) {
        if (!raging && self.spendResource("rage", 1)) {
            raging = true;
        }
    }

    @Override
    public boolean resistsDamage(Combatant self, DamageType type) {
        return raging
                && (type == DamageType.BLUDGEONING || type == DamageType.PIERCING || type == DamageType.SLASHING);
    }

    @Override
    public List<ExtraDamage> onHit(OnHitContext ctx) {
        if (!raging || ctx.weapon().kind() != AttackKind.MELEE) {
            return List.of();
        }
        return List.of(new ExtraDamage(Dice.of(0, 1, damageBonus), ctx.weapon().damageType()));
    }

    public boolean isRaging() {
        return raging;
    }
}
