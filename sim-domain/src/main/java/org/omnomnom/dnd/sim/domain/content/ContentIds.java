package org.omnomnom.dnd.sim.domain.content;

import java.util.Map;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.Condition;
import org.omnomnom.dnd.sim.domain.core.DamageType;

/**
 * Maps the seed database's integer IDs to engine enums. IDs come from {@code seed/01-reference.sql} and are stable
 * (seeded by natural key). Kept explicit, rather than relying on enum order, so a reordered seed fails loudly.
 */
public final class ContentIds {

    private static final Map<Integer, Ability> ABILITY = Map.of(
            1, Ability.STR, 2, Ability.DEX, 3, Ability.CON, 4, Ability.INT, 5, Ability.WIS, 6, Ability.CHA);

    private static final Map<Integer, DamageType> DAMAGE_TYPE = Map.ofEntries(
            Map.entry(1, DamageType.ACID), Map.entry(2, DamageType.BLUDGEONING), Map.entry(3, DamageType.COLD),
            Map.entry(4, DamageType.FIRE), Map.entry(5, DamageType.FORCE), Map.entry(6, DamageType.LIGHTNING),
            Map.entry(7, DamageType.NECROTIC), Map.entry(8, DamageType.PIERCING), Map.entry(9, DamageType.POISON),
            Map.entry(10, DamageType.PSYCHIC), Map.entry(11, DamageType.RADIANT), Map.entry(12, DamageType.SLASHING),
            Map.entry(13, DamageType.THUNDER));

    private static final Map<Integer, Condition> CONDITION = Map.ofEntries(
            Map.entry(1, Condition.BLINDED), Map.entry(2, Condition.CHARMED), Map.entry(3, Condition.DEAFENED),
            Map.entry(4, Condition.EXHAUSTION), Map.entry(5, Condition.FRIGHTENED), Map.entry(6, Condition.GRAPPLED),
            Map.entry(7, Condition.INCAPACITATED), Map.entry(8, Condition.INVISIBLE), Map.entry(9, Condition.PARALYZED),
            Map.entry(10, Condition.PETRIFIED), Map.entry(11, Condition.POISONED), Map.entry(12, Condition.PRONE),
            Map.entry(13, Condition.RESTRAINED), Map.entry(14, Condition.STUNNED), Map.entry(15, Condition.UNCONSCIOUS));

    private ContentIds() {}

    public static Ability abilityById(int id) {
        return lookup(ABILITY, id, "abilityID");
    }

    public static DamageType damageTypeById(int id) {
        return lookup(DAMAGE_TYPE, id, "damageTypeID");
    }

    public static Condition conditionById(int id) {
        return lookup(CONDITION, id, "conditionID");
    }

    private static <T> T lookup(Map<Integer, T> map, int id, String what) {
        T v = map.get(id);
        if (v == null) {
            throw new IllegalArgumentException("unknown " + what + " " + id);
        }
        return v;
    }
}
