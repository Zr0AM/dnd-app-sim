package org.omnomnom.dnd.sim.domain.combat;

import org.omnomnom.dnd.sim.domain.core.Condition;

/**
 * A condition applied for a duration.
 *
 * @param source the combatant id that caused this (for control-metric attribution)
 * @param repeatSave a save the victim repeats to end the effect early; may be null
 * @param concentrationOwner the caster id whose concentration sustains this, if any; may be null
 */
public record TimedConditionSpec(
        Condition condition, String source, int rounds, RepeatSave repeatSave, String concentrationOwner) {}
