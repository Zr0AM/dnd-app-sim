package org.omnomnom.dnd.sim.domain.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.ToDoubleFunction;
import java.util.stream.Stream;
import org.omnomnom.dnd.sim.domain.combat.AttackKind;
import org.omnomnom.dnd.sim.domain.combat.AttackProfile;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.combat.ExtraDamage;
import org.omnomnom.dnd.sim.domain.combat.ResourceIds;
import org.omnomnom.dnd.sim.domain.combat.TurnApi;
import org.omnomnom.dnd.sim.domain.combat.TurnPolicy;
import org.omnomnom.dnd.sim.domain.combat.spell.Spell;
import org.omnomnom.dnd.sim.domain.combat.spell.SpellKind;
import org.omnomnom.dnd.sim.domain.core.Picks;
import org.omnomnom.dnd.sim.domain.grid.Cell;
import org.omnomnom.dnd.sim.domain.grid.GridMath;

/**
 * The shared tactical AI: one decision-maker every build under test uses, so comparisons are fair (a bad per-build
 * AI would make a good build look bad). It is a utility-based action chooser: score candidate targets, pick the
 * best, move into range with the build's best weapon or spell, and use every attack the turn allows. Healing,
 * buffing, Lay on Hands, Hunter's Mark and Quickened cantrips are handled by their own steps.
 *
 * <p>A policy is a pure function of the {@link TurnApi}, so one instance can safely drive any number of concurrent
 * fights. This is a faithful port of {@code sim/src/ai/policy.ts}; ties resolve to the first candidate, exactly as
 * JavaScript's {@code reduce} and stable {@code sort} do there.
 */
public final class TacticalPolicy {

    // Rough constants the AI uses to estimate action value without a target's exact AC.
    private static final double ASSUMED_HIT = 0.6;
    private static final double ASSUMED_SAVE_FAIL = 0.5;
    /** Expected-damage penalty per slot level, so cantrips and weapons win when close. */
    private static final double SLOT_PENALTY = 1.5;
    /** Rounds a control effect is assumed to keep a target locked, for valuation. */
    private static final double ROUNDS_DENIED = 2;
    /** An ally below this fraction of its HP is worth a heal. */
    private static final double BADLY_HURT_FRACTION = 0.4;

    /** The default shared policy. */
    public static final TurnPolicy DEFAULT = create(TacticsWeights.DEFAULT);

    private TacticalPolicy() {}

    /** Build the shared tactical policy with the given weights. */
    public static TurnPolicy create(TacticsWeights weights) {
        return api -> act(api, weights);
    }

    // ---- weapons and threat --------------------------------------------------------------------

    /** Average damage of one hit with a weapon, counting its extra-damage riders. */
    public static double weaponAverageDamage(AttackProfile weapon) {
        double avg = weapon.damage().mean();
        for (ExtraDamage e : weapon.extraDamage()) {
            avg += e.damage().mean();
        }
        return avg;
    }

    /** The build's best weapon by average damage (its primary), honoring a Wild Shape form; null if it has none. */
    public static AttackProfile primaryWeapon(Combatant c) {
        return Picks.firstMax(c.activeAttacks(), TacticalPolicy::weaponAverageDamage).orElse(null);
    }

    /** A crude estimate of a creature's damage output per turn, to gauge threat. */
    public static double threatOf(Combatant c) {
        AttackProfile w = primaryWeapon(c);
        if (w == null) {
            return 0;
        }
        return weaponAverageDamage(w) * (1 + c.extraAttacks());
    }

    /** The attacker's expected damage this turn with its primary weapon. */
    private static double expectedTurnDamage(Combatant self, TacticsWeights weights) {
        AttackProfile w = primaryWeapon(self);
        if (w == null) {
            return 0;
        }
        return weaponAverageDamage(w) * (1 + self.extraAttacks()) * weights.assumedHitChance();
    }

