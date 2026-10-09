package org.omnomnom.dnd.sim.domain.content.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.domain.combat.AttackKind;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.combat.Feature;
import org.omnomnom.dnd.sim.domain.combat.spell.SpellcastingSpec;
import org.omnomnom.dnd.sim.domain.content.BuildProgression;
import org.omnomnom.dnd.sim.domain.content.ClassInfo;
import org.omnomnom.dnd.sim.domain.content.equipment.ArmorInfo;
import org.omnomnom.dnd.sim.domain.content.equipment.WeaponInfo;
import org.omnomnom.dnd.sim.domain.content.equipment.WeaponProperty;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.AbilityScores;
import org.omnomnom.dnd.sim.domain.core.DamageType;
import org.omnomnom.dnd.sim.domain.dice.Dice;

/** Port of {@code sim/src/content/character.spec.ts}. */
class CharacterCompilerTest {

    static final ClassInfo FIGHTER = new ClassInfo("fighter", 10, List.of(Ability.STR, Ability.CON));
    static final ClassInfo BARBARIAN = new ClassInfo("barbarian", 12, List.of(Ability.STR, Ability.CON));
    static final ClassInfo ROGUE = new ClassInfo("rogue", 8, List.of(Ability.DEX, Ability.INT));

    private static WeaponInfo weapon(String name, AttackKind range, int count, int sides, DamageType type,
            Integer versatileCount, Integer versatileSides, Integer rangeNormal, Integer rangeLong, WeaponProperty... props) {
        var set = EnumSet.noneOf(WeaponProperty.class);
        set.addAll(List.of(props));
        return new WeaponInfo(name, WeaponInfo.Category.MARTIAL, range, count, sides, type, set, versatileCount,
                versatileSides, rangeNormal, rangeLong);
    }

    static final WeaponInfo LONGSWORD = weapon("Longsword", AttackKind.MELEE, 1, 8, DamageType.SLASHING, 1, 10, null, null,
            WeaponProperty.VERSATILE);
    static final WeaponInfo GREATAXE = weapon("Greataxe", AttackKind.MELEE, 1, 12, DamageType.SLASHING, null, null, null, null,
            WeaponProperty.HEAVY, WeaponProperty.TWO_HANDED);
    static final WeaponInfo RAPIER = weapon("Rapier", AttackKind.MELEE, 1, 8, DamageType.PIERCING, null, null, null, null,
            WeaponProperty.FINESSE);
    static final WeaponInfo LONGBOW = weapon("Longbow", AttackKind.RANGED, 1, 8, DamageType.PIERCING, null, null, 150, 600,
            WeaponProperty.HEAVY, WeaponProperty.TWO_HANDED, WeaponProperty.AMMUNITION, WeaponProperty.RANGE);

    static final ArmorInfo CHAIN_MAIL = new ArmorInfo("Chain Mail", ArmorInfo.Category.HEAVY, 16, false, 0);
    static final ArmorInfo STUDDED = new ArmorInfo("Studded Leather", ArmorInfo.Category.LIGHT, 12, true, null);
    static final ArmorInfo BREASTPLATE = new ArmorInfo("Breastplate", ArmorInfo.Category.MEDIUM, 14, true, 2);

    private static BuildSpec.Builder base() {
        return BuildSpec.builder("T", FIGHTER, 3, AbilityScores.of(16, 14, 14, 10, 10, 10), LONGSWORD);
    }

    // ---- maxHitPoints --------------------------------------------------------------------------

    @Test
    void levelOneIsMaxDiePlusCon() {
        assertThat(CharacterCompiler.maxHitPoints(10, 1, 2)).isEqualTo(12);
    }

    @Test
    void hitPointsAcrossClassesAtLevelThree() {
        assertThat(CharacterCompiler.maxHitPoints(10, 3, 2)).isEqualTo(28); // 10 + 2 + 2*(6+2)
        assertThat(CharacterCompiler.maxHitPoints(12, 3, 3)).isEqualTo(35); // 12 + 3 + 2*(7+3)
        assertThat(CharacterCompiler.maxHitPoints(8, 3, 1)).isEqualTo(21); // 8 + 1 + 2*(5+1)
    }

    @Test
    void eachLevelAddsAtLeastOneEvenWithAHugeConPenalty() {
        // level 1: 6 - 5 = 1, then +1 and +1
        assertThat(CharacterCompiler.maxHitPoints(6, 3, -5)).isEqualTo(3);
    }

