package org.omnomnom.dnd.sim.adapter.out.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.domain.ai.TacticalPolicy;
import org.omnomnom.dnd.sim.domain.combat.AttackKind;
import org.omnomnom.dnd.sim.domain.combat.AttackProfile;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.combat.Encounter;
import org.omnomnom.dnd.sim.domain.combat.TurnPolicy;
import org.omnomnom.dnd.sim.domain.combat.event.CombatEvent;
import org.omnomnom.dnd.sim.domain.combat.event.EventLog;
import org.omnomnom.dnd.sim.domain.content.BuildProgression;
import org.omnomnom.dnd.sim.domain.content.ClassInfo;
import org.omnomnom.dnd.sim.domain.content.build.BuildSpec;
import org.omnomnom.dnd.sim.domain.content.build.CasterBuildSpec;
import org.omnomnom.dnd.sim.domain.content.build.CasterCompiler;
import org.omnomnom.dnd.sim.domain.content.build.CharacterCompiler;
import org.omnomnom.dnd.sim.domain.content.build.FightingStyle;
import org.omnomnom.dnd.sim.domain.content.build.Fillers;
import org.omnomnom.dnd.sim.domain.content.build.Role;
import org.omnomnom.dnd.sim.domain.content.build.SpellCatalog;
import org.omnomnom.dnd.sim.domain.content.build.UnarmoredDefense;
import org.omnomnom.dnd.sim.domain.content.equipment.WeaponInfo;
import org.omnomnom.dnd.sim.domain.content.feature.RageFeature;
import org.omnomnom.dnd.sim.domain.content.monster.MonsterCompiler;
import org.omnomnom.dnd.sim.domain.content.monster.MonsterOverrides;
import org.omnomnom.dnd.sim.domain.content.monster.MonsterTemplate;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.AbilityScores;
import org.omnomnom.dnd.sim.domain.core.DamageResponse;
import org.omnomnom.dnd.sim.domain.core.DamageType;
import org.omnomnom.dnd.sim.domain.core.Side;
import org.omnomnom.dnd.sim.domain.grid.Cell;
import org.omnomnom.dnd.sim.domain.grid.Grid;
import org.omnomnom.dnd.sim.domain.grid.GridMath;
import org.omnomnom.dnd.sim.domain.rng.LabeledRandom;

/**
 * End to end against the real seeds: ports of {@code caster.spec.ts} and {@code walking-skeleton.spec.ts} (seeds ->
 * compilers -> combatants -> deterministic combat), plus the reference-party fillers.
 */
class ContentIntegrationTest {

    static SqliteContentSource source;

    @BeforeAll
    static void open() {
        source = SqliteContentSource.open();
    }

    @AfterAll
    static void close() {
        source.close();
    }

    private static MonsterTemplate monster(String slug, boolean withMultiattack) {
        var src = source.monsterSources().stream().filter(s -> s.monster().monsterSlug().equals(slug)).findFirst().orElseThrow();
        var overrides = withMultiattack
                ? new org.omnomnom.dnd.sim.domain.content.monster.MonsterOverrides(
                        List.of(new MonsterTemplate.MultiattackEntry(src.actions().get(0).actionName(), 1)), 0)
                : org.omnomnom.dnd.sim.domain.content.monster.MonsterOverrides.NONE;
        return MonsterCompiler.compile(src, overrides);
    }

    // ---- caster.spec.ts ------------------------------------------------------------------------

    private static CasterBuildSpec.Builder wizard() {
        return CasterBuildSpec.builder("Wizard", source.classInfo("wizard"), 5, AbilityScores.of(8, 14, 14, 15, 12, 10),
                        source.weapon("Dagger"), Ability.INT)
                .cantrips(List.of(SpellCatalog.FIRE_BOLT))
                .spells(List.of(SpellCatalog.SCORCHING_RAY, SpellCatalog.FIREBALL))
                .slots(source.spellSlots("wizard", 5));
    }

    @Test
    void buildsALevelFiveWizardWithSlotsAndASaveDc() {
        Combatant w = CasterCompiler.compile(wizard().build());
        assertThat(w.ac()).isEqualTo(12); // 10 + Dex 2, unarmored
        assertThat(w.hp()).isEqualTo(6 + 2 + 4 * (4 + 2)); // d6, Con +2, L5 = 32
        assertThat(w.spellSaveDc()).isEqualTo(8 + 3 + 2);
        assertThat(w.slotCount(3)).isEqualTo(2);
        assertThat(w.spells()).hasSize(2);
    }

