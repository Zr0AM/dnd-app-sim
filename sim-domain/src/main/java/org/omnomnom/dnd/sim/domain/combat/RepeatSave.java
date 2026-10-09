package org.omnomnom.dnd.sim.domain.combat;

import org.omnomnom.dnd.sim.domain.core.Ability;

/** A save the victim repeats each turn to end a timed condition early. */
public record RepeatSave(Ability ability, int dc, boolean endsOnSuccess) {}
