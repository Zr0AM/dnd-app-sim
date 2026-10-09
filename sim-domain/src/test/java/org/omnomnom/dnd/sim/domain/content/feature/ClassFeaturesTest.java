package org.omnomnom.dnd.sim.domain.content.feature;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.domain.ai.TacticalPolicy;
import org.omnomnom.dnd.sim.domain.combat.ActiveForm;
import org.omnomnom.dnd.sim.domain.combat.AttackKind;
import org.omnomnom.dnd.sim.domain.combat.AttackProfile;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.combat.CombatantSpec;
import org.omnomnom.dnd.sim.domain.combat.Encounter;
import org.omnomnom.dnd.sim.domain.combat.FeatureFactory;
import org.omnomnom.dnd.sim.domain.combat.OnHitContext;
import org.omnomnom.dnd.sim.domain.combat.Recharge;
import org.omnomnom.dnd.sim.domain.combat.ResourceSpec;
import org.omnomnom.dnd.sim.domain.combat.TurnPolicy;
import org.omnomnom.dnd.sim.domain.combat.event.CombatEvent;
import org.omnomnom.dnd.sim.domain.combat.event.EventLog;
import org.omnomnom.dnd.sim.domain.combat.spell.SpellcastingSpec;
import org.omnomnom.dnd.sim.domain.content.feature.WildShapeFeature.BeastForm;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.AbilityScores;
import org.omnomnom.dnd.sim.domain.core.Condition;
import org.omnomnom.dnd.sim.domain.core.DamageResponse;
import org.omnomnom.dnd.sim.domain.core.DamageType;
import org.omnomnom.dnd.sim.domain.core.Side;
import org.omnomnom.dnd.sim.domain.dice.Advantage;
import org.omnomnom.dnd.sim.domain.dice.Dice;
import org.omnomnom.dnd.sim.domain.grid.Cell;
import org.omnomnom.dnd.sim.domain.grid.Grid;
import org.omnomnom.dnd.sim.domain.rng.LabeledRandom;

/** Port of {@code sim/src/content/martial-features.spec.ts}. */
class ClassFeaturesTest {

    static final AttackProfile GREATAXE =
            AttackProfile.builder("Greataxe", AttackKind.MELEE, 5, Dice.of(1, 12, 3), DamageType.SLASHING).reachFt(5).build();
    static final AttackProfile RAPIER =
            AttackProfile.builder("Rapier", AttackKind.MELEE, 5, Dice.of(1, 8, 3), DamageType.PIERCING).reachFt(5).finesse(true).build();
    static final AttackProfile LONGBOW =
            AttackProfile.builder("Longbow", AttackKind.RANGED, 7, Dice.of(1, 8, 2), DamageType.PIERCING).rangeFt(150).build();

    static CombatantSpec.Builder spec(String id) {
        return CombatantSpec.builder(id, id, Side.PARTY, 3, AbilityScores.of(16, 16, 14, 10, 10, 10), 15, 30);
    }

    static Combatant combatant(String id) {
        return new Combatant(spec(id).build());
    }

    static Combatant combatant(String id, UnaryOperator<CombatantSpec.Builder> f) {
        return new Combatant(f.apply(spec(id)).build());
    }

    static OnHitContext ctx(Combatant self, Combatant target, AttackProfile weapon) {
        return new OnHitContext(self, target, weapon, false, Advantage.NORMAL, false);
    }

    static OnHitContext ctx(Combatant self, Combatant target, AttackProfile weapon, Advantage adv, boolean allyAdjacent) {
        return new OnHitContext(self, target, weapon, false, adv, allyAdjacent);
    }

    // ---- Rage ----------------------------------------------------------------------------------

    @Test
    void rageActivatesOnTurnStartWhenAUseIsAvailableSpendingIt() {
        Combatant barb = combatant("barb", b -> b.resources(List.of(ResourceSpec.longRest("rage", 3))));
        RageFeature rage = new RageFeature(2);
        assertThat(rage.isRaging()).isFalse();
        rage.onTurnStart(barb);
        assertThat(rage.isRaging()).isTrue();
        assertThat(barb.resourceCount("rage")).isEqualTo(2);
    }