    @Test
    void aWizardCanWinAFightByCasting() {
        Combatant wiz = CasterCompiler.compile(wizard().id("wizard").position(new Cell(0, 6)).build());
        MonsterTemplate goblin = monster("goblin-warrior", false);
        List<Combatant> all = new ArrayList<>(List.of(wiz));
        int i = 0;
        for (Cell p : List.of(new Cell(10, 5), new Cell(10, 6), new Cell(10, 7))) {
            all.add(MonsterCompiler.spawn(goblin, new MonsterCompiler.Placement("g" + i++, Side.ENEMY, p)));
        }
        EventLog log = new EventLog();
        var res = Encounter.builder(Grid.open(16, 12), all, new LabeledRandom(2024))
                .policyFor(c -> c.id().equals("wizard") ? TacticalPolicy.DEFAULT : TurnPolicy.IDLE)
                .sink(log)
                .build()
                .run(20);
        assertThat(res.winner()).isEqualTo(Side.PARTY);
        // The wizard dealt its damage through spells (logged as spell events).
        assertThat(log.of(CombatEvent.SpellCast.class)).anyMatch(s -> s.caster().equals("wizard") && s.damage() > 0);
    }

    // ---- walking-skeleton.spec.ts --------------------------------------------------------------

    private static final TurnPolicy MELEE_AGGRESSOR = api -> {
        Combatant self = api.self();
        Combatant target = api.enemies().stream()
                .min(Comparator.comparingInt(c -> GridMath.distanceFt(self.position(), c.position())))
                .orElse(null);
        if (target == null || self.attacks().isEmpty()) {
            return;
        }
        AttackProfile weapon = self.attacks().stream().filter(w -> w.kind() == org.omnomnom.dnd.sim.domain.combat.AttackKind.MELEE)
                .findFirst().orElse(self.attacks().get(0));
        int reach = weapon.reachFtOrDefault();
        if (GridMath.distanceFt(self.position(), target.position()) > reach) {
            int steps = Math.max(1, reach / 5);
            // A cell adjacent to the target on the line from the mover, so it ends in reach.
            int x = target.position().x() - Integer.signum(target.position().x() - self.position().x()) * steps;
            int y = target.position().y() - Integer.signum(target.position().y() - self.position().y()) * steps;
            api.moveTo(new Cell(x, y));
        }
        if (GridMath.distanceFt(self.position(), target.position()) <= reach) {
            api.attack(target, weapon);
        }
    };

    private static Combatant champion(Cell position) {
        return CharacterCompiler.compile(BuildSpec.builder("Fighter", source.classInfo("fighter"), 3,
                        AbilityScores.of(16, 12, 14, 10, 10, 10), source.weapon("Longsword"))
                .id("fighter").subclass("champion").armor(source.armor("Chain Mail")).shield(true)
                .fightingStyle(FightingStyle.DEFENSE).position(position).build());
    }

    @Test
    void aLevelThreeChampionCompilesWithTheExpectedStatline() {
        Combatant fighter = champion(new Cell(0, 0));
        assertThat(fighter.ac()).isEqualTo(19);
        assertThat(fighter.hp()).isEqualTo(28);
        assertThat(fighter.attacks().get(0).attackBonus()).isEqualTo(5);
    }

    @Test
    void theFighterFightsThreeGoblinsDeterministically() {
        MonsterTemplate goblin = monster("goblin-warrior", true);
        var run = (java.util.function.Supplier<Object[]>) () -> {
            List<Combatant> all = new ArrayList<>(List.of(champion(new Cell(0, 5))));
            int i = 0;
            for (Cell p : List.of(new Cell(8, 4), new Cell(8, 5), new Cell(8, 6))) {
                all.add(MonsterCompiler.spawn(goblin, new MonsterCompiler.Placement("goblin-" + i++, Side.ENEMY, p)));
            }
            EventLog log = new EventLog();
            var r = Encounter.builder(Grid.open(12, 12), all, new LabeledRandom(2024))
                    .policyFor(c -> c.isConscious() ? MELEE_AGGRESSOR : TurnPolicy.IDLE)
                    .sink(log)
                    .build()
                    .run(50);
            return new Object[] {r, log.events()};
        };
        Object[] a = run.get();
        Object[] b = run.get();
        assertThat(((Encounter.RunResult) a[0]).rounds()).isLessThanOrEqualTo(50);
        assertThat(a[0]).isEqualTo(b[0]);
        assertThat(a[1]).isEqualTo(b[1]); // common random numbers: identical replay
    }

