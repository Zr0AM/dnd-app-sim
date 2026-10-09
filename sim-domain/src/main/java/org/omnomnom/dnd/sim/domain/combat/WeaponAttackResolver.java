package org.omnomnom.dnd.sim.domain.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.omnomnom.dnd.sim.domain.combat.AttackResolver.AttackParams;
import org.omnomnom.dnd.sim.domain.combat.AttackResolver.AttackResult;
import org.omnomnom.dnd.sim.domain.combat.AttackResolver.SaveResult;
import org.omnomnom.dnd.sim.domain.combat.event.CombatEvent;
import org.omnomnom.dnd.sim.domain.core.DamageType;
import org.omnomnom.dnd.sim.domain.dice.Advantage;
import org.omnomnom.dnd.sim.domain.dice.Dice;
import org.omnomnom.dnd.sim.domain.rng.Rng;

/**
 * Resolves one weapon attack: range or reach, condition- and feature-derived advantage, the roll, auto-crit against
 * inert targets, damage (dice doubled on a crit), mitigation, application, and save-or-condition riders on a hit.
 */
final class WeaponAttackResolver {

    /** Where a weapon attack came from; only {@code ACTION} attacks are logged as {@code Attack} events. */
    enum Source {
        ACTION,
        OPPORTUNITY
    }

    /** What the attacker's and target's features add to an attack: net advantage and a flat to-hit bonus. */
    private record FeatureMods(Advantage advantage, int toHit) {}

    private final FightContext ctx;
    private final Rolls rolls;
    private final DamageApplier damage;

    WeaponAttackResolver(FightContext ctx, Rolls rolls, DamageApplier damage) {
        this.ctx = ctx;
        this.rolls = rolls;
        this.damage = damage;
    }

    /** Damage dealt (0 on a miss), or empty if the target is out of range. */
    OptionalInt resolve(Combatant self, Combatant target, AttackProfile profile, Source source) {
        int dist = ctx.distanceFt(self, target);
        Optional<Advantage> rangePenalty = rangePenalty(profile, dist);
        if (rangePenalty.isEmpty()) {
            return OptionalInt.empty();
        }

        boolean within5 = dist <= 5;
        Advantage condAdv = Conditions.attackAdvantage(self, target, within5);
        FeatureMods mods = featureMods(self, target, profile);
        Advantage adv = combineAdvantage(combineAdvantage(condAdv, rangePenalty.get()), mods.advantage());

        int buffToHit = rolls.buffAttackBonus(self, profile.name() + ":" + target.id());
        var stream = ctx.rng().stream(self.id() + ":" + profile.name() + ":" + target.id());
        AttackResult result = AttackResolver.resolveAttack(stream,
                new AttackParams(profile.attackBonus() + mods.toHit() + buffToHit, target.effectiveAc(), adv,
                        profile.critRangeOrDefault()));

        if (!result.hit()) {
            if (source == Source.ACTION) {
                ctx.log(new CombatEvent.Attack(self.id(), target.id(), profile.name(), result.d20(), false, false, 0));
            }
            return OptionalInt.of(0);
        }

        boolean crit = result.crit() || Conditions.isAutoCritTarget(target, within5);
        int dealt = rollDamage(self, target, profile, crit, adv);

        boolean wasConscious = target.isConscious();
        DamageOutcome outcome = target.takeDamage(dealt, crit);
        if (source == Source.ACTION) {
            ctx.log(new CombatEvent.Attack(self.id(), target.id(), profile.name(), result.d20(), true, crit, dealt));
        }
        damage.settle(self, target, wasConscious, outcome, dealt);

        applyOnHitEffects(self, target, profile, crit, adv);
        return OptionalInt.of(dealt);
    }

    /** The advantage penalty for the distance (disadvantage beyond normal range), or empty if out of reach or range. */
    private static Optional<Advantage> rangePenalty(AttackProfile profile, int dist) {
        if (profile.kind() == AttackKind.MELEE) {
            return dist > profile.reachFtOrDefault() || dist == 0 ? Optional.empty() : Optional.of(Advantage.NORMAL);
        }
        int normal = profile.rangeFt() != null ? profile.rangeFt() : 0;
        int max = profile.rangeLongFt() != null ? profile.rangeLongFt() : normal;
        if (dist > max) {
            return Optional.empty();
        }
        return Optional.of(dist > normal ? Advantage.DISADVANTAGE : Advantage.NORMAL);
    }