    /** Score a target for selection; higher is more attractive. */
    public static double scoreTarget(Combatant self, Combatant target, TacticsWeights weights) {
        double hpFrac = (double) target.hp() / target.maxHp();
        double score = weights.woundedPreference() * (1 - hpFrac);
        score += weights.threatPreference() * normalizeThreat(threatOf(target));
        score -= weights.distancePenalty() * (GridMath.distanceFt(self.position(), target.position()) / 5.0);
        if (expectedTurnDamage(self, weights) >= target.hp()) {
            score += weights.finishBonus();
        }
        return score;
    }

    /** Map a raw threat number into roughly [0, 3] so it is comparable to the other terms. */
    private static double normalizeThreat(double threat) {
        return Math.min(3, threat / 10);
    }

    /** The reach/range of a weapon in feet for positioning. */
    private static int weaponRangeFt(AttackProfile weapon) {
        if (weapon.kind() == AttackKind.MELEE) {
            return weapon.reachFtOrDefault();
        }
        return weapon.rangeFt() != null ? weapon.rangeFt() : 5;
    }

    // ---- movement ------------------------------------------------------------------------------

    /** Move {@code steps} cells from {@code from} toward {@code target} along the grid line. */
    private static Cell stepTowardBy(Cell from, Cell target, int steps) {
        int x = from.x();
        int y = from.y();
        for (int i = 0; i < steps; i++) {
            x += Integer.signum(target.x() - x);
            y += Integer.signum(target.y() - y);
        }
        return new Cell(x, y);
    }

    /** Move toward {@code target} until within {@code rangeFt}, as far as this turn allows. */
    private static void approach(TurnApi api, Combatant target, int rangeFt) {
        int distCells = GridMath.stepDistance(api.self().position(), target.position());
        int rangeCells = Math.max(1, rangeFt / 5);
        int needed = Math.max(0, distCells - rangeCells);
        if (needed <= 0) {
            return;
        }
        int canMove = api.resources().movementFt() / 5;
        int steps = Math.min(needed, canMove);
        if (steps > 0) {
            api.moveTo(stepTowardBy(api.self().position(), target.position(), steps));
        }
    }

    // ---- spell valuation -----------------------------------------------------------------------

    /** A spell the AI has chosen to cast, with the slot and estimated value. */
    private record SpellChoice(Spell spell, int slotLevel, double ev, int rangeFt, Combatant target) {}

    /**
     * Expected useful damage of casting {@code spell} at {@code slotLevel}, capped at each target's remaining HP so
     * overkill does not make a big nuke look good against a weak single target (which keeps the AI from wasting
     * slots).
     */
    private static double spellExpectedDamage(
            Combatant self, Spell spell, int slotLevel, Combatant target, List<Combatant> enemies) {
        return switch (spell.kind()) {
            case SpellKind.AttackDamage ad -> attackDamageValue(self, spell, ad, slotLevel, target);
            case SpellKind.Control ctl -> controlSpellValue(ctl, target, enemies);
            case SpellKind.SaveDamage sd -> saveDamageValue(self, sd, slotLevel, target, enemies);
            case SpellKind.Heal heal -> 0; // healing and buffs are valued by their own steps
            case SpellKind.Buff buff -> 0;
        };
    }

    private static double attackDamageValue(Combatant self, Spell spell, SpellKind.AttackDamage ad, int slotLevel, Combatant target) {
        int rays = ad.beams() != null
                ? ad.beams().applyAsInt(self.level())
                : Spell.raysAt(ad, slotLevel, Math.max(1, spell.level()));
        int bonus = ad.addSpellMod() && self.spellAbility() != null ? self.abilityMod(self.spellAbility()) : 0;
        double dmg = rays * (ad.damage().at(slotLevel, self.level()).mean() + bonus) * ASSUMED_HIT;
        return Math.min(dmg, target.hp());
    }

    /**
     * Value control as damage prevented: a locked enemy denies ~its own output for the rounds it stays locked, weighted
     * by the chance it fails the save.
     */
    private static double controlSpellValue(SpellKind.Control ctl, Combatant target, List<Combatant> enemies) {
        if (ctl.aoeRadiusFt() == null) {
            return controlValue(target);
        }
        return sum(caughtOrTarget(enemies, target.position(), ctl.aoeRadiusFt(), target), TacticalPolicy::controlValue);
    }

