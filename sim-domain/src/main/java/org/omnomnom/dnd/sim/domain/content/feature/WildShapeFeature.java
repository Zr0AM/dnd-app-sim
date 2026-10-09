package org.omnomnom.dnd.sim.domain.content.feature;

import org.omnomnom.dnd.sim.domain.combat.ActiveForm;
import org.omnomnom.dnd.sim.domain.combat.AttackProfile;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.combat.Feature;
import org.omnomnom.dnd.sim.domain.combat.ResourceIds;

/**
 * Wild Shape (Druid). At the start of a turn while it has a use, the druid assumes a beast form: it gains the form's
 * Hit Points as temporary HP and takes on the form's Armor Class and natural attack until that pool is gone, then
 * re-forms if a use remains.
 *
 * <p>Documented simplifications (as upstream): one representative form rather than the beast catalog, and
 * spellcasting is not suppressed while shaped.
 */
public final class WildShapeFeature implements Feature {

    /** A beast form: its own HP (as temp HP), Armor Class and natural attack. */
    public record BeastForm(int hp, int ac, AttackProfile attack) {}

    private final BeastForm form;

    public WildShapeFeature(BeastForm form) {
        this.form = form;
    }

    @Override
    public String id() {
        return "wild-shape";
    }

    @Override
    public void onTurnStart(Combatant self) {
        if (self.tempHp() > 0) {
            return; // still in a form with HP to spare
        }
        if (self.spendResource(ResourceIds.WILD_SHAPE, 1)) {
            self.grantTempHp(form.hp());
            self.enterForm(new ActiveForm(form.ac(), form.attack()));
        }
    }
}
