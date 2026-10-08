package org.omnomnom.dnd.sim.domain.opt;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.combat.AttackKind;
import org.omnomnom.dnd.sim.domain.combat.Side;
import org.omnomnom.dnd.sim.domain.content.BuildSpec;
import org.omnomnom.dnd.sim.domain.content.CasterBuildSpec;
import org.omnomnom.dnd.sim.domain.content.CasterCompiler;
import org.omnomnom.dnd.sim.domain.content.CharacterCompiler;
import org.omnomnom.dnd.sim.domain.content.FightingStyle;
import org.omnomnom.dnd.sim.domain.content.UnarmoredDefense;
import org.omnomnom.dnd.sim.domain.content.WeaponInfo;
import org.omnomnom.dnd.sim.domain.content.WeaponProperty;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.AbilityScores;
import org.omnomnom.dnd.sim.domain.core.DamageType;
import org.omnomnom.dnd.sim.domain.rng.LabeledRandom;
import org.omnomnom.dnd.sim.domain.rng.Rng;

/**
 * Genome operators: random generation, repair, mutation, uniform crossover, and compiling a genome into a combatant.
 * Every operator draws from one labeled stream in a fixed order, so a seeded run is reproducible and matches the
 * TypeScript sim draw for draw.
 */
public final class Genomes {

    private Genomes() {}

    /** The 2024 standard array, assigned to the six abilities by a permutation. */
    public static final int[] STANDARD_ARRAY = {15, 14, 13, 12, 10, 8};

    private static final List<FightingStyle> FIGHTING_STYLES = List.of(FightingStyle.values());

    /** The six ability scores under an assignment permutation (str, dex, con, int, wis, cha order). */
    public static AbilityScores abilitiesFrom(List<Integer> assignment) {
        int[] a = new int[6];
        for (int i = 0; i < 6; i++) {
            a[i] = STANDARD_ARRAY[assignment.get(i)];
        }
        return AbilityScores.of(a[0], a[1], a[2], a[3], a[4], a[5]);
    }

    private static <T> T pick(List<T> list, Rng rng) {
        return list.get((int) Math.floor(rng.next() * list.size()));
    }

    private static List<Integer> shuffle(List<Integer> in, Rng rng) {
        List<Integer> a = new ArrayList<>(in);
        for (int i = a.size() - 1; i > 0; i--) {
            int j = (int) Math.floor(rng.next() * (i + 1));
            Integer t = a.get(i);
            a.set(i, a.get(j));
            a.set(j, t);
        }
        return a;
    }

    /** A random legal genome. {@code classes} restricts the class pool; null or empty means every class. */
    public static Genome randomGenome(MartialCatalog catalog, LabeledRandom random, String label, List<BuildClass> classes) {
        Rng rng = random.stream(label);
        BuildClass classSlug = pick(classes == null || classes.isEmpty() ? BuildClass.ALL : classes, rng);
        List<Integer> assignment = shuffle(List.of(0, 1, 2, 3, 4, 5), rng);
        WeaponInfo weapon = pick(catalog.weapons(), rng);
        FightingStyle style = classSlug == BuildClass.FIGHTER ? pick(FIGHTING_STYLES, rng) : null;
        Genome g = new Genome(classSlug, assignment, weapon.name(), null, false,
                weapon.properties().contains(WeaponProperty.TWO_HANDED), style);
        return repair(g, catalog);
    }