    /** Save-damage: expected damage per target after the save, capped per target's HP. */
    private static double saveDamageValue(Combatant self, SpellKind.SaveDamage sd, int slotLevel, Combatant target, List<Combatant> enemies) {
        double halfOnSuccess = sd.onSuccess() == SpellKind.OnSuccess.HALF ? 0.5 : 0;
        double perTarget = sd.damage().at(slotLevel, self.level()).mean() * (ASSUMED_SAVE_FAIL + (1 - ASSUMED_SAVE_FAIL) * halfOnSuccess);
        if (sd.aoeRadiusFt() == null) {
            return Math.min(perTarget, target.hp());
        }
        Cell origin = sd.selfOrigin() ? self.position() : target.position();
        return sum(caughtOrTarget(enemies, origin, sd.aoeRadiusFt(), target), e -> Math.min(perTarget, e.hp()));
    }

    /** Left-to-right sum, so the floating-point result matches an accumulating loop exactly. */
    private static double sum(List<Combatant> combatants, ToDoubleFunction<Combatant> value) {
        return combatants.stream().mapToDouble(value).reduce(0, Double::sum);
    }

    private static double controlValue(Combatant t) {
        return threatOf(t) * ROUNDS_DENIED * ASSUMED_SAVE_FAIL;
    }

    /** The enemies within the radius of {@code origin}, or just {@code target} if there are none. */
    private static List<Combatant> caughtOrTarget(List<Combatant> enemies, Cell origin, int radiusFt, Combatant target) {
        List<Combatant> caught = enemies.stream().filter(e -> GridMath.distanceFt(origin, e.position()) <= radiusFt).toList();
        return caught.isEmpty() ? List.of(target) : caught;
    }

    /** The spells of one kind the combatant knows, in the order it knows them. */
    private static <K extends SpellKind> List<Spell> spellsOfKind(Combatant c, Class<K> kind) {
        return c.spells().stream().filter(s -> kind.isInstance(s.kind())).toList();
    }

    /**
     * The best offensive spell to cast, or null. Damage spells are valued against {@code damageTarget} (the
     * wounded/best kill target); control spells against {@code controlTarget} (the most dangerous enemy, who is who
     * you want to lock down). The first of equally valuable choices wins.
     */
    private static SpellChoice bestSpell(
            Combatant self, Combatant damageTarget, Combatant controlTarget, List<Combatant> enemies) {
        Stream<SpellChoice> cantrips = self.cantrips().stream()
                .map(cantrip -> choice(self, cantrip, 0, damageTarget, controlTarget, enemies));
        // Healing and buffs are handled by their own steps (tryHeal / tryBuff). Consider every affordable slot level,
        // so a damage spell upcasts into a higher slot when the extra dice (capped at the target's HP) beat the slot's cost.
        Stream<SpellChoice> leveled = self.spells().stream()
                .filter(spell -> !(spell.kind() instanceof SpellKind.Heal || spell.kind() instanceof SpellKind.Buff))
                .flatMap(spell -> self.availableSlotLevels().stream()
                        .filter(slot -> slot >= spell.level())
                        .map(slot -> choice(self, spell, slot, damageTarget, controlTarget, enemies)));
        return Picks.firstMax(Stream.concat(cantrips, leveled).toList(), SpellChoice::ev).orElse(null);
    }

    private static SpellChoice choice(
            Combatant self, Spell spell, int slotLevel, Combatant damageTarget, Combatant controlTarget, List<Combatant> enemies) {
        Combatant target = spell.kind() instanceof SpellKind.Control ? controlTarget : damageTarget;
        double ev = spellExpectedDamage(self, spell, slotLevel, target, enemies) - slotLevel * SLOT_PENALTY;
        return new SpellChoice(spell, slotLevel, ev, spell.rangeFt(), target);
    }

    // ---- healing, buffs and class riders -------------------------------------------------------

