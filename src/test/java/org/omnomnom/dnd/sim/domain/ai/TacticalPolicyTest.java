package org.omnomnom.dnd.sim.domain.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.domain.combat.AttackKind;
import org.omnomnom.dnd.sim.domain.combat.AttackProfile;
import org.omnomnom.dnd.sim.domain.combat.CombatEvent;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.combat.CombatantSpec;
import org.omnomnom.dnd.sim.domain.combat.Encounter;
import org.omnomnom.dnd.sim.domain.combat.EventLog;
import org.omnomnom.dnd.sim.domain.combat.ExtraDamage;
import org.omnomnom.dnd.sim.domain.combat.Side;
import org.omnomnom.dnd.sim.domain.combat.TurnPolicy;
import org.omnomnom.dnd.sim.domain.core.AbilityScores;
import org.omnomnom.dnd.sim.domain.core.DamageType;
import org.omnomnom.dnd.sim.domain.dice.Dice;
import org.omnomnom.dnd.sim.domain.grid.Cell;
import org.omnomnom.dnd.sim.domain.grid.Grid;
import org.omnomnom.dnd.sim.domain.rng.LabeledRandom;

/**
 * Port of {@code sim/src/ai/policy.spec.ts}. Behavior beyond these basics is verified against the original engine by
 * {@code EncounterParityTest} and {@code SweepParityTest} (the AI running both sides of 370 fights).
 */
class TacticalPolicyTest {

    static final AttackProfile SWORD =
            AttackProfile.builder("sword", AttackKind.MELEE, 8, Dice.of(1, 8, 4), DamageType.SLASHING).reachFt(5).build();
    static final AttackProfile BOW = AttackProfile.builder("bow", AttackKind.RANGED, 8, Dice.of(1, 6, 3), DamageType.PIERCING)
            .rangeFt(80).rangeLongFt(320).build();

    static CombatantSpec.Builder mk(String id, Side side) {
        return CombatantSpec.builder(id, "C", side, 3, AbilityScores.of(16, 16, 14, 10, 10, 10), 15, 30);
    }

    @Test
    void weaponAverageDamageCountsRiders() {
        assertThat(TacticalPolicy.weaponAverageDamage(SWORD)).isEqualTo(4.5 + 4);
        AttackProfile flaming = AttackProfile.builder("sword", AttackKind.MELEE, 8, Dice.of(1, 8, 4), DamageType.SLASHING)
                .reachFt(5).extraDamage(List.of(new ExtraDamage(Dice.of(2, 6), DamageType.FIRE))).build();
        assertThat(TacticalPolicy.weaponAverageDamage(flaming)).isEqualTo(4.5 + 4 + 7);
    }

    @Test
    void primaryWeaponPicksTheHighestAverageWeapon() {
        Combatant c = new Combatant(mk("c", Side.PARTY).attacks(List.of(BOW, SWORD)).build());
        assertThat(TacticalPolicy.primaryWeapon(c).name()).isEqualTo("sword"); // 8.5 vs 6.5
    }

    @Test
    void primaryWeaponIsNullForAnUnarmedCreature() {
        assertThat(TacticalPolicy.primaryWeapon(new Combatant(mk("c", Side.PARTY).build()))).isNull();
        assertThat(TacticalPolicy.threatOf(new Combatant(mk("c", Side.PARTY).build()))).isZero();
    }

    @Test
    void threatScalesWithExtraAttacks() {
        Combatant one = new Combatant(mk("c", Side.PARTY).attacks(List.of(SWORD)).build());
        Combatant two = new Combatant(mk("c", Side.PARTY).attacks(List.of(SWORD)).extraAttacks(1).build());
        assertThat(TacticalPolicy.threatOf(two)).isEqualTo(TacticalPolicy.threatOf(one) * 2);
    }

    @Test
    void scoreTargetPrefersAWoundedTargetOverAHealthyOne() {
        Combatant self = new Combatant(mk("self", Side.PARTY).attacks(List.of(SWORD)).position(new Cell(0, 0)).build());
        Combatant healthy = new Combatant(mk("h", Side.ENEMY).position(new Cell(1, 0)).build());
        Combatant wounded = new Combatant(mk("w", Side.ENEMY).position(new Cell(1, 0)).build());
        wounded.takeDamage(20); // 10/30 left
        assertThat(TacticalPolicy.scoreTarget(self, wounded, TacticsWeights.DEFAULT))
                .isGreaterThan(TacticalPolicy.scoreTarget(self, healthy, TacticsWeights.DEFAULT));
    }

    private static Encounter fight(List<Combatant> cs, long seed, Grid grid, java.util.function.Function<Combatant, TurnPolicy> policy, EventLog log) {
        return Encounter.builder(grid, cs, new LabeledRandom(seed)).policyFor(policy).sink(log).build();
    }