    @Test
    void rageDoesNotActivateWithNoUsesLeft() {
        Combatant barb = combatant("barb", b -> b.resources(List.of(ResourceSpec.longRest("rage", 0))));
        RageFeature rage = new RageFeature(2);
        rage.onTurnStart(barb);
        assertThat(rage.isRaging()).isFalse();
    }

    @Test
    void rageGrantsResistanceToBpsOnlyWhileRaging() {
        Combatant barb = combatant("barb", b -> b.resources(List.of(ResourceSpec.longRest("rage", 1))));
        RageFeature rage = new RageFeature(2);
        assertThat(rage.resistsDamage(barb, DamageType.SLASHING)).isFalse();
        rage.onTurnStart(barb);
        assertThat(rage.resistsDamage(barb, DamageType.SLASHING)).isTrue();
        assertThat(rage.resistsDamage(barb, DamageType.FIRE)).isFalse();
    }

    @Test
    void rageAddsDamageToMeleeHitsOnly() {
        Combatant barb = combatant("barb", b -> b.resources(List.of(ResourceSpec.longRest("rage", 1))));
        RageFeature rage = new RageFeature(2);
        rage.onTurnStart(barb);
        var extra = rage.onHit(ctx(barb, combatant("t"), GREATAXE));
        assertThat(extra).hasSize(1);
        assertThat(extra.get(0).type()).isEqualTo(DamageType.SLASHING);
        assertThat(extra.get(0).damage().bonus()).isEqualTo(2);
        assertThat(rage.onHit(ctx(barb, combatant("t"), LONGBOW))).isEmpty();
    }

    // ---- Reckless Attack -----------------------------------------------------------------------

    @Test
    void recklessGivesAdvantageOnMeleeThenGrantsAttackersAdvantage() {
        RecklessAttackFeature r = new RecklessAttackFeature();
        assertThat(r.grantsAttackersAdvantage(null)).isFalse();
        var mods = r.outgoingAttack(combatant("b"), combatant("t"), GREATAXE);
        assertThat(mods.advantage()).isTrue();
        assertThat(r.grantsAttackersAdvantage(null)).isTrue();
    }

    @Test
    void recklessWindowClosesAtTheStartOfTheNextTurn() {
        RecklessAttackFeature r = new RecklessAttackFeature();
        r.outgoingAttack(combatant("b"), combatant("t"), GREATAXE);
        assertThat(r.grantsAttackersAdvantage(null)).isTrue();
        r.onTurnStart(null);
        assertThat(r.grantsAttackersAdvantage(null)).isFalse();
    }

    @Test
    void recklessDoesNothingOnRangedAttacks() {
        RecklessAttackFeature r = new RecklessAttackFeature();
        assertThat(r.outgoingAttack(combatant("b"), combatant("t"), LONGBOW).advantage()).isFalse();
        assertThat(r.grantsAttackersAdvantage(null)).isFalse();
    }

    // ---- Sneak Attack --------------------------------------------------------------------------

    @Test
    void sneakAttackTriggersOncePerTurnWithAdvantageOnAFinesseWeapon() {
        SneakAttackFeature s = new SneakAttackFeature(2);
        Combatant self = combatant("rogue");
        Combatant target = combatant("t");
        var first = s.onHit(ctx(self, target, RAPIER, Advantage.ADVANTAGE, false));
        assertThat(first).hasSize(1);
        assertThat(first.get(0).damage().count()).isEqualTo(2);
        assertThat(first.get(0).damage().sides()).isEqualTo(6);
        assertThat(s.onHit(ctx(self, target, RAPIER, Advantage.ADVANTAGE, false))).isEmpty();
        s.onTurnStart(null);
        assertThat(s.onHit(ctx(self, target, RAPIER, Advantage.ADVANTAGE, false))).hasSize(1);
    }