    /** An ally worth healing this turn: a downed ally first, else a badly wounded one (lowest HP fraction first). */
    private static Combatant pickHealTarget(TurnApi api) {
        List<Combatant> allies = api.allAllies();
        return allies.stream()
                .filter(Combatant::isDying)
                .findFirst()
                .orElseGet(() -> Picks.firstMin(
                        allies.stream().filter(a -> a.isConscious() && hpFraction(a) < BADLY_HURT_FRACTION).toList(),
                        TacticalPolicy::hpFraction).orElse(null));
    }

    private static double hpFraction(Combatant c) {
        return (double) c.hp() / c.maxHp();
    }

    private static Integer firstSlotAtLeast(Combatant c, int level) {
        return c.availableSlotLevels().stream().filter(l -> l >= level).findFirst().orElse(null);
    }

    /**
     * If the caster has a heal spell and an ally needs it, heal them. Prefers a bonus-action heal (Healing Word) for a
     * downed ally so the caster can still act; returns true if the whole turn's action was spent healing.
     */
    private static boolean tryHeal(TurnApi api) {
        List<Spell> healSpells = spellsOfKind(api.self(), SpellKind.Heal.class);
        if (healSpells.isEmpty()) {
            return false;
        }
        Combatant target = pickHealTarget(api);
        Integer slot = firstSlotAtLeast(api.self(), 1);
        if (target == null || slot == null) {
            return false;
        }

        Optional<Spell> bonusHeal = firstCastAt(healSpells, Spell.CastingTime.BONUS);
        Optional<Spell> actionHeal = firstCastAt(healSpells, Spell.CastingTime.ACTION);
        // Prefer a bonus-action heal to revive while keeping the action for offense.
        if (target.isDying() && bonusHeal.isPresent() && api.resources().bonus()) {
            api.castSpell(bonusHeal.get(), target, slot, false);
            return false; // action still free
        }
        Spell spell = actionHeal.or(() -> bonusHeal).orElseThrow();
        api.castSpell(spell, target, slot, false);
        return spell.action() == Spell.CastingTime.ACTION;
    }

    private static Optional<Spell> firstCastAt(List<Spell> spells, Spell.CastingTime time) {
        return spells.stream().filter(s -> s.action() == time).findFirst();
    }

    private record BuffValue(double value, List<Combatant> targets) {}

    /** A buff the caster has chosen to cast: the spell, who to aim it at, and its estimated value. */
    private record BuffPlan(Spell spell, Combatant target, double value) {}

    /**
     * Estimated value of casting {@code spell} (a buff) now, with the allies it would cover. Allies are ranked by
     * threat and capped at the spell's target count; the per-ally benefit credits an extra attack (Haste), a to-hit
     * rider (Bless, about +12% hit) and a small survivability bump for +AC.
     */
    private static BuffValue buffValue(Spell spell, List<Combatant> alliesInRange) {
        if (!(spell.kind() instanceof SpellKind.Buff kind)) {
            return new BuffValue(0, List.of());
        }
        List<Combatant> sorted = new ArrayList<>(alliesInRange);
        sorted.sort((a, b) -> Double.compare(threatOf(b), threatOf(a)));
        List<Combatant> ranked = sorted.size() > kind.maxTargets() ? sorted.subList(0, kind.maxTargets()) : sorted;
        double value = 0;
        for (Combatant a : ranked) {
            value = withBuffBenefit(value, kind, a);
        }
        return new BuffValue(value, ranked);
    }

    /** {@code value} plus what the buff is worth to one ally; the terms are added one by one, in order. */
    private static double withBuffBenefit(double value, SpellKind.Buff kind, Combatant ally) {
        double total = value;
        if (kind.extraAttackAction()) {
            total += 0.5 * threatOf(ally); // roughly one extra attack
        }
        if (kind.attackBonusDice() != null) {
            total += 0.12 * threatOf(ally); // +~2.5 to hit is about +12% of output
        }
        if (kind.acBonus() != 0) {
            total += 0.5 * kind.acBonus();
        }
        return total;
    }

