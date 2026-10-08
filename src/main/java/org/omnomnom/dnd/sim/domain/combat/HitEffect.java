package org.omnomnom.dnd.sim.domain.combat;

import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.Condition;

/**
 * A save-or-suffer effect a feature imposes on a target it hits (Stunning Strike). The encounter resolves the
 * save against {@code dc} and, on a failure, applies the condition for {@code rounds}, attributing it to the
 * attacker for the control metric.
 */
public record HitEffect(Ability save, int dc, Condition condition, int rounds) {}
