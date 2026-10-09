package org.omnomnom.dnd.sim.domain.combat.spell;

import org.omnomnom.dnd.sim.domain.dice.Dice;

/**
 * A spell the engine can run. Spells are immutable declarative data shared across runs; the encounter executes
 * them.
 *
 * @param level 0 for a cantrip
 */
public record Spell(
        String id, String name, int level, CastingTime action, int rangeFt, boolean concentration, SpellKind kind) {

    public enum CastingTime {
        ACTION,
        BONUS
    }

    /** Healing and buffs target allies; everything else targets enemies. */
    public boolean targetsAllies() {
        return kind instanceof SpellKind.Heal || kind instanceof SpellKind.Buff;
    }

    /** Cantrip dice that gain a die at levels 5, 11 and 17 (Fire Bolt, Sacred Flame, ...). */
    public static DamageScaling cantripDice(int baseCount, int sides) {
        return (slot, level) -> {
            int extra = (level >= 5 ? 1 : 0) + (level >= 11 ? 1 : 0) + (level >= 17 ? 1 : 0);
            return Dice.of(baseCount + extra, sides);
        };
    }

    /** Leveled dice that gain {@code perUpcast} dice per slot level above {@code baseLevel}. */
    public static DamageScaling upcastDice(int baseLevel, int baseCount, int sides, int perUpcast) {
        return (slot, level) ->
                Dice.of(baseCount + Math.max(0, slot - baseLevel) * perUpcast, sides);
    }

    public static DamageScaling upcastDice(int baseLevel, int baseCount, int sides) {
        return upcastDice(baseLevel, baseCount, sides, 1);
    }

    /** Rays fired for an attack-damage spell at a given slot level. */
    public static int raysAt(SpellKind.AttackDamage kind, int slotLevel, int baseLevel) {
        return kind.rays() + (kind.raysPerUpcast() > 0 ? Math.max(0, slotLevel - baseLevel) * kind.raysPerUpcast() : 0);
    }
}