    /**
     * Make a genome legal: barbarians go unarmored (Unarmored Defense), others pick armor they are allowed; a
     * two-handed weapon cannot be paired with a shield; a versatile weapon is two-handed only without a shield.
     * Casters keep their fixed package, so their genome is returned unchanged.
     */
    public static Genome repair(Genome g, MartialCatalog catalog) {
        if (g.classSlug().isCaster()) {
            return g;
        }
        WeaponInfo weapon = catalog.weaponByName(g.weaponName());
        boolean isTwoHandedWeapon = weapon.properties().contains(WeaponProperty.TWO_HANDED);
        boolean isVersatile = weapon.versatileDiceCount() != null;

        String armorName = g.armorName();
        boolean shield = g.shield();
        boolean twoHanded = g.twoHanded();

        if (g.classSlug() == BuildClass.BARBARIAN) {
            armorName = null; // Unarmored Defense
        } else if (armorName == null) {
            armorName = catalog.defaultArmorFor(g.classSlug());
        }

        if (isTwoHandedWeapon) {
            shield = false;
            twoHanded = true;
        } else if (!isVersatile) {
            twoHanded = false;
        } else if (shield) {
            twoHanded = false; // versatile: two-handed only makes sense without a shield
        }

        FightingStyle style = g.classSlug() == BuildClass.FIGHTER
                ? (g.fightingStyle() != null ? g.fightingStyle() : FightingStyle.DEFENSE)
                : null;
        return new Genome(g.classSlug(), g.abilityAssignment(), g.weaponName(), armorName, shield, twoHanded, style);
    }

    /** Mutate one gene at random, returning a repaired genome. */
    public static Genome mutate(Genome g, MartialCatalog catalog, LabeledRandom random, String label, List<BuildClass> classes) {
        Rng rng = random.stream(label);
        List<BuildClass> pool = classes == null || classes.isEmpty() ? BuildClass.ALL : classes;
        int choice = (int) Math.floor(rng.next() * 6);
        Genome next = switch (choice) {
            case 0 -> new Genome(pick(pool, rng), g.abilityAssignment(), g.weaponName(), g.armorName(), g.shield(), g.twoHanded(), g.fightingStyle());
            case 1 -> {
                // Swap two ability assignments.
                int a = (int) Math.floor(rng.next() * 6);
                int b = (int) Math.floor(rng.next() * 6);
                List<Integer> assignment = new ArrayList<>(g.abilityAssignment());
                Integer t = assignment.get(a);
                assignment.set(a, assignment.get(b));
                assignment.set(b, t);
                yield new Genome(g.classSlug(), assignment, g.weaponName(), g.armorName(), g.shield(), g.twoHanded(), g.fightingStyle());
            }
            case 2 -> new Genome(g.classSlug(), g.abilityAssignment(), pick(catalog.weapons(), rng).name(), g.armorName(), g.shield(),
                    g.twoHanded(), g.fightingStyle());
            case 3 -> new Genome(g.classSlug(), g.abilityAssignment(), g.weaponName(), g.armorName(), !g.shield(), g.twoHanded(), g.fightingStyle());
            case 4 -> new Genome(g.classSlug(), g.abilityAssignment(), g.weaponName(), g.armorName(), g.shield(), !g.twoHanded(), g.fightingStyle());
            default -> new Genome(g.classSlug(), g.abilityAssignment(), g.weaponName(), g.armorName(), g.shield(), g.twoHanded(),
                    pick(FIGHTING_STYLES, rng));
        };
        return repair(next, catalog);
    }

    /** Uniform crossover of two genomes, gene by gene, then repair. */
    public static Genome crossover(Genome a, Genome b, MartialCatalog catalog, LabeledRandom random, String label) {
        Rng rng = random.stream(label);
        // Evaluation order matters: one draw per gene, in field order.
        BuildClass classSlug = rng.next() < 0.5 ? a.classSlug() : b.classSlug();
        List<Integer> assignment = rng.next() < 0.5 ? a.abilityAssignment() : b.abilityAssignment();
        String weapon = rng.next() < 0.5 ? a.weaponName() : b.weaponName();
        String armor = rng.next() < 0.5 ? a.armorName() : b.armorName();
        boolean shield = rng.next() < 0.5 ? a.shield() : b.shield();
        boolean twoHanded = rng.next() < 0.5 ? a.twoHanded() : b.twoHanded();
        FightingStyle style = rng.next() < 0.5 ? a.fightingStyle() : b.fightingStyle();
        return repair(new Genome(classSlug, assignment, weapon, armor, shield, twoHanded, style), catalog);
    }