    /**
     * If the caster has a buff spell, isn't already concentrating, and has allies worth buffing in range, cast the most
     * valuable buff. Buffs are concentration, so this fires once and then the buffer acts normally while the effect
     * holds. Returns true if the turn's action was spent casting.
     */
    private static boolean tryBuff(TurnApi api) {
        Combatant self = api.self();
        if (self.concentratingOn() != null || !api.resources().action()) {
            return false;
        }
        // Allies that actually attack are worth buffing.
        List<Combatant> combatants = api.allies().stream().filter(a -> threatOf(a) > 0).toList();
        List<Spell> affordable = spellsOfKind(self, SpellKind.Buff.class).stream()
                .filter(s -> firstSlotAtLeast(self, s.level()) != null)
                .toList();
        if (combatants.isEmpty() || affordable.isEmpty()) {
            return false;
        }

        Optional<BuffPlan> plan = bestBuffInRange(self, combatants, affordable).or(() -> approachForBuff(api, combatants, affordable));
        if (plan.isEmpty()) {
            return false;
        }
        Spell spell = plan.get().spell();
        var cast = api.castSpell(spell, plan.get().target(), firstSlotAtLeast(self, spell.level()), false);
        return cast.isPresent() && spell.action() == Spell.CastingTime.ACTION;
    }

    /** Rank affordable buffs by value against the allies currently in range; the first of equal values wins. */
    private static Optional<BuffPlan> bestBuffInRange(Combatant self, List<Combatant> combatants, List<Spell> affordable) {
        List<BuffPlan> plans = affordable.stream()
                .map(s -> planFor(s, buffValue(s, inRangeOf(self, combatants, s))))
                .flatMap(Optional::stream)
                .filter(p -> p.value() > 0)
                .toList();
        return Picks.firstMax(plans, BuffPlan::value);
    }

    /** Nobody in range: approach the strongest ally for the highest-level affordable buff, then retry. */
    private static Optional<BuffPlan> approachForBuff(TurnApi api, List<Combatant> combatants, List<Spell> affordable) {
        Combatant strongest = Picks.firstMax(combatants, TacticalPolicy::threatOf).orElseThrow();
        Spell spell = Picks.firstMax(affordable, Spell::level).orElseThrow();
        approach(api, strongest, spell.rangeFt());
        return planFor(spell, buffValue(spell, inRangeOf(api.self(), combatants, spell)));
    }

    /** The plan to cast {@code spell} at the allies' best-ranked member, or empty if it would cover nobody. */
    private static Optional<BuffPlan> planFor(Spell spell, BuffValue value) {
        return value.targets().isEmpty() ? Optional.empty() : Optional.of(new BuffPlan(spell, value.targets().get(0), value.value()));
    }

    private static List<Combatant> inRangeOf(Combatant self, List<Combatant> allies, Spell s) {
        return allies.stream().filter(a -> GridMath.distanceFt(self.position(), a.position()) <= s.rangeFt()).toList();
    }

    /**
     * Metamagic (Sorcerer): after the main action, spend Sorcery Points to cast a damage cantrip as a Bonus Action - a
     * second spell in the turn. Fires when the caster has a 'sorcery' pool, a free bonus action, a damage cantrip, and
     * an enemy in range of it.
     */
    private static void tryQuickenedCantrip(TurnApi api, Combatant damageTarget) {
        Combatant self = api.self();
        if (!api.resources().bonus() || self.resourceCount(ResourceIds.SORCERY) < ResourceIds.QUICKENED_SPELL_COST) {
            return;
        }
        self.cantrips().stream()
                .filter(c -> c.kind() instanceof SpellKind.AttackDamage || c.kind() instanceof SpellKind.SaveDamage)
                .findFirst()
                .filter(cantrip -> GridMath.distanceFt(self.position(), damageTarget.position()) <= cantrip.rangeFt())
                .ifPresent(cantrip -> api.castSpell(cantrip, damageTarget, 0, true));
    }

