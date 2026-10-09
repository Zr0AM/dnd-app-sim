package org.omnomnom.dnd.sim.domain.opt.genome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.adapter.out.content.SqliteContentSource;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.content.build.FightingStyle;
import org.omnomnom.dnd.sim.domain.content.monster.MonsterCatalog;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.AbilityScores;
import org.omnomnom.dnd.sim.domain.rng.LabeledRandom;

/** Port of {@code genome.spec.ts}. */
class GenomesTest {

    static MartialCatalog catalog;

    @BeforeAll
    static void open() {
        try (SqliteContentSource source = SqliteContentSource.open()) {
            catalog = MartialCatalog.load(source, MonsterCatalog.load(source), 3);
        }
    }

    @AfterAll
    static void done() {}

    private static Genome genome(BuildClass c, List<Integer> assignment, String weapon, String armor, boolean shield, boolean twoHanded,
            FightingStyle style) {
        return new Genome(c, assignment, weapon, armor, shield, twoHanded, style);
    }

    private static final List<Integer> IDENTITY = List.of(0, 1, 2, 3, 4, 5);

    @Test
    void abilitiesApplyTheStandardArrayUnderTheAssignment() {
        AbilityScores a = Genomes.abilitiesFrom(IDENTITY);
        assertThat(a.get(Ability.STR)).isEqualTo(15);
        assertThat(a.get(Ability.CHA)).isEqualTo(8);
        int[] sorted = Arrays.stream(Ability.values()).mapToInt(a::get).sorted().toArray();
        assertThat(sorted).containsExactly(8, 10, 12, 13, 14, 15);
    }

    @Test
    void barbariansAreAlwaysUnarmored() {
        Genome g = Genomes.repair(genome(BuildClass.BARBARIAN, IDENTITY, "Greataxe", "Chain Mail", false, true, null), catalog);
        assertThat(g.armorName()).isNull();
    }

    @Test
    void aTwoHandedWeaponDropsTheShield() {
        Genome g = Genomes.repair(genome(BuildClass.FIGHTER, IDENTITY, "Greatsword", "Chain Mail", true, true, null), catalog);
        assertThat(g.shield()).isFalse();
        assertThat(g.twoHanded()).isTrue();
    }

    @Test
    void aOneHandedNonVersatileWeaponIsNeverTwoHanded() {
        Genome g = Genomes.repair(genome(BuildClass.FIGHTER, IDENTITY, "Rapier", "Chain Mail", false, true, null), catalog);
        assertThat(g.twoHanded()).isFalse();
    }

    @Test
    void aVersatileWeaponWithAShieldIsOneHanded() {
        Genome shielded = Genomes.repair(genome(BuildClass.FIGHTER, IDENTITY, "Longsword", "Chain Mail", true, true, null), catalog);
        assertThat(shielded.twoHanded()).isFalse();
        Genome bare = Genomes.repair(genome(BuildClass.FIGHTER, IDENTITY, "Longsword", "Chain Mail", false, true, null), catalog);
        assertThat(bare.twoHanded()).isTrue();
    }

    @Test
    void nonBarbariansGetArmorIfTheyHadNone() {
        Genome g = Genomes.repair(genome(BuildClass.FIGHTER, IDENTITY, "Longsword", null, true, false, null), catalog);
        assertThat(g.armorName()).isEqualTo("Chain Mail");
        Genome rogue = Genomes.repair(genome(BuildClass.ROGUE, IDENTITY, "Rapier", null, false, false, null), catalog);
        assertThat(rogue.armorName()).isEqualTo("Studded Leather Armor");
    }

    @Test
    void onlyFightersKeepAFightingStyleAndAlwaysHaveOne() {
        assertThat(Genomes.repair(genome(BuildClass.FIGHTER, IDENTITY, "Longsword", "Chain Mail", true, false, null), catalog).fightingStyle())
                .isEqualTo(FightingStyle.DEFENSE);
        assertThat(Genomes.repair(genome(BuildClass.FIGHTER, IDENTITY, "Longsword", "Chain Mail", true, false, FightingStyle.ARCHERY), catalog)
                .fightingStyle()).isEqualTo(FightingStyle.ARCHERY);
        assertThat(Genomes.repair(genome(BuildClass.ROGUE, IDENTITY, "Rapier", "Chain Mail", false, false, FightingStyle.ARCHERY), catalog)
                .fightingStyle()).isNull();
    }

    @Test
    void castersAreReturnedUnchangedByRepair() {
        Genome wizard = genome(BuildClass.WIZARD, IDENTITY, "Longsword", "Chain Mail", true, true, FightingStyle.ARCHERY);
        assertThat(Genomes.repair(wizard, catalog)).isSameAs(wizard);
    }

