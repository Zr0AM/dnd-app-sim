package org.omnomnom.dnd.sim.domain.content.build;

import java.util.EnumSet;
import java.util.List;
import org.omnomnom.dnd.sim.domain.combat.AttackKind;
import org.omnomnom.dnd.sim.domain.combat.AttackProfile;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.combat.CombatantSpec;
import org.omnomnom.dnd.sim.domain.combat.spell.SpellcastingSpec;
import org.omnomnom.dnd.sim.domain.content.ClassInfo;
import org.omnomnom.dnd.sim.domain.content.equipment.WeaponInfo;
import org.omnomnom.dnd.sim.domain.content.equipment.WeaponProperty;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.CoreRules;
import org.omnomnom.dnd.sim.domain.dice.Dice;

/**
 * Caster build compiler: turns a resolved caster build into a {@link Combatant} with spellcasting, a backup weapon and
 * the slot table from the seeds. Spell selection is fixed by the caller (not evolved).
 */
public final class CasterCompiler {

    private CasterCompiler() {}

    /** AC from armor (with its Dex cap) or unarmored, plus a shield. */
    static int casterAc(CasterBuildSpec spec) {
        int dexMod = spec.abilities().modifier(Ability.DEX);
        int ac;
        if (spec.armor() != null) {
            int dexPart = spec.armor().addsDex()
                    ? (spec.armor().dexCap() != null ? Math.min(dexMod, spec.armor().dexCap()) : dexMod)
                    : 0;
            ac = spec.armor().baseAc() + dexPart;
        } else if (spec.unarmoredAcAbility() != null) {
            ac = 10 + dexMod + spec.abilities().modifier(spec.unarmoredAcAbility());
        } else {
            ac = 10 + dexMod;
        }
        return ac + (spec.shield() ? 2 : 0);
    }

    /** A simple backup weapon attack (the caster rarely uses it, but may). */
    static AttackProfile backupAttack(CasterBuildSpec spec) {
        WeaponInfo w = spec.weapon();
        Ability ability;
        if (w.has(WeaponProperty.FINESSE)) {
            ability = spec.abilities().modifier(Ability.STR) >= spec.abilities().modifier(Ability.DEX) ? Ability.STR : Ability.DEX;
        } else {
            ability = w.range() == AttackKind.RANGED ? Ability.DEX : Ability.STR;
        }
        int abilityMod = spec.abilities().modifier(ability);
        return new AttackProfile(
                w.name(), w.range(),
                w.range() == AttackKind.MELEE ? 5 : null,
                w.range() == AttackKind.RANGED ? w.rangeNormalFt() : null,
                null,
                abilityMod + CoreRules.proficiencyBonus(spec.level()),
                Dice.of(w.diceCount(), w.diceSides(), abilityMod),
                w.damageType(), List.of(), null, false);
    }

    /** Compile a caster build into a {@link Combatant} with spellcasting. */
    public static Combatant compile(CasterBuildSpec spec) {
        int conMod = spec.abilities().modifier(Ability.CON);
        return new Combatant(CombatantSpec.builder(
                        spec.id() != null ? spec.id() : spec.classInfo().slug(),
                        spec.name(), spec.side(), spec.level(), spec.abilities(), casterAc(spec),
                        CharacterCompiler.maxHitPoints(spec.classInfo().hitDieSides(), spec.level(), conMod) + spec.extraHp())
                .saveProficiencies(ClassInfo.saveSet(spec.classInfo()))
                .attacks(List.of(backupAttack(spec)))
                .resources(spec.resources())
                .features(spec.features())
                .spellcasting(new SpellcastingSpec(spec.spellAbility(), spec.slots(), spec.cantrips(), spec.spells(), spec.shortRestSlots()))
                .position(spec.position())
                .build());
    }
}