    /**
     * Feature-driven modifiers: the attacker's own features (Reckless Attack), the target's features that expose it
     * (Reckless grants attackers advantage), and any flat to-hit bonus.
     */
    private static FeatureMods featureMods(Combatant self, Combatant target, AttackProfile profile) {
        boolean hasAdvantage = false;
        boolean hasDisadvantage = false;
        int toHit = 0;
        for (Feature f : self.features()) {
            OutgoingAttackMods mods = f.outgoingAttack(self, target, profile);
            hasAdvantage |= mods.advantage();
            hasDisadvantage |= mods.disadvantage();
            toHit += mods.toHit();
        }
        hasAdvantage |= target.features().stream().anyMatch(f -> f.grantsAttackersAdvantage(target));
        Advantage advantage = combineAdvantage(
                hasAdvantage ? Advantage.ADVANTAGE : Advantage.NORMAL, hasDisadvantage ? Advantage.DISADVANTAGE : Advantage.NORMAL);
        return new FeatureMods(advantage, toHit);
    }

    /**
     * Primary damage, then each extra rider (the profile's, then those the attacker's features add on a hit), each
     * mitigated by its own type. A crit doubles the dice of every component but never the flat bonuses.
     */
    private int rollDamage(Combatant self, Combatant target, AttackProfile profile, boolean crit, Advantage adv) {
        var dmgStream = ctx.rng().stream(self.id() + ":" + profile.name() + ":" + target.id() + ":dmg");
        List<ExtraDamage> components = new ArrayList<>(profile.extraDamage());
        OnHitContext onHit = onHitContext(self, target, profile, crit, adv);
        for (Feature f : self.features()) {
            components.addAll(f.onHit(onHit));
        }

        int dealt = rollMitigated(profile.damage(), profile.damageType(), crit, dmgStream, target);
        for (ExtraDamage extra : components) {
            dealt += rollMitigated(extra.damage(), extra.type(), crit, dmgStream, target);
        }
        return dealt;
    }

    private static int rollMitigated(Dice dice, DamageType type, boolean crit, Rng stream, Combatant target) {
        int raw = dice.roll(stream);
        if (crit) {
            raw += dice.withoutBonus().roll(stream);
        }
        return DamageMitigation.applyResponse(raw, target.damageResponseFor(type));
    }

    /**
     * Save-or-condition riders on a hit (Monk Stunning Strike): each feature that triggers makes the target save; on a
     * failure the condition is applied and attributed to the attacker (feeding the control metric).
     */
    private void applyOnHitEffects(Combatant self, Combatant target, AttackProfile profile, boolean crit, Advantage adv) {
        if (!target.isConscious()) {
            return;
        }
        for (Feature f : self.features()) {
            f.onHitEffect(onHitContext(self, target, profile, crit, adv)).ifPresent(effect -> {
                SaveResult save = rolls.save(
                        self.id() + ":" + f.id() + ":" + target.id() + Rolls.SAVE_SUFFIX, target, effect.save(), f.id(), effect.dc());
                if (!save.success()) {
                    target.applyTimedCondition(new TimedConditionSpec(effect.condition(), self.id(), effect.rounds(), null, null));
                }
            });
        }
    }

    private OnHitContext onHitContext(Combatant self, Combatant target, AttackProfile profile, boolean crit, Advantage adv) {
        return new OnHitContext(self, target, profile, crit, adv, ctx.roster().hasAdjacentAlly(self, target));
    }

    /** Combine two advantage sources under the no-stacking rule. */
    static Advantage combineAdvantage(Advantage a, Advantage b) {
        boolean adv = a == Advantage.ADVANTAGE || b == Advantage.ADVANTAGE;
        boolean dis = a == Advantage.DISADVANTAGE || b == Advantage.DISADVANTAGE;
        if (adv == dis) {
            return Advantage.NORMAL;
        }
        return adv ? Advantage.ADVANTAGE : Advantage.DISADVANTAGE;
    }
}
