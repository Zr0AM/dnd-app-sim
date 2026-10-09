package org.omnomnom.dnd.sim.domain.content.monster;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.domain.combat.AttackKind;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.combat.Encounter;
import org.omnomnom.dnd.sim.domain.combat.TurnPolicy;
import org.omnomnom.dnd.sim.domain.combat.event.CombatEvent;
import org.omnomnom.dnd.sim.domain.combat.event.EventLog;
import org.omnomnom.dnd.sim.domain.content.MonsterSource.ActionRow;
import org.omnomnom.dnd.sim.domain.content.MonsterSource.DamageRow;
import org.omnomnom.dnd.sim.domain.content.MonsterSource.DefenseRow;
import org.omnomnom.dnd.sim.domain.content.MonsterSource.MonsterRow;
import org.omnomnom.dnd.sim.domain.content.MonsterSource.SaveRow;
import org.omnomnom.dnd.sim.domain.content.MonsterSource.SpeedRow;
import org.omnomnom.dnd.sim.domain.content.MonsterSource;
import org.omnomnom.dnd.sim.domain.content.monster.MonsterCompiler.Placement;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.DamageResponse;
import org.omnomnom.dnd.sim.domain.core.DamageType;
import org.omnomnom.dnd.sim.domain.core.Side;
import org.omnomnom.dnd.sim.domain.grid.Cell;
import org.omnomnom.dnd.sim.domain.grid.Grid;
import org.omnomnom.dnd.sim.domain.rng.LabeledRandom;

/** Port of {@code sim/src/content/monster.spec.ts}. */
class MonsterCompilerTest {

    // Goblin Minion, exactly as the seed rows give it: AC 12, HP 7, Dagger +4 (1d4+2 piercing), melee_or_ranged.
    static final MonsterSource GOBLIN_MINION = new MonsterSource(
            new MonsterRow("goblin-minion", "Goblin Minion", 12, 7, 8, 15, 10, 10, 8, 8, 0.125),
            List.of(
                    new ActionRow(410, "action", "Dagger", "melee_or_ranged", 4, 5, 20, 60),
                    new ActionRow(411, "bonus_action", "Nimble Escape", null, null, null, null, null)),
            List.of(new DamageRow(410, 0, 1, 4, 2, 4, 8)),
            List.of(),
            List.of(),
            List.of(new SpeedRow("walk", 30)));

    // The Animated Rug's Smother: 2d6+3 bludgeoning, listed twice (index 0 and 1), a real two-row example.
    static final MonsterSource RUG = new MonsterSource(
            new MonsterRow("rug", "Rug", 12, 33, 17, 14, 10, 1, 3, 1, 2),
            List.of(new ActionRow(1, "action", "Smother", "melee", 5, 5, null, null)),
            List.of(new DamageRow(1, 0, 2, 6, 3, 10, 2), new DamageRow(1, 1, 2, 6, 3, 10, 2)));

    private static MonsterSource with(MonsterSource base, List<SaveRow> saves, List<DefenseRow> defenses) {
        return new MonsterSource(base.monster(), base.actions(), base.damage(), saves, defenses, base.speeds());
    }

    @Test
    void mapsTheStatBlockScalars() {
        MonsterTemplate t = MonsterCompiler.compile(GOBLIN_MINION);
        assertThat(t.ac()).isEqualTo(12);
        assertThat(t.maxHp()).isEqualTo(7);
        assertThat(t.cr()).isEqualTo(0.125);
        assertThat(t.abilities().dex()).isEqualTo(15);
        assertThat(t.speedFt()).isEqualTo(30);
    }

    @Test
    void speedDefaultsToThirtyWithoutAWalkRow() {
        assertThat(MonsterCompiler.compile(RUG).speedFt()).isEqualTo(30);
    }

    @Test
    void compilesAnAttackActionIntoAProfile() {
        MonsterTemplate t = MonsterCompiler.compile(GOBLIN_MINION);
        assertThat(t.attacks()).hasSize(1); // only the action attack, not the bonus action
        var dagger = t.attacks().get(0);
        assertThat(dagger.name()).isEqualTo("Dagger");
        assertThat(dagger.attackBonus()).isEqualTo(4);
        assertThat(dagger.damageType()).isEqualTo(DamageType.PIERCING);
        assertThat(dagger.damage().mean()).isEqualTo(4.5); // 1d4+2
        assertThat(dagger.kind()).isEqualTo(AttackKind.MELEE); // melee_or_ranged modeled as melee
        assertThat(dagger.reachFt()).isEqualTo(5);
        assertThat(dagger.rangeFt()).isEqualTo(20);
        assertThat(dagger.rangeLongFt()).isEqualTo(60);
    }