    @Test
    void sneakAttackTriggersFromAnAdjacentAllyWithoutAdvantage() {
        assertThat(new SneakAttackFeature(2)
                        .onHit(ctx(combatant("r"), combatant("t"), RAPIER, Advantage.NORMAL, true)))
                .hasSize(1);
    }

    @Test
    void sneakAttackDoesNotTriggerAtDisadvantageEvenWithAnAdjacentAlly() {
        assertThat(new SneakAttackFeature(2)
                        .onHit(ctx(combatant("r"), combatant("t"), RAPIER, Advantage.DISADVANTAGE, true)))
                .isEmpty();
    }

    @Test
    void sneakAttackNeedsAdvantageOrAnAlly() {
        assertThat(new SneakAttackFeature(2).onHit(ctx(combatant("r"), combatant("t"), RAPIER))).isEmpty();
    }

    @Test
    void sneakAttackRequiresAFinesseOrRangedWeapon() {
        assertThat(new SneakAttackFeature(2)
                        .onHit(ctx(combatant("r"), combatant("t"), GREATAXE, Advantage.ADVANTAGE, false)))
                .isEmpty();
        assertThat(new SneakAttackFeature(2)
                        .onHit(ctx(combatant("r"), combatant("t"), LONGBOW, Advantage.ADVANTAGE, false)))
                .hasSize(1);
    }

    // ---- Colossus Slayer -----------------------------------------------------------------------

    @Test
    void colossusSlayerAddsAnEightSidedDieToAWoundedTargetOncePerTurn() {
        ColossusSlayerFeature c = new ColossusSlayerFeature();
        Combatant self = combatant("ranger");
        Combatant wounded = combatant("t");
        wounded.takeDamage(5); // now missing HP
        var first = c.onHit(ctx(self, wounded, LONGBOW));
        assertThat(first).hasSize(1);
        assertThat(first.get(0).damage().count()).isEqualTo(1);
        assertThat(first.get(0).damage().sides()).isEqualTo(8);
        assertThat(c.onHit(ctx(self, wounded, LONGBOW))).isEmpty();
        c.onTurnStart(null);
        assertThat(c.onHit(ctx(self, wounded, LONGBOW))).hasSize(1);
    }

    @Test
    void colossusSlayerDoesNotTriggerAgainstAFullHpTarget() {
        assertThat(new ColossusSlayerFeature().onHit(ctx(combatant("ranger"), combatant("t"), LONGBOW))).isEmpty();
    }

    // ---- Divine Smite --------------------------------------------------------------------------

    static final AttackProfile SWORD =
            AttackProfile.builder("Longsword", AttackKind.MELEE, 6, Dice.of(1, 8, 3), DamageType.SLASHING).reachFt(5).build();

    private static Combatant paladin() {
        return combatant("pal", b -> b.spellcasting(
                new SpellcastingSpec(Ability.CHA, List.of(new SpellcastingSpec.Slot(1, 2)), List.of(), List.of(), false)));
    }

    @Test
    void divineSmiteSpendsASlotOncePerTurnOnAMeleeHitForTwoEightSidedDiceRadiant() {
        DivineSmiteFeature d = new DivineSmiteFeature();
        Combatant self = paladin();
        var extra = d.onHit(ctx(self, combatant("t"), SWORD));
        assertThat(extra).hasSize(1);
        assertThat(extra.get(0).type()).isEqualTo(DamageType.RADIANT);
        assertThat(extra.get(0).damage().count()).isEqualTo(2);
        assertThat(extra.get(0).damage().sides()).isEqualTo(8);
        assertThat(self.slotCount(1)).isEqualTo(1);
        assertThat(d.onHit(ctx(self, combatant("t"), SWORD))).isEmpty();
        assertThat(self.slotCount(1)).isEqualTo(1);
        d.onTurnStart(null);
        assertThat(d.onHit(ctx(self, combatant("t"), SWORD))).hasSize(1);
        assertThat(self.slotCount(1)).isZero();
    }

