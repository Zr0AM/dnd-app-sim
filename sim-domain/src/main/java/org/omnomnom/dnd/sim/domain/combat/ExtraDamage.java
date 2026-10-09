package org.omnomnom.dnd.sim.domain.combat;

import org.omnomnom.dnd.sim.domain.core.DamageType;
import org.omnomnom.dnd.sim.domain.dice.Dice;

/** An additional damage component on an attack, of its own type ("plus 2d6 fire"). */
public record ExtraDamage(Dice damage, DamageType type) {}
