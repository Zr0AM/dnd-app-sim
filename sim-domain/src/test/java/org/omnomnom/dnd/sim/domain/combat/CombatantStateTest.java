package org.omnomnom.dnd.sim.domain.combat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.omnomnom.dnd.sim.domain.combat.TestCombatants.make;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.domain.combat.spell.BuffSpec;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.Condition;
import org.omnomnom.dnd.sim.domain.core.DamageResponse;
import org.omnomnom.dnd.sim.domain.core.DamageType;
import org.omnomnom.dnd.sim.domain.dice.Dice;

/**
 * Direct tests for {@link Combatant} behavior that upstream only exercises through encounter-level specs (control,
 * buff, legendary, martial-features): timed conditions, buffs, resources, Wild Shape, feature ownership.
 */
class CombatantStateTest {

    // ---- timed conditions ----------------------------------------------------------------------

    @Test
    void timedConditionAppliesTheFlagAndExpiresAfterItsDuration() {
        Combatant c = make();
        c.applyTimedCondition(new TimedConditionSpec(Condition.PARALYZED, "wiz", 2, null, null));
        assertThat(c.hasCondition(Condition.PARALYZED)).isTrue();
        assertThat(c.controlSources()).containsExactly("wiz");

        assertThat(c.tickTimedConditions(ScriptedRng.faces(10))).isEmpty();
        assertThat(c.hasCondition(Condition.PARALYZED)).isTrue();
        assertThat(c.tickTimedConditions(ScriptedRng.faces(10))).containsExactly(Condition.PARALYZED);
        assertThat(c.hasCondition(Condition.PARALYZED)).isFalse();
        assertThat(c.controlSources()).isEmpty();
    }

    @Test
    void aSuccessfulRepeatSaveEndsTheEffectEarly() {
        Combatant c = make(); // Wis 12 => +1, not proficient
        c.applyTimedCondition(new TimedConditionSpec(
                Condition.PARALYZED, "wiz", 10, new RepeatSave(Ability.WIS, 14, true), null));
        assertThat(c.tickTimedConditions(ScriptedRng.faces(5))).isEmpty(); // 5 + 1 = 6 < 14
        assertThat(c.hasCondition(Condition.PARALYZED)).isTrue();
        assertThat(c.tickTimedConditions(ScriptedRng.faces(13))).containsExactly(Condition.PARALYZED); // 14 >= 14
        assertThat(c.hasCondition(Condition.PARALYZED)).isFalse();
    }

    @Test
    void breakingConcentrationEndsTheCastersConditionsOnly() {
        Combatant c = make();
        c.applyTimedCondition(new TimedConditionSpec(Condition.STUNNED, "a", 10, null, "a"));
        c.applyTimedCondition(new TimedConditionSpec(Condition.PRONE, "b", 10, null, "b"));
        c.endConcentrationConditions("a");
        assertThat(c.hasCondition(Condition.STUNNED)).isFalse();
        assertThat(c.hasCondition(Condition.PRONE)).isTrue();
    }

    // ---- buffs ---------------------------------------------------------------------------------

    private static BuffSpec bless(String source, int rounds) {
        return new BuffSpec("bless", source, rounds, Dice.of(1, 4), Dice.of(1, 4), 0, false, source);
    }

    @Test
    void reapplyingTheSameBuffRefreshesInsteadOfStacking() {
        Combatant c = make();
        c.applyBuff(bless("cleric", 2));
        c.tickBuffs();
        c.applyBuff(bless("cleric", 10)); // refresh: back to 10 rounds, still a single buff
        assertThat(c.buffAttackBonuses()).hasSize(1);
        assertThat(c.buffSaveBonuses()).hasSize(1);
        assertThat(c.tickBuffs()).isEmpty();
        assertThat(c.hasBuff("bless")).isTrue();
    }

    @Test
    void buffsAggregateAcAndExpire() {
        Combatant c = make();
        c.applyBuff(new BuffSpec("haste", "bard", 1, null, null, 2, true, null));
        assertThat(c.effectiveAc()).isEqualTo(16 + 2);
        assertThat(c.hasExtraAttackAction()).isTrue();
        assertThat(c.buffSourceFor("haste")).isEqualTo("bard");
        assertThat(c.buffSources()).containsExactly("bard");
        assertThat(c.tickBuffs()).containsExactly("haste");
        assertThat(c.effectiveAc()).isEqualTo(16);
        assertThat(c.hasExtraAttackAction()).isFalse();
        assertThat(c.buffSourceFor("haste")).isNull();
    }

    @Test
    void breakingConcentrationEndsTheCastersBuffs() {
        Combatant c = make();
        c.applyBuff(bless("cleric", 10));
        c.endConcentrationBuffs("someone-else");
        assertThat(c.hasBuff("bless")).isTrue();
        c.endConcentrationBuffs("cleric");
        assertThat(c.hasBuff("bless")).isFalse();
    }

    // ---- resources and legendary actions -------------------------------------------------------