    @Test
    void divineSmiteScalesWithTheSlotLevelSpent() {
        Combatant self = combatant("pal", b -> b.spellcasting(
                new SpellcastingSpec(Ability.CHA, List.of(new SpellcastingSpec.Slot(3, 1)), List.of(), List.of(), false)));
        var extra = new DivineSmiteFeature().onHit(ctx(self, combatant("t"), SWORD));
        assertThat(extra.get(0).damage().count()).isEqualTo(4); // 2d8 + 1d8 per slot level above 1st
    }

    @Test
    void divineSmiteDoesNothingWithNoSlotsOrOnARangedAttack() {
        DivineSmiteFeature d = new DivineSmiteFeature();
        Combatant self = paladin();
        AttackProfile bow = AttackProfile.builder("bow", AttackKind.RANGED, 6, Dice.of(1, 8, 3), DamageType.SLASHING).rangeFt(100).build();
        assertThat(d.onHit(ctx(self, combatant("t"), bow))).isEmpty();
        self.spendSlot(1);
        self.spendSlot(1);
        assertThat(d.onHit(ctx(self, combatant("t"), SWORD))).isEmpty();
    }

    // ---- Monk ----------------------------------------------------------------------------------

    static final AttackProfile FIST =
            AttackProfile.builder("Unarmed Strike", AttackKind.MELEE, 5, Dice.of(1, 6, 3), DamageType.BLUDGEONING).reachFt(5).build();

    private static Combatant monk() {
        return combatant("monk", b -> b.resources(List.of(new ResourceSpec("focus", 5, Recharge.ALL, null))));
    }

    @Test
    void martialArtsGrantsOneBonusAttackActionByDefault() {
        assertThat(new MartialArtsFeature().bonusAttackActions(null)).isEqualTo(1);
    }

    @Test
    void flurryOfBlowsSpendsFocusForASecondStrikeKeepingOneInReserve() {
        MartialArtsFeature m = new MartialArtsFeature();
        Combatant self = monk(); // 5 Focus
        m.onTurnStart(self);
        assertThat(m.bonusAttackActions(self)).isEqualTo(2);
        assertThat(self.resourceCount("focus")).isEqualTo(4);

        self.spendResource("focus", 3); // drain to a single Focus: no Flurry, keep it for Stunning Strike
        m.onTurnStart(self);
        assertThat(m.bonusAttackActions(self)).isEqualTo(1);
        assertThat(self.resourceCount("focus")).isEqualTo(1);
    }

    @Test
    void stunningStrikeSpendsFocusForAConSaveVsStunnedOncePerTurn() {
        StunningStrikeFeature s = new StunningStrikeFeature();
        // Level 3 here (PB 2), Wis 10 => +0: DC 8 + 2 + 0.
        Combatant self = monk();
        var effect = s.onHitEffect(ctx(self, combatant("t"), FIST));
        assertThat(effect).isPresent();
        assertThat(effect.get().save()).isEqualTo(Ability.CON);
        assertThat(effect.get().condition()).isEqualTo(Condition.STUNNED);
        assertThat(effect.get().dc()).isEqualTo(8 + 2 + 0);
        assertThat(effect.get().rounds()).isEqualTo(1);
        assertThat(self.resourceCount("focus")).isEqualTo(4);
        assertThat(s.onHitEffect(ctx(self, combatant("t"), FIST))).isEmpty();
        s.onTurnStart(null);
        assertThat(s.onHitEffect(ctx(self, combatant("t"), FIST))).isPresent();
    }

    @Test
    void stunningStrikeDcUsesTheMonksWisdomAndProficiency() {
        Combatant self = new Combatant(CombatantSpec.builder("monk", "monk", Side.PARTY, 5, AbilityScores.of(10, 16, 14, 10, 14, 10), 15, 30)
                .resources(List.of(new ResourceSpec("focus", 5, Recharge.ALL, null))).build());
        var effect = new StunningStrikeFeature().onHitEffect(ctx(self, combatant("t"), FIST));
        assertThat(effect.get().dc()).isEqualTo(8 + 3 + 2); // PB 3 (L5) + Wis +2
    }

