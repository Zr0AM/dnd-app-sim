package org.omnomnom.dnd.sim.domain.content;

import java.util.List;
import java.util.Map;
import org.omnomnom.dnd.sim.domain.combat.AttackProfile;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.AbilityScores;
import org.omnomnom.dnd.sim.domain.core.DamageResponse;
import org.omnomnom.dnd.sim.domain.core.DamageType;

/** An engine-ready monster, independent of placement. Immutable, so one template spawns any number of fights. */
public record MonsterTemplate(
        String slug,
        String name,
        double cr,
        int ac,
        int maxHp,
        AbilityScores abilities,
        int speedFt,
        Map<Ability, Integer> saveBonuses,
        Map<DamageType, DamageResponse> damageResponses,
        List<AttackProfile> attacks,
        List<MultiattackEntry> multiattack,
        int legendaryActions) {

    public MonsterTemplate {
        saveBonuses = Map.copyOf(saveBonuses);
        damageResponses = Map.copyOf(damageResponses);
        attacks = List.copyOf(attacks);
        multiattack = List.copyOf(multiattack);
    }

    /** Multiattack as an attack name to repeat and how many times. */
    public record MultiattackEntry(String action, int count) {}
}