    @Test
    void resourcesRechargeByRestType() {
        Combatant c = make(b -> b.resources(List.of(
                ResourceSpec.longRest("rage", 3),
                new ResourceSpec("focus", 4, Recharge.FULL, Recharge.FULL),
                new ResourceSpec("inspiration", 3, Recharge.of(1), Recharge.FULL))));
        c.spendResource("rage", 2);
        c.spendResource("focus", 4);
        c.spendResource("inspiration", 3);
        assertThat(c.spendResource("rage", 5)).isFalse();

        c.shortRest();
        assertThat(c.resourceCount("rage")).isEqualTo(1); // long-rest only
        assertThat(c.resourceCount("focus")).isEqualTo(4);
        assertThat(c.resourceCount("inspiration")).isEqualTo(1);

        c.longRest();
        assertThat(c.resourceCount("rage")).isEqualTo(3);
        assertThat(c.resourceCount("inspiration")).isEqualTo(3);
        assertThat(c.resourceCount("undefined-pool")).isZero();
    }

    @Test
    void legendaryActionsSpendAndRefresh() {
        Combatant boss = make(b -> b.legendaryActions(3));
        assertThat(boss.legendaryRemaining()).isEqualTo(3);
        assertThat(boss.spendLegendary()).isTrue();
        boss.spendLegendary();
        boss.spendLegendary();
        assertThat(boss.spendLegendary()).isFalse();
        boss.refreshLegendary();
        assertThat(boss.legendaryRemaining()).isEqualTo(3);
    }

    // ---- Wild Shape ----------------------------------------------------------------------------

    @Test
    void wildShapeFormOverridesAcAndAttacksUntilItsTempHpIsGone() {
        AttackProfile bite = AttackProfile.builder("Bite", AttackKind.MELEE, 5, Dice.of(2, 6, 2), DamageType.PIERCING).build();
        Combatant druid = make();
        druid.enterForm(new ActiveForm(13, bite));
        druid.grantTempHp(8);
        assertThat(druid.effectiveAc()).isEqualTo(13);
        assertThat(druid.activeAttacks()).containsExactly(bite);

        druid.takeDamage(8); // temp HP used up
        assertThat(druid.activeForm()).isNull();
        assertThat(druid.effectiveAc()).isEqualTo(16);
        assertThat(druid.hp()).isEqualTo(40);
    }

    // ---- features ------------------------------------------------------------------------------

    /** A stateful feature, like Sneak Attack's once-per-turn flag. */
    private static final class Counter implements Feature {
        int uses;

        @Override
        public String id() {
            return "counter";
        }

        @Override
        public void onTurnStart(Combatant self) {
            uses++;
        }
    }

    @Test
    void eachCombatantBuildsItsOwnFeatureInstances() {
        CombatantSpec spec = TestCombatants.base().features(List.of(Counter::new)).build();
        Combatant a = new Combatant(spec);
        Combatant b = new Combatant(spec);
        assertThat(a.features().get(0)).isNotSameAs(b.features().get(0));

        a.features().get(0).onTurnStart(a);
        assertThat(((Counter) a.features().get(0)).uses).isEqualTo(1);
        assertThat(((Counter) b.features().get(0)).uses).isZero();
    }

    @Test
    void sharedFactoryHandsEveryCombatantTheSameInstance() {
        Feature stateless = () -> "stateless";
        CombatantSpec spec = TestCombatants.base().features(List.of(FeatureFactory.shared(stateless))).build();
        assertThat(new Combatant(spec).features().get(0)).isSameAs(new Combatant(spec).features().get(0));
    }

    @Test
    void featureHooksDefaultToNoOps() {
        Feature f = () -> "noop";
        Combatant c = make();
        assertThat(f.outgoingAttack(c, c, null)).isSameAs(OutgoingAttackMods.NONE);
        assertThat(f.onHit(null)).isEmpty();
        assertThat(f.onHitEffect(null)).isEmpty();
        assertThat(f.bonusAttackActions(c)).isZero();
        assertThat(f.grantsAttackersAdvantage(c)).isFalse();
        assertThat(f.resistsDamage(c, DamageType.FIRE)).isFalse();
    }

    @Test
    void featureResistanceUpgradesNormalButNeverOverridesStaticDefenses() {
        Feature rage = new Feature() {
            @Override
            public String id() {
                return "rage";
            }

            @Override
            public boolean resistsDamage(Combatant self, DamageType type) {
                return type == DamageType.SLASHING || type == DamageType.FIRE;
            }
        };
        Combatant c = make(b -> b.features(List.of(FeatureFactory.shared(rage)))
                .damageResponses(java.util.Map.of(DamageType.FIRE, DamageResponse.VULNERABLE)));
        assertThat(c.damageResponseFor(DamageType.SLASHING)).isEqualTo(DamageResponse.RESISTANT);
        assertThat(c.damageResponseFor(DamageType.FIRE)).isEqualTo(DamageResponse.VULNERABLE);
        assertThat(c.damageResponseFor(DamageType.COLD)).isEqualTo(DamageResponse.NORMAL);
    }
}
