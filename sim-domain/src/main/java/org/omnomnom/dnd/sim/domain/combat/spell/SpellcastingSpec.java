package org.omnomnom.dnd.sim.domain.combat.spell;

import java.util.List;
import org.omnomnom.dnd.sim.domain.core.Ability;

/**
 * @param slots spell slots by spell level
 * @param shortRestSlots Warlock Pact Magic: slots recharge on a Short Rest, not only a Long Rest
 */
public record SpellcastingSpec(
        Ability ability, List<Slot> slots, List<Spell> cantrips, List<Spell> spells, boolean shortRestSlots) {

    public record Slot(int level, int count) {}

    public SpellcastingSpec {
        slots = List.copyOf(slots);
        cantrips = List.copyOf(cantrips);
        spells = List.copyOf(spells);
    }
}
