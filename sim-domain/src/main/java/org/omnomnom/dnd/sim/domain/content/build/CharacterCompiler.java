package org.omnomnom.dnd.sim.domain.content.build;

import java.util.ArrayList;
import java.util.List;
import org.omnomnom.dnd.sim.domain.combat.AttackKind;
import org.omnomnom.dnd.sim.domain.combat.AttackProfile;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.combat.CombatantSpec;
import org.omnomnom.dnd.sim.domain.combat.FeatureFactory;
import org.omnomnom.dnd.sim.domain.combat.Recharge;
import org.omnomnom.dnd.sim.domain.combat.ResourceIds;
import org.omnomnom.dnd.sim.domain.combat.ResourceSpec;
import org.omnomnom.dnd.sim.domain.content.BuildProgression;
import org.omnomnom.dnd.sim.domain.content.ClassInfo;
import org.omnomnom.dnd.sim.domain.content.equipment.WeaponInfo;
import org.omnomnom.dnd.sim.domain.content.equipment.WeaponProperty;
import org.omnomnom.dnd.sim.domain.content.feature.AuraOfProtectionFeature;
import org.omnomnom.dnd.sim.domain.content.feature.ColossusSlayerFeature;
import org.omnomnom.dnd.sim.domain.content.feature.DivineSmiteFeature;
import org.omnomnom.dnd.sim.domain.content.feature.HuntersMarkFeature;
import org.omnomnom.dnd.sim.domain.content.feature.MartialArtsFeature;
import org.omnomnom.dnd.sim.domain.content.feature.RageFeature;
import org.omnomnom.dnd.sim.domain.content.feature.RecklessAttackFeature;
import org.omnomnom.dnd.sim.domain.content.feature.SneakAttackFeature;
import org.omnomnom.dnd.sim.domain.content.feature.StunningStrikeFeature;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.CoreRules;
import org.omnomnom.dnd.sim.domain.dice.Dice;

/**
 * The character-build compiler: turns a resolved martial build into an engine {@link Combatant}.
 *
 * <p>Scope: single-class martial builds with correct Hit Points, Armor Class, saving throws, one weapon attack, the
 * numeric fighting styles, and the class features that attach to it (Rage, Sneak Attack, Hunter's Mark, Divine Smite,
 * Martial Arts, ...). It is pure: it takes resolved data objects so it can be tested without a database.
 */
public final class CharacterCompiler {

    private CharacterCompiler() {}

    /** Fixed Hit Points by class: max die at level 1, then (die/2 + 1) + Con each later level, at least 1 per level. */
    public static int maxHitPoints(int hitDieSides, int level, int conMod) {
        if (level < 1) {
            throw new IllegalArgumentException("level must be >= 1, got " + level);
        }
        int fixed = hitDieSides / 2 + 1;
        int hp = hitDieSides + conMod; // level 1: full die
        for (int l = 2; l <= level; l++) {
            hp += Math.max(1, fixed + conMod);
        }
        return Math.max(1, hp);
    }

    /** Armor Class from armor, Unarmored Defense, a shield and the Defense fighting style. */
    public static int armorClass(BuildSpec spec) {
        int dexMod = spec.abilities().modifier(Ability.DEX);
        int ac;
        if (spec.armor() != null) {
            int dexPart = spec.armor().addsDex()
                    ? (spec.armor().dexCap() != null ? Math.min(dexMod, spec.armor().dexCap()) : dexMod)
                    : 0;
            ac = spec.armor().baseAc() + dexPart;
        } else if (spec.unarmoredDefense() == UnarmoredDefense.BARBARIAN) {
            ac = 10 + dexMod + spec.abilities().modifier(Ability.CON);
        } else if (spec.unarmoredDefense() == UnarmoredDefense.MONK) {
            ac = 10 + dexMod + spec.abilities().modifier(Ability.WIS);
        } else {
            ac = 10 + dexMod;
        }
        if (spec.shield()) {
            ac += 2;
        }
        // Defense requires wearing armor (Unarmored Defense does not count).
        if (spec.fightingStyle() == FightingStyle.DEFENSE && spec.armor() != null) {
            ac += 1;
        }
        return ac;
    }

    /** The ability used for a weapon's attack and damage (Str, Dex, or the better for Finesse). */
    public static Ability weaponAbility(BuildSpec spec) {
        if (spec.weapon().range() == AttackKind.RANGED) {
            return Ability.DEX;
        }
        if (spec.weapon().has(WeaponProperty.FINESSE)) {
            return spec.abilities().modifier(Ability.STR) >= spec.abilities().modifier(Ability.DEX) ? Ability.STR : Ability.DEX;
        }
        return Ability.STR;
    }

