package org.omnomnom.dnd.sim.domain.combat;

import org.omnomnom.dnd.sim.domain.dice.Advantage;

/**
 * Context for an on-hit damage rider.
 *
 * @param rollAdvantage the advantage state the attack roll was actually made with
 * @param allyAdjacentToTarget an ally of the attacker (not incapacitated) is within 5 ft of the target
 */
public record OnHitContext(
        Combatant self,
        Combatant target,
        AttackProfile weapon,
        boolean crit,
        Advantage rollAdvantage,
        boolean allyAdjacentToTarget) {}