    @Test
    void stunningStrikeNeedsFocusAndAMeleeHit() {
        StunningStrikeFeature s = new StunningStrikeFeature();
        Combatant self = monk();
        AttackProfile bow = AttackProfile.builder("bow", AttackKind.RANGED, 5, Dice.of(1, 6, 3), DamageType.BLUDGEONING).rangeFt(100).build();
        assertThat(s.onHitEffect(ctx(self, combatant("t"), bow))).isEmpty();
        StunningStrikeFeature fresh = new StunningStrikeFeature();
        for (int i = 0; i < 5; i++) {
            fresh.onTurnStart(null);
            fresh.onHitEffect(ctx(self, combatant("t"), FIST));
        }
        fresh.onTurnStart(null);
        assertThat(self.resourceCount("focus")).isZero();
        assertThat(fresh.onHitEffect(ctx(self, combatant("t"), FIST))).isEmpty();
    }

    // ---- features in the engine ----------------------------------------------------------------

    private static TurnPolicy alwaysAttack(AttackProfile weapon) {
        return api -> {
            var enemies = api.enemies();
            if (enemies.isEmpty()) {
                return;
            }
            var dmg = api.attack(enemies.get(0), weapon);
            while (dmg.isPresent() && api.resources().attacksRemaining() > 0) {
                dmg = api.attack(enemies.get(0), weapon);
            }
        };
    }

    private static Encounter fight(List<Combatant> cs, long seed, java.util.function.Function<Combatant, TurnPolicy> policy, EventLog log) {
        return Encounter.builder(Grid.open(10, 10), cs, new LabeledRandom(seed)).policyFor(policy).sink(log).build();
    }

    @Test
    void aRagingBarbarianResistsASlashingAttacker() {
        Combatant barb = combatant("barb", b -> b.maxHp(40).resources(List.of(ResourceSpec.longRest("rage", 1)))
                .features(List.<FeatureFactory>of(() -> new RageFeature(2))).position(new Cell(0, 0)));
        AttackProfile strong = AttackProfile.builder("Greataxe", AttackKind.MELEE, 20, Dice.of(1, 12, 3), DamageType.SLASHING).reachFt(5).build();
        Combatant foe = combatant("foe", b -> b.side(Side.ENEMY).ac(10).maxHp(60).attacks(List.of(strong)).position(new Cell(1, 0)));
        Encounter e = fight(List.of(barb, foe), 5, c -> c.id().equals("foe") ? alwaysAttack(strong) : TurnPolicy.IDLE, new EventLog());
        e.rollInitiative();
        e.runRound();
        e.runRound();
        assertThat(((RageFeature) barb.features().get(0)).isRaging()).isTrue();
        assertThat(barb.damageResponseFor(DamageType.SLASHING)).isEqualTo(DamageResponse.RESISTANT);
        assertThat(barb.hp()).isGreaterThan(0);
    }

    @Test
    void aLevelFiveExtraAttackFighterMakesTwoAttacksInOneAction() {
        Combatant fighter = combatant("fighter", b -> b.level(5).extraAttacks(1).attacks(List.of(GREATAXE)).position(new Cell(0, 0)));
        Combatant dummy = combatant("dummy", b -> b.side(Side.ENEMY).ac(1).maxHp(200).position(new Cell(1, 0)));
        EventLog log = new EventLog();
        Encounter e = fight(List.of(fighter, dummy), 9, c -> c.id().equals("fighter") ? alwaysAttack(GREATAXE) : TurnPolicy.IDLE, log);
        e.rollInitiative();
        e.runRound();
        assertThat(log.of(CombatEvent.Attack.class)).filteredOn(a -> a.attacker().equals("fighter")).hasSize(2);
    }