    @Test
    void skipsNonAttackActions() {
        assertThat(MonsterCompiler.compile(GOBLIN_MINION).attacks()).noneMatch(a -> a.name().equals("Nimble Escape"));
    }

    @Test
    void capturesASecondDamageRowAsAnExtraRider() {
        var smother = MonsterCompiler.compile(RUG).attacks().get(0);
        assertThat(smother.extraDamage()).hasSize(1);
        assertThat(smother.extraDamage().get(0).type()).isEqualTo(DamageType.BLUDGEONING);
        assertThat(smother.extraDamage().get(0).damage().mean()).isEqualTo(10); // 2d6+3
    }

    @Test
    void flatDamageWithoutDiceBecomesAFlatBonus() {
        MonsterSource flat = new MonsterSource(GOBLIN_MINION.monster(), GOBLIN_MINION.actions(),
                List.of(new DamageRow(410, 0, null, null, null, 7, 8)));
        var dmg = MonsterCompiler.compile(flat).attacks().get(0).damage();
        assertThat(dmg.count()).isZero();
        assertThat(dmg.bonus()).isEqualTo(7);
        assertThat(dmg.mean()).isEqualTo(7);
    }

    @Test
    void diceWithoutABonusRollWithAZeroBonus() {
        MonsterSource plain = new MonsterSource(GOBLIN_MINION.monster(), GOBLIN_MINION.actions(),
                List.of(new DamageRow(410, 0, 1, 6, null, 4, 8)));
        var dmg = MonsterCompiler.compile(plain).attacks().get(0).damage();
        assertThat(dmg.bonus()).isZero();
        assertThat(dmg.mean()).isEqualTo(3.5);
    }

    @Test
    void anActionWithNoDamageRowsIsNotAnAttack() {
        MonsterSource none = new MonsterSource(GOBLIN_MINION.monster(), GOBLIN_MINION.actions(), List.of());
        assertThat(MonsterCompiler.compile(none).attacks()).isEmpty();
    }

    @Test
    void rangedKindStaysRangedAndHasNoDefaultReach() {
        MonsterSource archer = new MonsterSource(GOBLIN_MINION.monster(),
                List.of(new ActionRow(410, "action", "Bow", "ranged", 4, null, 80, 320)),
                GOBLIN_MINION.damage());
        var bow = MonsterCompiler.compile(archer).attacks().get(0);
        assertThat(bow.kind()).isEqualTo(AttackKind.RANGED);
        assertThat(bow.reachFt()).isNull();
        assertThat(bow.rangeFt()).isEqualTo(80);
    }

    @Test
    void mapsSavesToExplicitBonusesAndDefensesToResponses() {
        MonsterTemplate t = MonsterCompiler.compile(with(GOBLIN_MINION,
                List.of(new SaveRow(2, 4)), // Dex +4
                List.of(new DefenseRow("resistance", 4, null), // fire
                        new DefenseRow("immunity", 9, null), // poison
                        new DefenseRow("vulnerability", 7, null), // necrotic
                        new DefenseRow("immunity", null, 1)))); // a condition immunity: ignored for now
        assertThat(t.saveBonuses()).containsEntry(Ability.DEX, 4);
        assertThat(t.damageResponses()).containsEntry(DamageType.FIRE, DamageResponse.RESISTANT)
                .containsEntry(DamageType.POISON, DamageResponse.IMMUNE)
                .containsEntry(DamageType.NECROTIC, DamageResponse.VULNERABLE)
                .hasSize(3);
    }

    @Test
    void acceptsAHandAuthoredMultiattackOverride() {
        MonsterTemplate t = MonsterCompiler.compile(GOBLIN_MINION,
                new MonsterOverrides(List.of(new MonsterTemplate.MultiattackEntry("Dagger", 2)), 0));
        assertThat(t.multiattack()).containsExactly(new MonsterTemplate.MultiattackEntry("Dagger", 2));
    }