    @Test
    void rejectsLevelsBelowOne() {
        assertThatThrownBy(() -> CharacterCompiler.maxHitPoints(10, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- armorClass ----------------------------------------------------------------------------

    @Test
    void heavyArmorIgnoresDex() {
        assertThat(CharacterCompiler.armorClass(base().armor(CHAIN_MAIL).build())).isEqualTo(16);
    }

    @Test
    void lightArmorAddsFullDex() {
        BuildSpec s = BuildSpec.builder("T", FIGHTER, 3, AbilityScores.of(10, 16, 14, 10, 10, 10), LONGSWORD).armor(STUDDED).build();
        assertThat(CharacterCompiler.armorClass(s)).isEqualTo(15); // 12 + 3
    }

    @Test
    void mediumArmorCapsDexAtTwo() {
        BuildSpec s = BuildSpec.builder("T", FIGHTER, 3, AbilityScores.of(10, 18, 14, 10, 10, 10), LONGSWORD).armor(BREASTPLATE).build();
        assertThat(CharacterCompiler.armorClass(s)).isEqualTo(16); // 14 + 2
    }

    @Test
    void shieldAndDefenseStyleStackWithArmor() {
        BuildSpec s = base().armor(CHAIN_MAIL).shield(true).fightingStyle(FightingStyle.DEFENSE).build();
        assertThat(CharacterCompiler.armorClass(s)).isEqualTo(16 + 2 + 1);
    }

    @Test
    void defenseStyleDoesNotApplyWithoutArmor() {
        BuildSpec s = BuildSpec.builder("T", FIGHTER, 3, AbilityScores.of(16, 14, 16, 10, 10, 10), LONGSWORD)
                .unarmoredDefense(UnarmoredDefense.BARBARIAN).fightingStyle(FightingStyle.DEFENSE).build();
        assertThat(CharacterCompiler.armorClass(s)).isEqualTo(10 + 2 + 3);
    }

    @Test
    void barbarianUnarmoredDefenseIsTenPlusDexPlusConShieldAllowed() {
        BuildSpec s = BuildSpec.builder("T", BARBARIAN, 3, AbilityScores.of(16, 14, 16, 10, 10, 10), LONGSWORD)
                .unarmoredDefense(UnarmoredDefense.BARBARIAN).shield(true).build();
        assertThat(CharacterCompiler.armorClass(s)).isEqualTo(10 + 2 + 3 + 2);
    }

    @Test
    void monkUnarmoredDefenseUsesWisdom() {
        BuildSpec s = BuildSpec.builder("T", FIGHTER, 3, AbilityScores.of(10, 16, 10, 10, 14, 10), LONGSWORD)
                .unarmoredDefense(UnarmoredDefense.MONK).build();
        assertThat(CharacterCompiler.armorClass(s)).isEqualTo(10 + 3 + 2);
    }

    // ---- weaponAbility / weaponAttack ----------------------------------------------------------

    @Test
    void meleeUsesStrAndFinessePicksTheBetterOfStrDex() {
        assertThat(CharacterCompiler.weaponAbility(base().build())).isEqualTo(Ability.STR);
        assertThat(CharacterCompiler.weaponAbility(
                BuildSpec.builder("T", FIGHTER, 3, AbilityScores.of(10, 16, 14, 10, 10, 10), RAPIER).build())).isEqualTo(Ability.DEX);
        assertThat(CharacterCompiler.weaponAbility(
                BuildSpec.builder("T", FIGHTER, 3, AbilityScores.of(18, 12, 14, 10, 10, 10), RAPIER).build())).isEqualTo(Ability.STR);
    }

    @Test
    void rangedUsesDex() {
        BuildSpec s = BuildSpec.builder("T", FIGHTER, 3, AbilityScores.of(16, 14, 14, 10, 10, 10), LONGBOW).build();
        assertThat(CharacterCompiler.weaponAbility(s)).isEqualTo(Ability.DEX);
    }

    @Test
    void fighterLevelThreeLongsword() {
        var a = CharacterCompiler.weaponAttack(base().build());
        assertThat(a.attackBonus()).isEqualTo(3 + 2); // Str +3, prof +2
        assertThat(a.damage().mean()).isEqualTo(4.5 + 3); // 1d8 + 3
        assertThat(a.critRange()).isEqualTo(20);
        assertThat(a.reachFt()).isEqualTo(5);
    }

    @Test
    void twoHandedVersatileUsesTheVersatileDie() {
        var a = CharacterCompiler.weaponAttack(base().twoHanded(true).build());
        assertThat(a.damage()).isEqualTo(Dice.of(1, 10, 3));
    }

    @Test
    void archeryAddsTwoOnlyToRangedAttacks() {
        BuildSpec ranged = BuildSpec.builder("T", FIGHTER, 3, AbilityScores.of(16, 14, 14, 10, 10, 10), LONGBOW)
                .fightingStyle(FightingStyle.ARCHERY).build();
        var r = CharacterCompiler.weaponAttack(ranged);
        assertThat(r.attackBonus()).isEqualTo(2 + 2 + 2); // Dex +2, prof +2, archery +2
        assertThat(r.rangeFt()).isEqualTo(150);
        assertThat(r.rangeLongFt()).isEqualTo(600);
        assertThat(r.reachFt()).isNull();
        var m = CharacterCompiler.weaponAttack(base().fightingStyle(FightingStyle.ARCHERY).build());
        assertThat(m.attackBonus()).isEqualTo(3 + 2);
    }

    @Test
    void reachWeaponsReachTenFeet() {
        WeaponInfo glaive = weapon("Glaive", AttackKind.MELEE, 1, 10, DamageType.SLASHING, null, null, null, null,
                WeaponProperty.REACH, WeaponProperty.HEAVY);
        var a = CharacterCompiler.weaponAttack(BuildSpec.builder("T", FIGHTER, 3, AbilityScores.allTens(), glaive).build());
        assertThat(a.reachFt()).isEqualTo(10);
    }

    @Test
    void championGetsANineteenTwentyCritRangeFromLevelThree() {
        assertThat(CharacterCompiler.weaponAttack(base().subclass("champion").build()).critRange()).isEqualTo(19);
        BuildSpec level2 = BuildSpec.builder("T", FIGHTER, 2, AbilityScores.of(16, 14, 14, 10, 10, 10), LONGSWORD).subclass("champion").build();
        assertThat(CharacterCompiler.weaponAttack(level2).critRange()).isEqualTo(20);
    }

    @Test
    void aNonProficientWeaponDropsTheProficiencyBonus() {
        assertThat(CharacterCompiler.weaponAttack(base().weaponProficient(false).build()).attackBonus()).isEqualTo(3);
    }

    // ---- compileBuild --------------------------------------------------------------------------

    @Test
    void aLevelThreeSwordAndBoardFighterHasTheExpectedStatline() {
        Combatant fighter = CharacterCompiler.compile(
                BuildSpec.builder("Fighter", FIGHTER, 3, AbilityScores.of(16, 12, 14, 10, 10, 10), LONGSWORD)
                        .id("fighter").subclass("champion").armor(CHAIN_MAIL).shield(true)
                        .fightingStyle(FightingStyle.DEFENSE).build());
        assertThat(fighter.ac()).isEqualTo(16 + 2 + 1);
        assertThat(fighter.hp()).isEqualTo(28);
        assertThat(fighter.saveBonus(Ability.CON)).isEqualTo(2 + 2);
        assertThat(fighter.saveBonus(Ability.DEX)).isEqualTo(1);
        assertThat(fighter.attacks().get(0).attackBonus()).isEqualTo(5);
        assertThat(fighter.attacks().get(0).critRange()).isEqualTo(19);
    }

    @Test
    void aLevelThreeBarbarianIsUnarmoredAndTough() {
        Combatant barb = CharacterCompiler.compile(
                BuildSpec.builder("Barbarian", BARBARIAN, 3, AbilityScores.of(16, 14, 16, 8, 10, 8), GREATAXE)
                        .twoHanded(true).unarmoredDefense(UnarmoredDefense.BARBARIAN).build());
        assertThat(barb.ac()).isEqualTo(10 + 2 + 3);
        assertThat(barb.hp()).isEqualTo(35);
        assertThat(barb.attacks().get(0).damage().mean()).isEqualTo(6.5 + 3);
        assertThat(barb.id()).isEqualTo("barbarian"); // defaults to the class slug
    }

    @Test
    void aLevelThreeRogueUsesDexViaFinesse() {
        Combatant rogue = CharacterCompiler.compile(
                BuildSpec.builder("Rogue", ROGUE, 3, AbilityScores.of(10, 16, 12, 14, 10, 10), RAPIER)
                        .subclass("thief").armor(STUDDED).build());
        assertThat(rogue.ac()).isEqualTo(12 + 3);
        assertThat(rogue.hp()).isEqualTo(21);
        assertThat(rogue.attacks().get(0).attackBonus()).isEqualTo(3 + 2);
        assertThat(rogue.saveBonus(Ability.DEX)).isEqualTo(3 + 2);
        assertThat(rogue.saveBonus(Ability.INT)).isEqualTo(2 + 2);
    }

    @Test
    void aPaladinGishCarriesSpellSlotsAndDivineSmite() {
        ClassInfo paladinClass = new ClassInfo("paladin", 10, List.of(Ability.WIS, Ability.CHA));
        Combatant pal = CharacterCompiler.compile(
                BuildSpec.builder("Paladin", paladinClass, 5, AbilityScores.of(16, 10, 14, 8, 10, 14), LONGSWORD)
                        .subclass("oath-of-devotion").armor(CHAIN_MAIL).shield(true).fightingStyle(FightingStyle.DEFENSE)
                        .progression(new BuildProgression(0, 0, 0, 1))
                        .spellcasting(new SpellcastingSpec(Ability.CHA, List.of(new SpellcastingSpec.Slot(1, 4)), List.of(), List.of(), false))
                        .build());
        assertThat(pal.extraAttacks()).isEqualTo(1);
        assertThat(pal.slotCount(1)).isEqualTo(4);
        assertThat(pal.spellSaveDc()).isEqualTo(8 + 3 + 2);
        assertThat(pal.features()).anyMatch(f -> f.id().equals("divine-smite"));
        assertThat(pal.resourceCount("lay-on-hands")).isEqualTo(25);
        assertThat(pal.features()).noneMatch(f -> f.id().equals("aura-of-protection")); // level 6+
    }

    @Test
    void classFeaturesAreAttachedByClassAndLevel() {
        BuildProgression rage = new BuildProgression(3, 2, 0, 0);
        Combatant barb = CharacterCompiler.compile(
                BuildSpec.builder("B", BARBARIAN, 3, AbilityScores.allTens(), GREATAXE).progression(rage).build());
        assertThat(barb.features().stream().map(Feature::id)).containsExactly("rage", "reckless-attack");
        assertThat(barb.resourceCount("rage")).isEqualTo(3);

        Combatant rogue = CharacterCompiler.compile(BuildSpec.builder("R", ROGUE, 5, AbilityScores.allTens(), RAPIER)
                .progression(new BuildProgression(0, 0, 3, 1)).build());
        assertThat(rogue.features().stream().map(Feature::id)).containsExactly("sneak-attack");

        ClassInfo ranger = new ClassInfo("ranger", 10, List.of(Ability.STR, Ability.DEX));
        Combatant hunter = CharacterCompiler.compile(
                BuildSpec.builder("H", ranger, 5, AbilityScores.allTens(), LONGBOW).subclass("hunter").build());
        assertThat(hunter.features().stream().map(Feature::id)).containsExactly("hunters-mark", "colossus-slayer");
        assertThat(hunter.resourceCount("hunters-mark")).isEqualTo(3); // proficiency bonus at level 5

        ClassInfo monk = new ClassInfo("monk", 8, List.of(Ability.STR, Ability.DEX));
        Combatant mk = CharacterCompiler.compile(BuildSpec.builder("M", monk, 5, AbilityScores.allTens(), RAPIER).build());
        assertThat(mk.features().stream().map(Feature::id)).containsExactly("martial-arts", "stunning-strike");
        assertThat(mk.resourceCount("focus")).isEqualTo(5);
    }

    @Test
    void eachCompiledCombatantOwnsItsFeatureInstances() {
        BuildSpec spec = BuildSpec.builder("B", BARBARIAN, 3, AbilityScores.allTens(), GREATAXE)
                .progression(new BuildProgression(3, 2, 0, 0)).build();
        Combatant a = CharacterCompiler.compile(spec);
        Combatant b = CharacterCompiler.compile(spec);
        assertThat(a.features().get(0)).isNotSameAs(b.features().get(0));
    }

    // ---- gaps found by mutation testing ---------------------------------------------------------

    @Test
    void hitPointsNeverDropBelowOne() {
        assertThat(CharacterCompiler.maxHitPoints(6, 1, -9)).isEqualTo(1);
    }

    @Test
    void anUnarmoredCharacterWithoutUnarmoredDefenseHasTenPlusDex() {
        // Dex 14 (+2): no armor, no shield, no Unarmored Defense.
        assertThat(CharacterCompiler.armorClass(base().build())).isEqualTo(12);
        // A negative Dex modifier lowers it (kills a sign flip on the Dex term).
        BuildSpec clumsy = BuildSpec.builder("T", FIGHTER, 3, AbilityScores.of(16, 8, 14, 10, 10, 10), LONGSWORD).build();
        assertThat(CharacterCompiler.armorClass(clumsy)).isEqualTo(9);
    }

    @Test
    void featuresDependOnTheClassNotJustTheProgressionOrSlots() {
        // Sneak Attack dice on a non-rogue progression, and slots on a non-paladin, grant nothing.
        Combatant fighter = CharacterCompiler.compile(base().progression(new BuildProgression(0, 0, 3, 1))
                .spellcasting(new SpellcastingSpec(Ability.CHA, List.of(new SpellcastingSpec.Slot(1, 4)), List.of(), List.of(), false))
                .build());
        assertThat(fighter.features()).isEmpty();
        // A rogue with no sneak dice (progression NONE) gets no Sneak Attack either.
        Combatant dull = CharacterCompiler.compile(BuildSpec.builder("R", ROGUE, 1, AbilityScores.allTens(), RAPIER).build());
        assertThat(dull.features()).isEmpty();
        // A paladin without slots has no Divine Smite.
        ClassInfo paladinClass = new ClassInfo("paladin", 10, List.of(Ability.WIS, Ability.CHA));
        Combatant slotless = CharacterCompiler.compile(
                BuildSpec.builder("P", paladinClass, 2, AbilityScores.allTens(), LONGSWORD).build());
        assertThat(slotless.features()).noneMatch(f -> f.id().equals("divine-smite"));
    }

    @Test
    void auraOfProtectionComesOnlineAtLevelSix() {
        ClassInfo paladinClass = new ClassInfo("paladin", 10, List.of(Ability.WIS, Ability.CHA));
        Combatant five = CharacterCompiler.compile(BuildSpec.builder("P", paladinClass, 5, AbilityScores.allTens(), LONGSWORD).build());
        Combatant six = CharacterCompiler.compile(BuildSpec.builder("P", paladinClass, 6, AbilityScores.allTens(), LONGSWORD).build());
        assertThat(five.features()).noneMatch(f -> f.id().equals("aura-of-protection"));
        assertThat(six.features()).anyMatch(f -> f.id().equals("aura-of-protection"));
    }

    @Test
    void rageRechargesOneUsePerShortRestAndAllOnALongRest() {
        Combatant barb = CharacterCompiler.compile(BuildSpec.builder("B", BARBARIAN, 3, AbilityScores.allTens(), GREATAXE)
                .progression(new BuildProgression(3, 2, 0, 0)).build());
        barb.spendResource("rage", 3);
        barb.shortRest();
        assertThat(barb.resourceCount("rage")).isEqualTo(1);
        barb.longRest();
        assertThat(barb.resourceCount("rage")).isEqualTo(3);
    }

    @Test
    void monkFocusStartsAtLevelTwoAndStunningStrikeAtFive() {
        ClassInfo monk = new ClassInfo("monk", 8, List.of(Ability.STR, Ability.DEX));
        java.util.function.IntFunction<Combatant> at = level ->
                CharacterCompiler.compile(BuildSpec.builder("M", monk, level, AbilityScores.allTens(), RAPIER).build());
        assertThat(at.apply(1).resourceCount("focus")).isZero();
        assertThat(at.apply(2).resourceCount("focus")).isEqualTo(2);
        assertThat(at.apply(4).features()).noneMatch(f -> f.id().equals("stunning-strike"));
        assertThat(at.apply(5).features()).anyMatch(f -> f.id().equals("stunning-strike"));
    }

    @Test
    void recklessAttackStartsAtLevelTwo() {
        BuildProgression rage = new BuildProgression(2, 2, 0, 0);
        Combatant one = CharacterCompiler.compile(BuildSpec.builder("B", BARBARIAN, 1, AbilityScores.allTens(), GREATAXE).progression(rage).build());
        Combatant two = CharacterCompiler.compile(BuildSpec.builder("B", BARBARIAN, 2, AbilityScores.allTens(), GREATAXE).progression(rage).build());
        assertThat(one.features()).noneMatch(f -> f.id().equals("reckless-attack"));
        assertThat(two.features()).anyMatch(f -> f.id().equals("reckless-attack"));
    }
}