    @Test
    void aMonkMakesAnExtraBonusStrikeAndStunsOnAHit() {
        AttackProfile fist = AttackProfile.builder("Unarmed Strike", AttackKind.MELEE, 20, Dice.of(1, 6, 3), DamageType.BLUDGEONING).reachFt(5).build();
        Combatant monk = new Combatant(CombatantSpec.builder("monk", "monk", Side.PARTY, 5, AbilityScores.of(10, 16, 14, 10, 16, 10), 15, 30)
                .extraAttacks(1).attacks(List.of(fist))
                .resources(List.of(new ResourceSpec("focus", 5, Recharge.ALL, null)))
                .features(List.<FeatureFactory>of(MartialArtsFeature::new, StunningStrikeFeature::new))
                .position(new Cell(0, 0)).build());
        // A foe sure to fail the Con save, so Stunning Strike reliably lands.
        Combatant foe = combatant("foe", b -> b.side(Side.ENEMY).ac(1).maxHp(200)
                .saveBonuses(java.util.Map.of(Ability.CON, -50)).position(new Cell(1, 0)));
        TurnPolicy drain = api -> {
            if (!api.self().id().equals("monk")) {
                return;
            }
            var t = api.enemies();
            while (!t.isEmpty() && api.attack(t.get(0), fist).isPresent()) {
                // keep swinging
            }
        };
        EventLog log = new EventLog();
        Encounter e = fight(List.of(monk, foe), 3, c -> c.id().equals("monk") ? drain : TurnPolicy.IDLE, log);
        e.rollInitiative();
        e.runRound();
        e.runRound();
        assertThat(log.of(CombatEvent.Attack.class)).filteredOn(a -> a.attacker().equals("monk") && a.hit())
                .hasSizeGreaterThanOrEqualTo(3); // 2 Attack-action strikes + 1 Martial Arts bonus strike per turn
        assertThat(log.of(CombatEvent.ControlDenied.class)).anyMatch(d -> d.source().equals("monk"));
    }

    @Test
    void aPaladinUsesLayOnHandsToHealAHurtAllyThenActs() {
        Combatant paladin = combatant("pal", b -> b.level(5).attacks(List.of(SWORD))
                .resources(List.of(ResourceSpec.longRest("lay-on-hands", 25))).position(new Cell(0, 0)));
        Combatant ally = combatant("ally", b -> b.maxHp(50).position(new Cell(0, 1)));
        ally.takeDamage(45); // down to 5/50 (badly hurt)
        Combatant foe = combatant("foe", b -> b.side(Side.ENEMY).ac(12).maxHp(80).position(new Cell(1, 0)));
        EventLog log = new EventLog();
        Encounter e = fight(List.of(paladin, ally, foe), 4, c -> c.id().equals("pal") ? TacticalPolicy.DEFAULT : TurnPolicy.IDLE, log);
        e.rollInitiative();
        e.runRound();
        assertThat(log.of(CombatEvent.Heal.class)).filteredOn(h -> h.source().equals("pal")).hasSize(1);
        assertThat(ally.hp()).isGreaterThan(5);
        assertThat(paladin.resourceCount("lay-on-hands")).isLessThan(25);
        assertThat(log.of(CombatEvent.Attack.class)).anyMatch(a -> a.attacker().equals("pal")); // the action was free
    }

    @Test
    void aRangerMarksATargetAndHitsCarryTheMark() {
        AttackProfile bow = AttackProfile.builder("Longbow", AttackKind.RANGED, 20, Dice.of(1, 8, 3), DamageType.PIERCING).rangeFt(150).build();
        Combatant ranger = combatant("ranger", b -> b.level(5).attacks(List.of(bow))
                .features(List.<FeatureFactory>of(HuntersMarkFeature::new))
                .resources(List.of(ResourceSpec.longRest("hunters-mark", 3))).position(new Cell(0, 0)));
        Combatant foe = combatant("foe", b -> b.side(Side.ENEMY).ac(1).maxHp(300).position(new Cell(2, 0)));
        TurnPolicy policy = api -> {
            if (api.self().id().equals("ranger")) {
                api.markTarget(foe);
                api.attack(foe, bow);
            }
        };
        EventLog log = new EventLog();
        Encounter e = fight(List.of(ranger, foe), 3, c -> c.id().equals("ranger") ? policy : TurnPolicy.IDLE, log);
        e.rollInitiative();
        e.runRound();
        assertThat(ranger.markedTarget()).isEqualTo("foe");
        assertThat(ranger.concentratingOn()).isEqualTo("hunters-mark");
        assertThat(ranger.resourceCount("hunters-mark")).isEqualTo(2);
        assertThat(log.of(CombatEvent.Marked.class)).anyMatch(m -> m.source().equals("ranger"));
    }