    /** The Monk's unarmed strike: the Martial Arts die scales with level, Dex-based (finesse). */
    static WeaponInfo monkUnarmedStrike(int level) {
        int sides = level >= 17 ? 10 : level >= 11 ? 8 : level >= 5 ? 6 : 4;
        Set<WeaponProperty> props = EnumSet.of(WeaponProperty.FINESSE);
        return new WeaponInfo("Unarmed Strike", WeaponInfo.Category.SIMPLE, AttackKind.MELEE, 1, sides, DamageType.BLUDGEONING,
                props, null, null, null, null);
    }

    /** Compile a genome into a party-side combatant with the given id. */
    public static Combatant build(Genome g, MartialCatalog catalog, String id) {
        AbilityScores abilities = abilitiesFrom(g.abilityAssignment());
        int level = catalog.level();
        BuildClass c = g.classSlug();
        String name = c.code() + " hero";

        if (c.isCaster()) {
            CasterPackage pkg = catalog.casterPackageFor(c);
            return CasterCompiler.compile(CasterBuildSpec.builder(name, catalog.classByName(c), level, abilities, pkg.weapon(), pkg.spellAbility())
                    .id(id).side(Side.PARTY).subclass(catalog.subclassFor(c))
                    .armor(pkg.armor()).shield(pkg.shield())
                    .cantrips(pkg.cantrips()).spells(pkg.spells()).slots(pkg.slots())
                    .resources(pkg.resources()).features(pkg.features())
                    .shortRestSlots(pkg.shortRestSlots()).extraHp(pkg.extraHp())
                    .unarmoredAcAbility(pkg.unarmoredAcAbility())
                    .build());
        }

        // The Monk fights unarmored (Unarmored Defense) with its unarmed strike, so the evolved weapon, armor and style
        // are ignored in favor of the monk kit.
        if (c == BuildClass.MONK) {
            return CharacterCompiler.compile(BuildSpec.builder("monk hero", catalog.classByName(c), level, abilities,
                            monkUnarmedStrike(level))
                    .id(id).side(Side.PARTY).subclass(catalog.subclassFor(c))
                    .unarmoredDefense(UnarmoredDefense.MONK)
                    .progression(catalog.progressionFor(c))
                    .build());
        }

        BuildSpec.Builder spec = BuildSpec.builder(name, catalog.classByName(c), level, abilities, catalog.weaponByName(g.weaponName()))
                .id(id).side(Side.PARTY).subclass(catalog.subclassFor(c))
                .twoHanded(g.twoHanded())
                .shield(g.shield())
                .progression(catalog.progressionFor(c));
        if (g.armorName() != null) {
            spec.armor(catalog.armorByName(g.armorName()));
        }
        if (g.fightingStyle() != null) {
            spec.fightingStyle(g.fightingStyle());
        }
        if (c == BuildClass.BARBARIAN) {
            spec.unarmoredDefense(UnarmoredDefense.BARBARIAN);
        }
        // A gish (Paladin) also carries spell slots and a short spell list.
        if (catalog.gishSpellcastingFor(c) != null) {
            spec.spellcasting(catalog.gishSpellcastingFor(c));
        }
        return CharacterCompiler.compile(spec.build());
    }

    /** A stable string key for a genome (for caching and de-duplication). */
    public static String key(Genome g) {
        // Casters use a fixed gear/spell package, so only class and abilities vary.
        StringBuilder assignment = new StringBuilder();
        g.abilityAssignment().forEach(assignment::append);
        if (g.classSlug().isCaster()) {
            return g.classSlug().code() + "|" + assignment;
        }
        return String.join("|",
                g.classSlug().code(),
                assignment,
                g.weaponName(),
                g.armorName() != null ? g.armorName() : "-",
                g.shield() ? "S" : "-",
                g.twoHanded() ? "2H" : "1H",
                g.fightingStyle() != null ? g.fightingStyle().code() : "-");
    }
}