    @Test
    void aBarbarianCompiledFromTheSeedsRages() {
        ClassInfo barbarianClass = source.classInfo("barbarian");
        WeaponInfo greataxe = source.weapon("Greataxe");
        BuildProgression progression = source.progression("barbarian", 3);
        assertThat(progression.rageUses()).isEqualTo(3);
        assertThat(progression.rageDamageBonus()).isEqualTo(2);
        assertThat(progression.extraAttacks()).isZero();

        Combatant barb = CharacterCompiler.compile(BuildSpec.builder("Barbarian", barbarianClass, 3,
                        AbilityScores.of(16, 14, 16, 8, 10, 8), greataxe)
                .id("barbarian").twoHanded(true).unarmoredDefense(UnarmoredDefense.BARBARIAN)
                .progression(progression).position(new Cell(0, 0)).build());
        assertThat(barb.ac()).isEqualTo(15); // 10 + Dex 2 + Con 3
        assertThat(barb.hp()).isEqualTo(35);
        assertThat(barb.resourceCount("rage")).isEqualTo(3);

        Combatant foe = CharacterCompiler.compile(BuildSpec.builder("Foe", barbarianClass, 3,
                        AbilityScores.of(20, 10, 14, 8, 10, 8), greataxe)
                .id("foe").side(Side.ENEMY).twoHanded(true).unarmoredDefense(UnarmoredDefense.BARBARIAN)
                .position(new Cell(1, 0)).build());
        TurnPolicy attack = api -> {
            var t = api.enemies();
            if (!t.isEmpty() && !api.self().attacks().isEmpty()) {
                api.attack(t.get(0), api.self().attacks().get(0));
            }
        };
        Encounter.builder(Grid.open(10, 10), List.of(barb, foe), new LabeledRandom(2024))
                .policyFor(c -> attack).build().run(30);

        // The barbarian activated Rage (a use was spent) and is resisting B/P/S.
        assertThat(barb.resourceCount("rage")).isLessThan(3);
        assertThat(((RageFeature) barb.features().stream().filter(f -> f.id().equals("rage")).findFirst().orElseThrow()).isRaging()).isTrue();
        assertThat(barb.damageResponseFor(DamageType.SLASHING)).isEqualTo(DamageResponse.RESISTANT);
    }

    // ---- fillers -------------------------------------------------------------------------------

    @Test
    void everyRoleIsBuildableAtEveryCheckpointLevel() {
        for (int level : List.of(3, 5, 11, 17)) {
            Map<Role, Fillers.Filler> fillers = Fillers.load(source, level);
            assertThat(fillers.keySet()).containsExactlyInAnyOrder(Role.values());
            for (Role role : Role.values()) {
                Combatant c = fillers.get(role).make("ally-" + role.code(), Side.PARTY, new Cell(1, 1));
                assertThat(c.level()).isEqualTo(level);
                assertThat(c.hp()).isPositive();
                assertThat(c.id()).isEqualTo("ally-" + role.code());
            }
        }
    }

    @Test
    void fillersHaveTheirFrozenRecipes() {
        Map<Role, Fillers.Filler> fillers = Fillers.load(source, 5);
        Combatant tank = fillers.get(Role.TANK).make("t", Side.PARTY, new Cell(0, 0));
        assertThat(tank.ac()).isEqualTo(16 + 2 + 1); // chain mail, shield, Defense style
        assertThat(tank.extraAttacks()).isZero(); // fillers carry no progression table except rogue/ranger
        Combatant healer = fillers.get(Role.HEALER).make("h", Side.PARTY, new Cell(0, 0));
        assertThat(healer.spells().stream().map(s -> s.id())).containsExactly("cure-wounds", "healing-word", "guiding-bolt");
        Combatant controller = fillers.get(Role.CONTROLLER).make("c", Side.PARTY, new Cell(0, 0));
        assertThat(controller.spells().stream().map(s -> s.id()))
                .containsExactly("hypnotic-pattern", "hold-person", "fireball", "scorching-ray");
        Combatant ranger = fillers.get(Role.SUSTAINED_DPS).make("r", Side.PARTY, new Cell(0, 0));
        assertThat(ranger.extraAttacks()).isEqualTo(1);
        assertThat(ranger.features().stream().map(f -> f.id())).containsExactly("hunters-mark", "colossus-slayer");
    }

    @Test
    void fillersAreIndependentOfTheSourceAfterItIsClosed() {
        SqliteContentSource temp = SqliteContentSource.open();
        Map<Role, Fillers.Filler> fillers = Fillers.load(temp, 5);
        temp.close();
        assertThat(fillers.get(Role.BURST).make("b", Side.PARTY, new Cell(0, 0)).hp()).isPositive();
    }

    @Test
    void fillersMakeIndependentCombatantsEachTime() {
        var filler = Fillers.load(source, 5).get(Role.BURST);
        Combatant a = filler.make("a", Side.PARTY, new Cell(0, 0));
        Combatant b = filler.make("b", Side.PARTY, new Cell(1, 0));
        a.takeDamage(5);
        assertThat(b.hp()).isEqualTo(b.maxHp());
        assertThat(a.features().get(0)).isNotSameAs(b.features().get(0));
    }
}