    /** Build the weapon attack profile (to-hit, damage, crit range). */
    public static AttackProfile weaponAttack(BuildSpec spec) {
        int pb = CoreRules.proficiencyBonus(spec.level());
        Ability ability = weaponAbility(spec);
        int abilityMod = spec.abilities().modifier(ability);
        WeaponInfo weapon = spec.weapon();

        int toHit = abilityMod + (spec.weaponProficient() ? pb : 0);
        if (spec.fightingStyle() == FightingStyle.ARCHERY && weapon.range() == AttackKind.RANGED) {
            toHit += 2;
        }

        boolean useVersatile = spec.twoHanded() && weapon.versatileDiceCount() != null && weapon.versatileDiceSides() != null;
        int count = useVersatile ? weapon.versatileDiceCount() : weapon.diceCount();
        int sides = useVersatile ? weapon.versatileDiceSides() : weapon.diceSides();
        Dice damage = Dice.of(count, sides, abilityMod);

        // Champion's Improved Critical widens the crit range to 19-20 from level 3.
        int critRange = "champion".equals(spec.subclass()) && spec.level() >= 3 ? 19 : 20;

        AttackKind kind = weapon.range();
        return new AttackProfile(
                weapon.name(), kind,
                kind == AttackKind.MELEE ? (weapon.has(WeaponProperty.REACH) ? 10 : 5) : null,
                kind == AttackKind.RANGED ? weapon.rangeNormalFt() : null,
                kind == AttackKind.RANGED ? weapon.rangeLongFt() : null,
                toHit, damage, weapon.damageType(), List.of(), critRange, weapon.has(WeaponProperty.FINESSE));
    }

    /** The features and resource pools a build has. Features are factories so each combatant owns its instances. */
    public record BuiltFeatures(List<FeatureFactory> features, List<ResourceSpec> resources) {}

    /** Attach the class, subclass and level features this build has, in a fixed order (it affects roll order). */
    public static BuiltFeatures buildFeatures(BuildSpec spec) {
        List<FeatureFactory> features = new ArrayList<>();
        List<ResourceSpec> resources = new ArrayList<>();
        BuildProgression p = spec.progression();
        String slug = spec.classInfo().slug();

        if (slug.equals("barbarian")) {
            if (p.rageUses() > 0) {
                resources.add(new ResourceSpec(ResourceIds.RAGE, p.rageUses(), Recharge.of(1), Recharge.FULL));
                int bonus = p.rageDamageBonus();
                features.add(() -> new RageFeature(bonus));
            }
            if (spec.level() >= 2) {
                features.add(RecklessAttackFeature::new); // Reckless Attack at level 2
            }
        }
        if (slug.equals("rogue") && p.sneakAttackDice() > 0) {
            int dice = p.sneakAttackDice();
            features.add(() -> new SneakAttackFeature(dice));
        }
        // Ranger: Favored Enemy grants free Hunter's Mark casts (uses = proficiency bonus).
        if (slug.equals("ranger")) {
            features.add(HuntersMarkFeature::new);
            resources.add(new ResourceSpec(ResourceIds.HUNTERS_MARK, CoreRules.proficiencyBonus(spec.level()), null, Recharge.FULL));
            // Hunter subclass (level 3): Hunter's Prey - Colossus Slayer.
            if ("hunter".equals(spec.subclass()) && spec.level() >= 3) {
                features.add(ColossusSlayerFeature::new);
            }
        }
        // Paladin's Divine Smite (a slot-fueled radiant rider on a melee hit) once it has slots.
        if (slug.equals("paladin") && spec.spellcasting() != null && !spec.spellcasting().slots().isEmpty()) {
            features.add(DivineSmiteFeature::new);
        }
        // Paladin's Lay on Hands: a healing pool of 5 HP per level (a Bonus Action to spend).
        if (slug.equals("paladin")) {
            resources.add(new ResourceSpec(ResourceIds.LAY_ON_HANDS, 5 * spec.level(), null, Recharge.FULL));
            // Aura of Protection comes online at level 6.
            if (spec.level() >= 6) {
                features.add(AuraOfProtectionFeature::new);
            }
        }
        // Monk: Martial Arts (free bonus unarmed strike) and, from level 2, Focus Points fuelling Stunning Strike
        // (available once the monk can make two attacks, at 5).
        if (slug.equals("monk")) {
            features.add(MartialArtsFeature::new);
            if (spec.level() >= 2) {
                resources.add(new ResourceSpec(ResourceIds.FOCUS, spec.level(), Recharge.FULL, Recharge.FULL));
            }
            if (spec.level() >= 5) {
                features.add(StunningStrikeFeature::new);
            }
        }
        return new BuiltFeatures(features, resources);
    }

    /** Compile a build into a {@link Combatant} placed on the board. */
    public static Combatant compile(BuildSpec spec) {
        int conMod = spec.abilities().modifier(Ability.CON);
        BuiltFeatures built = buildFeatures(spec);
        CombatantSpec.Builder b = CombatantSpec.builder(
                        spec.id() != null ? spec.id() : spec.classInfo().slug(),
                        spec.name(), spec.side(), spec.level(), spec.abilities(), armorClass(spec),
                        maxHitPoints(spec.classInfo().hitDieSides(), spec.level(), conMod))
                .saveProficiencies(ClassInfo.saveSet(spec.classInfo()))
                .attacks(List.of(weaponAttack(spec)))
                .features(built.features())
                .resources(built.resources())
                .extraAttacks(spec.progression().extraAttacks())
                .position(spec.position());
        if (spec.spellcasting() != null) {
            b.spellcasting(spec.spellcasting());
        }
        return new Combatant(b.build());
    }
}