    @Test
    void randomGenomesCompileToValidCombatants() {
        LabeledRandom rng = new LabeledRandom(1);
        for (int i = 0; i < 50; i++) {
            Combatant c = Genomes.build(Genomes.randomGenome(catalog, rng, "g:" + i, null), catalog, "hero");
            assertThat(c.hp()).isPositive();
            assertThat(c.ac()).isPositive();
            assertThat(c.attacks()).hasSize(1);
        }
    }

    @Test
    void mutationStaysLegalAndBuildable() {
        LabeledRandom rng = new LabeledRandom(2);
        Genome g = Genomes.randomGenome(catalog, rng, "base", null);
        for (int i = 0; i < 50; i++) {
            g = Genomes.mutate(g, catalog, rng, "m:" + i, null);
            Genome current = g;
            assertThatCode(() -> Genomes.build(current, catalog, "hero")).doesNotThrowAnyException();
            if (g.classSlug() == BuildClass.BARBARIAN) {
                assertThat(g.armorName()).isNull();
            }
        }
    }

    @Test
    void crossoverStaysLegal() {
        LabeledRandom rng = new LabeledRandom(3);
        Genome a = Genomes.randomGenome(catalog, rng, "a", null);
        Genome b = Genomes.randomGenome(catalog, rng, "b", null);
        Genome child = Genomes.crossover(a, b, catalog, rng, "x");
        assertThatCode(() -> Genomes.build(child, catalog, "hero")).doesNotThrowAnyException();
    }

    @Test
    void aWizardCompilesToASpellcaster() {
        Combatant wizard = Genomes.build(genome(BuildClass.WIZARD, List.of(5, 1, 2, 0, 3, 4), "Dagger", null, false, false, null), catalog, "hero");
        assertThat(wizard.spellAbility()).isEqualTo(Ability.INT);
        assertThat(wizard.spells()).isNotEmpty();
        assertThat(wizard.slotCount(1)).isPositive();
    }

    @Test
    void everyClassCompilesToAValidCombatant() {
        assertThat(BuildClass.ALL).hasSize(12);
        for (BuildClass c : BuildClass.ALL) {
            Combatant hero = Genomes.build(genome(c, IDENTITY, "Longsword", c == BuildClass.BARBARIAN ? null : "Chain Mail", false, false,
                    FightingStyle.DEFENSE), catalog, "hero");
            assertThat(hero.hp()).as(c.code()).isPositive();
            assertThat(hero.ac()).as(c.code()).isPositive();
            assertThat(hero.attacks()).as(c.code()).hasSize(1);
        }
    }

    @Test
    void aDruidIsAWisdomCasterWithNatureSpells() {
        Combatant druid = Genomes.build(genome(BuildClass.DRUID, List.of(5, 1, 2, 3, 0, 4), "Mace", null, false, false, null), catalog, "hero");
        assertThat(druid.spellAbility()).isEqualTo(Ability.WIS);
        assertThat(druid.spells()).anyMatch(s -> s.id().equals("moonbeam"));
        assertThat(druid.spells()).anyMatch(s -> s.id().equals("cure-wounds"));
    }

    @Test
    void theMonkIgnoresEvolvedGearAndFightsWithItsUnarmedStrike() {
        Combatant monk = Genomes.build(genome(BuildClass.MONK, IDENTITY, "Greataxe", "Chain Mail", true, true, FightingStyle.ARCHERY), catalog, "hero");
        assertThat(monk.attacks().get(0).name()).isEqualTo("Unarmed Strike");
        assertThat(monk.attacks().get(0).damage().sides()).isEqualTo(4); // level 3
        // 10 + Dex 14 (+2) + Wis 10 (0): no armor or shield
        assertThat(monk.ac()).isEqualTo(12);
    }

    @Test
    void genomeKeyIsStableAndDistinguishesGenomes() {
        LabeledRandom rng = new LabeledRandom(4);
        Genome a = Genomes.randomGenome(catalog, rng, "a", null);
        assertThat(Genomes.key(a)).isEqualTo(Genomes.key(new Genome(a.classSlug(), a.abilityAssignment(), a.weaponName(), a.armorName(),
                a.shield(), a.twoHanded(), a.fightingStyle())));
        Genome b = new Genome(a.classSlug() == BuildClass.FIGHTER ? BuildClass.ROGUE : BuildClass.FIGHTER, a.abilityAssignment(),
                a.weaponName(), a.armorName(), a.shield(), a.twoHanded(), a.fightingStyle());
        assertThat(Genomes.key(a)).isNotEqualTo(Genomes.key(b));
    }

    @Test
    void casterKeysIgnoreTheMartialGearGenes() {
        Genome a = genome(BuildClass.WIZARD, IDENTITY, "Dagger", null, false, false, null);
        Genome b = genome(BuildClass.WIZARD, IDENTITY, "Longbow", "Chain Mail", true, true, FightingStyle.ARCHERY);
        assertThat(Genomes.key(a)).isEqualTo(Genomes.key(b)).isEqualTo("wizard|012345");
    }
}
