package org.omnomnom.dnd.sim.domain.content;

import java.util.List;

/**
 * All seed rows for one monster, as gathered by a {@link ContentSource}. The row records mirror the seed tables the
 * monster compiler reads; nullable columns are boxed.
 */
public record MonsterSource(
        MonsterRow monster,
        List<ActionRow> actions,
        List<DamageRow> damage,
        List<SaveRow> saves,
        List<DefenseRow> defenses,
        List<SpeedRow> speeds) {

    public MonsterSource {
        actions = List.copyOf(actions);
        damage = List.copyOf(damage);
        saves = List.copyOf(saves);
        defenses = List.copyOf(defenses);
        speeds = List.copyOf(speeds);
    }

    public MonsterSource(MonsterRow monster, List<ActionRow> actions, List<DamageRow> damage) {
        this(monster, actions, damage, List.of(), List.of(), List.of());
    }

    public record MonsterRow(
            String monsterSlug,
            String monsterName,
            int monsterAc,
            int monsterHpAvg,
            int monsterStr,
            int monsterDex,
            int monsterCon,
            int monsterInt,
            int monsterWis,
            int monsterCha,
            double crValue) {}

    /** {@code attackKind} is {@code melee}, {@code ranged}, {@code melee_or_ranged}, or null for a non-attack. */
    public record ActionRow(
            int monsterActionID,
            String actionSection,
            String actionName,
            String attackKind,
            Integer attackBonus,
            Integer attackReachFt,
            Integer attackRangeFt,
            Integer attackRangeLongFt) {}

    public record DamageRow(
            int monsterActionID,
            int damageIndex,
            Integer damageDiceCount,
            Integer damageDiceSides,
            Integer damageBonus,
            int damageAvg,
            int damageTypeID) {}

    public record SaveRow(int abilityID, int saveBonus) {}

    /** {@code defenseKind} is {@code resistance}, {@code vulnerability} or {@code immunity}. */
    public record DefenseRow(String defenseKind, Integer damageTypeID, Integer conditionID) {}

    public record SpeedRow(String speedMode, int speedFt) {}
}
