package org.omnomnom.dnd.sim.domain.combat.spell;

import java.util.function.IntUnaryOperator;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.Condition;
import org.omnomnom.dnd.sim.domain.core.DamageType;
import org.omnomnom.dnd.sim.domain.dice.Dice;

/**
 * The mechanical kinds of spell the engine can resolve. Spell numbers are authored data (never parsed), as in the
 * upstream effect-format spec. Nullable components mean "absent".
 */
public sealed interface SpellKind {

    /**
     * A spell attack per ray (Fire Bolt, Scorching Ray, Eldritch Blast).
     *
     * @param rays separate spell attacks at the spell's base level (Scorching Ray fires several)
     * @param raysPerUpcast extra rays per slot level above the spell's base level
     * @param beams beam count as a function of caster level, overriding {@code rays} (Eldritch Blast); may be null
     * @param addSpellMod add the caster's spellcasting modifier to each attack's damage (Agonizing Blast)
     */
    record AttackDamage(
            DamageScaling damage,
            DamageType damageType,
            int rays,
            int raysPerUpcast,
            IntUnaryOperator beams,
            boolean addSpellMod)
            implements SpellKind {

        public static AttackDamage of(DamageScaling damage, DamageType damageType) {
            return new AttackDamage(damage, damageType, 1, 0, null, false);
        }

        public AttackDamage withRays(int v) {
            return new AttackDamage(damage, damageType, v, raysPerUpcast, beams, addSpellMod);
        }

        public AttackDamage withRaysPerUpcast(int v) {
            return new AttackDamage(damage, damageType, rays, v, beams, addSpellMod);
        }

        public AttackDamage withBeams(IntUnaryOperator v) {
            return new AttackDamage(damage, damageType, rays, raysPerUpcast, v, addSpellMod);
        }

        public AttackDamage withAddSpellMod(boolean v) {
            return new AttackDamage(damage, damageType, rays, raysPerUpcast, beams, v);
        }
    }

    enum OnSuccess {
        HALF,
        NONE
    }

    /**
     * Save-or-take-damage (Fireball, Burning Hands, Moonbeam).
     *
     * @param aoeRadiusFt if non-null, an area effect hitting every enemy within this radius of the point
     * @param selfOrigin the area is centered on the caster rather than a chosen point
     */
    record SaveDamage(
            Ability save,
            DamageScaling damage,
            DamageType damageType,
            OnSuccess onSuccess,
            Integer aoeRadiusFt,
            boolean selfOrigin)
            implements SpellKind {

        public static SaveDamage of(Ability save, DamageScaling damage, DamageType damageType, OnSuccess onSuccess) {
            return new SaveDamage(save, damage, damageType, onSuccess, null, false);
        }

        public SaveDamage withAoe(int radiusFt, boolean selfOrigin) {
            return new SaveDamage(save, damage, damageType, onSuccess, radiusFt, selfOrigin);
        }
    }

    /** Healing (Cure Wounds, Healing Word). */
    record Heal(DamageScaling dice, boolean addSpellMod) implements SpellKind {}

    /**
     * Save-or-condition (Hold Person, Hypnotic Pattern).
     *
     * @param rounds duration in rounds (a minute is 10 rounds)
     * @param repeatSaveEndsEffect the victim repeats the save at the end of its turns to end the effect
     * @param aoeRadiusFt area control hitting every enemy within this radius of the aim point; may be null
     * @param onlyType restrict to a creature type (Hold Person: Humanoid); may be null
     */
    record Control(
            Ability save,
            Condition condition,
            int rounds,
            boolean repeatSaveEndsEffect,
            Integer aoeRadiusFt,
            String onlyType)
            implements SpellKind {}

    /**
     * A beneficial effect placed on allies for a duration (Bless, Haste). Each modifier is optional so one kind
     * covers roll-rider buffs (Bless) and action/defence buffs (Haste).
     *
     * @param buffId stable id so applying the same buff twice refreshes instead of stacking
     * @param maxTargets how many allies the cast can cover (Bless 3, Haste 1), nearest first
     * @param attackBonusDice dice added to the recipient's attack rolls, rolled per attack; may be null
     * @param saveBonusDice dice added to the recipient's saving throws; may be null
     * @param acBonus flat bonus to Armor Class (Haste: +2)
     * @param extraAttackAction grants one extra action usable only for a single weapon attack (Haste)
     */
    record Buff(
            String buffId,
            int maxTargets,
            int rounds,
            Dice attackBonusDice,
            Dice saveBonusDice,
            int acBonus,
            boolean extraAttackAction)
            implements SpellKind {}
}