    @Test
    void aMeleeAttackerClosesAndKillsAPassiveDummy() {
        Combatant hero = new Combatant(mk("hero", Side.PARTY).attacks(List.of(SWORD)).position(new Cell(0, 0)).build());
        Combatant dummy = new Combatant(CombatantSpec.builder("dummy", "C", Side.ENEMY, 3, AbilityScores.of(16, 16, 14, 10, 10, 10), 10, 7)
                .position(new Cell(5, 0)).build());
        EventLog log = new EventLog();
        var result = fight(List.of(hero, dummy), 42, Grid.open(12, 12),
                c -> c.id().equals("hero") ? TacticalPolicy.DEFAULT : TurnPolicy.IDLE, log).run(20);
        assertThat(result.winner()).isEqualTo(Side.PARTY);
        assertThat(dummy.isConscious()).isFalse();
        assertThat(log.of(CombatEvent.Move.class)).anyMatch(m -> m.id().equals("hero"));
    }

    @Test
    void aRangedAttackerFiresWithoutClosingToMelee() {
        Combatant archer = new Combatant(mk("archer", Side.PARTY).attacks(List.of(BOW)).position(new Cell(0, 0)).build());
        Combatant dummy = new Combatant(CombatantSpec.builder("dummy", "C", Side.ENEMY, 3, AbilityScores.of(16, 16, 14, 10, 10, 10), 10, 7)
                .position(new Cell(6, 0)).build()); // 30 ft
        EventLog log = new EventLog();
        fight(List.of(archer, dummy), 7, Grid.open(20, 20),
                c -> c.id().equals("archer") ? TacticalPolicy.DEFAULT : TurnPolicy.IDLE, log).run(20);
        // Already within 80 ft bow range, so no movement needed.
        assertThat(log.of(CombatEvent.Move.class)).noneMatch(m -> m.id().equals("archer"));
        assertThat(dummy.isConscious()).isFalse();
    }

    @Test
    void focusesFireOnTheWoundedEnemyFirst() {
        Combatant hero = new Combatant(mk("hero", Side.PARTY).attacks(List.of(SWORD)).position(new Cell(0, 0)).build());
        Combatant full = new Combatant(CombatantSpec.builder("full", "C", Side.ENEMY, 3,
                AbilityScores.of(16, 16, 14, 10, 10, 10), 30, 20).position(new Cell(1, 0)).build());
        Combatant wounded = new Combatant(CombatantSpec.builder("wounded", "C", Side.ENEMY, 3,
                AbilityScores.of(16, 16, 14, 10, 10, 10), 30, 20).position(new Cell(0, 1)).build());
        wounded.takeDamage(16); // 4 HP left
        EventLog log = new EventLog();
        Encounter e = fight(List.of(hero, full, wounded), 3, Grid.open(12, 12),
                c -> c.id().equals("hero") ? TacticalPolicy.DEFAULT : TurnPolicy.IDLE, log);
        e.rollInitiative();
        e.runRound();
        var first = log.of(CombatEvent.Attack.class).stream().filter(a -> a.attacker().equals("hero")).findFirst();
        assertThat(first).isPresent();
        assertThat(first.get().target()).isEqualTo("wounded");
    }

    @Test
    void isDeterministicUnderASeed() {
        var play = (java.util.function.Supplier<List<CombatEvent>>) () -> {
            Combatant hero = new Combatant(mk("hero", Side.PARTY).attacks(List.of(SWORD)).position(new Cell(0, 0)).build());
            Combatant foe = new Combatant(CombatantSpec.builder("foe", "C", Side.ENEMY, 3, AbilityScores.of(16, 16, 14, 10, 10, 10), 14, 25)
                    .attacks(List.of(SWORD)).position(new Cell(4, 0)).build());
            EventLog log = new EventLog();
            fight(List.of(hero, foe), 99, Grid.open(12, 12), c -> TacticalPolicy.DEFAULT, log).run(30);
            return List.copyOf(log.events());
        };
        assertThat(play.get()).isEqualTo(play.get());
    }

    @Test
    void weightsAreConfigurableWithoutTouchingTheEngine() {
        TurnPolicy aggressive = TacticalPolicy.create(new TacticsWeights(0, 10, 0.1, 5, 0.6));
        assertThat(aggressive).isNotNull();
    }

    @Test
    void onePolicyInstanceCanDriveConcurrentFights() throws Exception {
        // The policy holds no state, so the shared DEFAULT can be used from many threads at once.
        var results = new java.util.concurrent.ConcurrentHashMap<Integer, List<CombatEvent>>();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int slot = t;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < 20; i++) {
                        Combatant hero = new Combatant(mk("hero", Side.PARTY).attacks(List.of(SWORD)).position(new Cell(0, 0)).build());
                        Combatant foe = new Combatant(CombatantSpec.builder("foe", "C", Side.ENEMY, 3,
                                AbilityScores.of(16, 16, 14, 10, 10, 10), 14, 25).attacks(List.of(SWORD)).position(new Cell(4, 0)).build());
                        EventLog log = new EventLog();
                        fight(List.of(hero, foe), 99, Grid.open(12, 12), c -> TacticalPolicy.DEFAULT, log).run(30);
                        results.merge(slot * 100 + i, List.copyOf(log.events()), (a, b) -> a);
                    }
                }));
            }
            for (var f : futures) {
                f.get();
            }
        }
        var distinct = results.values().stream().distinct().count();
        assertThat(results).hasSize(160);
        assertThat(distinct).as("same seed and state must give the same log on every thread").isEqualTo(1);
    }
}
