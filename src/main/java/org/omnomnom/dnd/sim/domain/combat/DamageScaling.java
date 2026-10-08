package org.omnomnom.dnd.sim.domain.combat;

import org.omnomnom.dnd.sim.domain.dice.Dice;

/** Damage (or healing) dice as a function of the slot level used and the caster's total level. */
@FunctionalInterface
public interface DamageScaling {

    Dice at(int slotLevel, int casterLevel);
}