    @Test
    void multiattackTableCoversBossesAndDefaultsToNone() {
        assertThat(MonsterMultiattack.overridesFor("troll").multiattack())
                .containsExactly(new MonsterTemplate.MultiattackEntry("Rend", 3));
        assertThat(MonsterMultiattack.overridesFor("young-red-dragon").legendaryActions()).isEqualTo(3);
        assertThat(MonsterMultiattack.overridesFor("storm-giant").legendaryActions()).isZero();
        assertThat(MonsterMultiattack.overridesFor("goblin-minion")).isEqualTo(MonsterOverrides.NONE);
    }

    // ---- spawnMonster --------------------------------------------------------------------------

    @Test
    void spawnedCombatantUsesItsExplicitSaveBonus() {
        MonsterTemplate t = MonsterCompiler.compile(with(GOBLIN_MINION, List.of(new SaveRow(3, 5)), List.of()));
        Combatant c = MonsterCompiler.spawn(t, new Placement("g1", Side.ENEMY, new Cell(3, 3)));
        assertThat(c.saveBonus(Ability.CON)).isEqualTo(5); // override, not ability + proficiency
        assertThat(c.ac()).isEqualTo(12);
        assertThat(c.hp()).isEqualTo(7);
        assertThat(c.position()).isEqualTo(new Cell(3, 3));
    }

    @Test
    void multiattackBecomesExtraAttacks() {
        MonsterTemplate troll = MonsterCompiler.compile(GOBLIN_MINION, MonsterMultiattack.overridesFor("troll"));
        assertThat(MonsterCompiler.spawn(troll, new Placement("t", Side.ENEMY, new Cell(0, 0))).extraAttacks()).isEqualTo(2);
        MonsterTemplate plain = MonsterCompiler.compile(GOBLIN_MINION);
        assertThat(MonsterCompiler.spawn(plain, new Placement("t", Side.ENEMY, new Cell(0, 0))).extraAttacks()).isZero();
    }

    @Test
    void spawningTwiceGivesIndependentCombatants() {
        MonsterTemplate t = MonsterCompiler.compile(GOBLIN_MINION);
        Combatant a = MonsterCompiler.spawn(t, new Placement("a", Side.ENEMY, new Cell(0, 0)));
        Combatant b = MonsterCompiler.spawn(t, new Placement("b", Side.ENEMY, new Cell(1, 0)));
        a.takeDamage(5);
        assertThat(a.hp()).isEqualTo(2);
        assertThat(b.hp()).isEqualTo(7);
    }

    @Test
    void aCompiledGoblinCanFightInTheEngine() {
        MonsterTemplate t = MonsterCompiler.compile(GOBLIN_MINION,
                new MonsterOverrides(List.of(new MonsterTemplate.MultiattackEntry("Dagger", 1)), 0));
        Combatant g = MonsterCompiler.spawn(t, new Placement("g1", Side.ENEMY, new Cell(1, 0)));
        // A sturdy hero (a re-statted goblin) that attacks the goblin should win and the goblin should die.
        MonsterSource heroSrc = new MonsterSource(
                new MonsterRow("hero", "Hero", 18, 40, 8, 15, 10, 10, 8, 8, 0.125),
                List.of(new ActionRow(410, "action", "Dagger", "melee_or_ranged", 8, 5, 20, 60)),
                List.of(new DamageRow(410, 0, 2, 6, 4, 11, 12)));
        Combatant hero = MonsterCompiler.spawn(MonsterCompiler.compile(heroSrc), new Placement("hero", Side.PARTY, new Cell(0, 0)));
        TurnPolicy attack = api -> {
            var targets = api.enemies();
            if (!targets.isEmpty() && !api.self().attacks().isEmpty()) {
                api.attack(targets.get(0), api.self().attacks().get(0));
            }
        };
        EventLog log = new EventLog();
        var result = Encounter.builder(Grid.open(10, 10), List.of(hero, g), new LabeledRandom(2024))
                .policyFor(c -> c.side() == Side.PARTY ? attack : TurnPolicy.IDLE)
                .sink(log)
                .build()
                .run();
        assertThat(result.winner()).isEqualTo(Side.PARTY);
        assertThat(g.isConscious()).isFalse();
        assertThat(log.of(CombatEvent.Attack.class)).isNotEmpty();
    }
}
