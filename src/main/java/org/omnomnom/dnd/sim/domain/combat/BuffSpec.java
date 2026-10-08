package org.omnomnom.dnd.sim.domain.combat;

import org.omnomnom.dnd.sim.domain.dice.Dice;

/**
 * A beneficial effect placed on a creature for a duration (Bless, Haste).
 *
 * @param id stable id so re-applying the same buff refreshes rather than stacks
 * @param source the caster id that granted this (for support-metric attribution)
 * @param attackBonusDice dice added to the recipient's attack rolls; may be null
 * @param saveBonusDice dice added to the recipient's saving throws; may be null
 * @param acBonus flat bonus to Armor Class
 * @param extraAttackAction grants one extra action usable only for a single weapon attack
 * @param concentrationOwner the caster id whose concentration sustains this, if any; may be null
 */
public record BuffSpec(
        String id,
        String source,
        int rounds,
        Dice attackBonusDice,
        Dice saveBonusDice,
        int acBonus,
        boolean extraAttackAction,
        String concentrationOwner) {}
