package org.omnomnom.dnd.sim.domain.combat;

import org.omnomnom.dnd.sim.domain.dice.Dice;

/** Dice contributed by an active buff, with the buff id and the caster it should be attributed to. */
public record BuffBonus(Dice dice, String id, String source) {}