    @Test
    void huntersMarkAddsOneSixSidedForceDieOnlyToTheMarkedTarget() {
        HuntersMarkFeature f = new HuntersMarkFeature();
        Combatant ranger = combatant("ranger");
        ranger.setMarkedTarget("m");
        var extra = f.onHit(ctx(ranger, combatant("m"), LONGBOW));
        assertThat(extra).hasSize(1);
        assertThat(extra.get(0).type()).isEqualTo(DamageType.FORCE);
        assertThat(extra.get(0).damage().sides()).isEqualTo(6);
        assertThat(f.onHit(ctx(ranger, combatant("o"), LONGBOW))).isEmpty();
    }

    @Test
    void wildShapeAssumesABeastFormAndReformsWhenSpent() {
        AttackProfile bite = AttackProfile.builder("Bite", AttackKind.MELEE, 5, Dice.of(2, 6, 2), DamageType.PIERCING).reachFt(5).build();
        WildShapeFeature w = new WildShapeFeature(new BeastForm(10, 13, bite));
        Combatant druid = combatant("druid", b -> b.ac(11).maxHp(40)
                .resources(List.of(new ResourceSpec("wild-shape", 2, Recharge.ALL, null))));
        w.onTurnStart(druid); // forms
        assertThat(druid.tempHp()).isEqualTo(10);
        assertThat(druid.effectiveAc()).isEqualTo(13);
        assertThat(druid.activeAttacks().get(0).name()).isEqualTo("Bite");
        assertThat(druid.resourceCount("wild-shape")).isEqualTo(1);
        w.onTurnStart(druid); // still buffered: no re-form, no use spent
        assertThat(druid.resourceCount("wild-shape")).isEqualTo(1);
        druid.takeDamage(10); // buffer chewed through: the form ends
        assertThat(druid.tempHp()).isZero();
        assertThat(druid.effectiveAc()).isEqualTo(11);
        assertThat(druid.activeAttacks()).isEmpty();
        w.onTurnStart(druid);
        assertThat(druid.tempHp()).isEqualTo(10);
        assertThat(druid.effectiveAc()).isEqualTo(13);
        assertThat(druid.resourceCount("wild-shape")).isZero();
        druid.takeDamage(10); // out of charges: no more forms
        w.onTurnStart(druid);
        assertThat(druid.tempHp()).isZero();
    }

    @Test
    void darkOnesBlessingGrantsTempHpEqualToChaPlusLevelWhenTheWarlockDropsAnEnemy() {
        Combatant warlock = combatant("lock", b -> b.level(5).features(List.of(FeatureFactory.shared(new DarkOnesBlessingFeature()))));
        // Cha 10 => +0, so max(1, 0 + 5) = 5.
        warlock.features().get(0).onKill(warlock, combatant("v"));
        assertThat(warlock.tempHp()).isEqualTo(5);
    }

    @Test
    void auraOfProtectionIsAMarkerFeature() {
        assertThat(new AuraOfProtectionFeature().id()).isEqualTo("aura-of-protection");
        assertThat(new AuraOfProtectionFeature().id()).isEqualTo(AuraOfProtectionFeature.ID);
    }

    @Test
    void activeFormRecordIsExposedForInspection() {
        AttackProfile bite = AttackProfile.builder("Bite", AttackKind.MELEE, 5, Dice.of(2, 6, 2), DamageType.PIERCING).build();
        Combatant druid = combatant("druid");
        druid.enterForm(new ActiveForm(14, bite));
        assertThat(druid.activeForm().ac()).isEqualTo(14);
        druid.clearForm();
        assertThat(druid.activeForm()).isNull();
    }

}
