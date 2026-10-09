package org.omnomnom.dnd.sim.domain.content.feature;

import java.util.List;
import org.omnomnom.dnd.sim.domain.combat.ExtraDamage;
import org.omnomnom.dnd.sim.domain.combat.Feature;
import org.omnomnom.dnd.sim.domain.combat.OnHitContext;
import org.omnomnom.dnd.sim.domain.core.DamageType;
import org.omnomnom.dnd.sim.domain.dice.Dice;

/**
 * Hunter's Mark (Ranger): while the ranger concentrates on the mark, every hit it lands on the marked creature deals an
 * extra 1d6 force damage. The mark is placed through the engine ({@code TurnApi.markTarget}) and sustained by
 * concentration; this rider reads the owner's marked target. Moving the mark when the first target dies is a
 * documented simplification left out, as upstream.
 */
public final class HuntersMarkFeature implements Feature {

    @Override
    public String id() {
        return "hunters-mark";
    }

    @Override
    public List<ExtraDamage> onHit(OnHitContext ctx) {
        if (!ctx.target().id().equals(ctx.self().markedTarget())) {
            return List.of();
        }
        return List.of(new ExtraDamage(Dice.of(1, 6), DamageType.FORCE));
    }
}
