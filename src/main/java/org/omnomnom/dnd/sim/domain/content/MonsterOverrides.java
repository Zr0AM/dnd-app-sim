package org.omnomnom.dnd.sim.domain.content;

import java.util.List;

/**
 * Hand-authored additions the structured seed data cannot express: Multiattack counts (prose in the stat block) and
 * legendary actions per round.
 */
public record MonsterOverrides(List<MonsterTemplate.MultiattackEntry> multiattack, int legendaryActions) {

    public static final MonsterOverrides NONE = new MonsterOverrides(List.of(), 0);

    public MonsterOverrides {
        multiattack = List.copyOf(multiattack);
    }
}
