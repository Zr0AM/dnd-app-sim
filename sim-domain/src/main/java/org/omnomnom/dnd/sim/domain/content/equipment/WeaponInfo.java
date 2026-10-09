package org.omnomnom.dnd.sim.domain.content.equipment;

import java.util.EnumSet;
import java.util.Set;
import org.omnomnom.dnd.sim.domain.combat.AttackKind;
import org.omnomnom.dnd.sim.domain.core.DamageType;

/**
 * A weapon resolved from the seeds.
 *
 * @param versatileDiceCount two-handed dice for a versatile weapon; null if none
 * @param rangeNormalFt normal range for a ranged weapon; null otherwise
 */
public record WeaponInfo(
        String name,
        Category category,
        AttackKind range,
        int diceCount,
        int diceSides,
        DamageType damageType,
        Set<WeaponProperty> properties,
        Integer versatileDiceCount,
        Integer versatileDiceSides,
        Integer rangeNormalFt,
        Integer rangeLongFt) {

    public enum Category {
        SIMPLE,
        MARTIAL
    }

    public WeaponInfo {
        properties = properties.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(properties));
    }

    public boolean has(WeaponProperty p) {
        return properties.contains(p);
    }

    /** Minimal melee weapon for tests and tools. */
    public static WeaponInfo melee(String name, int diceCount, int diceSides, DamageType type, WeaponProperty... props) {
        Set<WeaponProperty> p = EnumSet.noneOf(WeaponProperty.class);
        p.addAll(java.util.List.of(props));
        return new WeaponInfo(name, Category.MARTIAL, AttackKind.MELEE, diceCount, diceSides, type, p, null, null, null, null);
    }
}
