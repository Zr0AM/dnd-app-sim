package org.omnomnom.dnd.sim.domain.opt;

import java.util.List;
import org.omnomnom.dnd.sim.domain.content.FightingStyle;

/**
 * A single-class, standard-array build: plain choices that a {@link MartialCatalog} resolves to engine data, so the
 * genome stays serializable. Immutable.
 *
 * @param abilityAssignment a permutation of 0..5: which standard-array value each ability (str, dex, con, int, wis,
 *     cha) gets
 * @param armorName null when unarmored
 * @param fightingStyle may be null (only Fighters keep one after repair)
 */
public record Genome(
        BuildClass classSlug,
        List<Integer> abilityAssignment,
        String weaponName,
        String armorName,
        boolean shield,
        boolean twoHanded,
        FightingStyle fightingStyle) {

    public Genome {
        abilityAssignment = List.copyOf(abilityAssignment);
    }
}