    /**
     * Lay on Hands (Paladin): a Bonus Action top-up for a downed or badly hurt ally, from the healing pool, costing no
     * spell slot. It uses only the bonus action, so the paladin still takes its action normally this turn.
     */
    private static void tryLayOnHands(TurnApi api) {
        if (api.self().resourceCount(ResourceIds.LAY_ON_HANDS) <= 0 || !api.resources().bonus()) {
            return;
        }
        Combatant target = pickHealTarget(api);
        if (target != null) {
            api.layOnHands(target);
        }
    }

    /**
     * Hunter's Mark (Ranger): place the mark on the kill target as a Bonus Action when the ranger has a free use and
     * isn't already concentrating, so its hits carry the extra damage. The free-use pool is the capability: content
     * grants it only alongside the feature that makes the mark deal damage.
     */
    private static void tryMark(TurnApi api, Combatant damageTarget) {
        Combatant self = api.self();
        if (self.concentratingOn() != null || !api.resources().bonus()) {
            return;
        }
        if (self.resourceCount(ResourceIds.HUNTERS_MARK) <= 0) {
            return;
        }
        api.markTarget(damageTarget);
    }

    // ---- the turn ------------------------------------------------------------------------------

    private static void act(TurnApi api, TacticsWeights weights) {
        // Lay on Hands first: a free bonus-action top-up that keeps the action open. Healing then takes priority when an
        // ally is down or badly hurt, and after that establishing a buff (Bless/Haste) if we are not concentrating.
        tryLayOnHands(api);
        if (tryHeal(api) || tryBuff(api)) {
            return;
        }

        List<Combatant> enemies = api.enemies();
        if (enemies.isEmpty()) {
            return;
        }

        // The damage target (best to kill) and the control target (most dangerous); the first of equals wins.
        Combatant damageTarget = Picks.firstMax(enemies, e -> scoreTarget(api.self(), e, weights)).orElseThrow();
        Combatant controlTarget = Picks.firstMax(enemies, TacticalPolicy::threatOf).orElseThrow();

        // Hunter's Mark on the kill target (bonus action), before attacking.
        tryMark(api, damageTarget);
        castOrAttack(api, weights, enemies, damageTarget, controlTarget);

        // Sorcerer Metamagic: a quickened cantrip as a bonus action, after the action.
        tryQuickenedCantrip(api, damageTarget);
    }

    /** Cast if a spell beats the weapon; otherwise make weapon attacks. */
    private static void castOrAttack(
            TurnApi api, TacticsWeights weights, List<Combatant> enemies, Combatant damageTarget, Combatant controlTarget) {
        AttackProfile weapon = primaryWeapon(api.self());
        double weaponEv = weapon != null
                ? weaponAverageDamage(weapon) * (1 + api.self().extraAttacks()) * weights.assumedHitChance()
                : -1;
        SpellChoice spell = bestSpell(api.self(), damageTarget, controlTarget, enemies);

        if (spell != null && spell.ev() > weaponEv) {
            approach(api, spell.target(), spell.rangeFt());
            if (GridMath.distanceFt(api.self().position(), spell.target().position()) <= spell.rangeFt()) {
                api.castSpell(spell.spell(), spell.target(), spell.slotLevel(), false);
            }
        } else if (weapon != null) {
            attackWithWeapon(api, weapon, damageTarget);
        }
    }

    private static void attackWithWeapon(TurnApi api, AttackProfile weapon, Combatant target) {
        int rangeFt = weaponRangeFt(weapon);
        approach(api, target, rangeFt);
        if (GridMath.distanceFt(api.self().position(), target.position()) > rangeFt) {
            return;
        }
        // Drain the Attack action, its Extra Attacks, then any buff-granted extra attack action (Haste), so a hasted
        // striker actually uses the extra swing.
        var dmg = api.attack(target, weapon);
        while (dmg.isPresent()
                && target.isConscious()
                && (api.resources().attacksRemaining() > 0 || api.resources().extraAttackActions() > 0)) {
            dmg = api.attack(target, weapon);
        }
    }
}
