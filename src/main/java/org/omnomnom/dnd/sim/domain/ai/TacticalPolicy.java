package org.omnomnom.dnd.sim.domain.ai;

import java.util.ArrayList;
import java.util.List;
import org.omnomnom.dnd.sim.domain.combat.AttackKind;
import org.omnomnom.dnd.sim.domain.combat.AttackProfile;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.combat.ExtraDamage;
import org.omnomnom.dnd.sim.domain.combat.TurnApi;
import org.omnomnom.dnd.sim.domain.combat.TurnPolicy;
import org.omnomnom.dnd.sim.domain.combat.spell.Spell;
import org.omnomnom.dnd.sim.domain.combat.spell.SpellKind;
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
    /** Sorcery Points a Quickened Spell costs (mirrors the engine's quicken cost). */
    private static final int QUICKEN_COST = 2;

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
        AttackProfile best = null;
        for (AttackProfile w : c.activeAttacks()) {
            if (best == null || weaponAverageDamage(w) > weaponAverageDamage(best)) {
                best = w;
            }
        }
        return best;
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
        SpellKind kind = spell.kind();
        if (kind instanceof SpellKind.AttackDamage ad) {
            int rays = ad.beams() != null
                    ? ad.beams().applyAsInt(self.level())
                    : Spell.raysAt(ad, slotLevel, Math.max(1, spell.level()));
            int bonus = ad.addSpellMod() && self.spellAbility() != null ? self.abilityMod(self.spellAbility()) : 0;
            double dmg = rays * (ad.damage().at(slotLevel, self.level()).mean() + bonus) * ASSUMED_HIT;
            return Math.min(dmg, target.hp());
        }
        if (kind instanceof SpellKind.Control ctl) {
            // Value control as damage prevented: a locked enemy denies ~its own output for the rounds it stays
            // locked, weighted by the chance it fails the save.
            if (ctl.aoeRadiusFt() == null) {
                return controlValue(target);
            }
            int radius = ctl.aoeRadiusFt();
            List<Combatant> caught = within(enemies, target.position(), radius);
            double sum = 0;
            for (Combatant e : caught.isEmpty() ? List.of(target) : caught) {
                sum += controlValue(e);
            }
            return sum;
        }
        if (!(kind instanceof SpellKind.SaveDamage sd)) {
            return 0; // heal and other non-damage kinds
        }
        // Save-damage: expected damage per target after the save, capped per target's HP.
        double perTarget = sd.damage().at(slotLevel, self.level()).mean()
                * (ASSUMED_SAVE_FAIL + (1 - ASSUMED_SAVE_FAIL) * (sd.onSuccess() == SpellKind.OnSuccess.HALF ? 0.5 : 0));
        if (sd.aoeRadiusFt() == null) {
            return Math.min(perTarget, target.hp());
        }
        Cell origin = sd.selfOrigin() ? self.position() : target.position();
        List<Combatant> caught = within(enemies, origin, sd.aoeRadiusFt());
        double sum = 0;
        for (Combatant e : caught.isEmpty() ? List.of(target) : caught) {
            sum += Math.min(perTarget, e.hp());
        }
        return sum;
    }

    private static double controlValue(Combatant t) {
        return threatOf(t) * ROUNDS_DENIED * ASSUMED_SAVE_FAIL;
    }

    private static List<Combatant> within(List<Combatant> enemies, Cell origin, int radiusFt) {
        List<Combatant> out = new ArrayList<>();
        for (Combatant e : enemies) {
            if (GridMath.distanceFt(origin, e.position()) <= radiusFt) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * The best offensive spell to cast, or null. Damage spells are valued against {@code damageTarget} (the
     * wounded/best kill target); control spells against {@code controlTarget} (the most dangerous enemy, who is who
     * you want to lock down).
     */
    private static SpellChoice bestSpell(
            Combatant self, Combatant damageTarget, Combatant controlTarget, List<Combatant> enemies) {
        SpellChoice best = null;
        for (Spell cantrip : self.cantrips()) {
            best = consider(best, self, cantrip, 0, damageTarget, controlTarget, enemies);
        }
        for (Spell spell : self.spells()) {
            // Healing and buffs are handled by their own steps (tryHeal / tryBuff).
            if (spell.kind() instanceof SpellKind.Heal || spell.kind() instanceof SpellKind.Buff) {
                continue;
            }
            // Consider every affordable slot level, so a damage spell upcasts into a higher slot when the extra dice
            // (capped at the target's HP) beat the slot's cost.
            for (int slot : self.availableSlotLevels()) {
                if (slot >= spell.level()) {
                    best = consider(best, self, spell, slot, damageTarget, controlTarget, enemies);
                }
            }
        }
        return best;
    }

    private static SpellChoice consider(
            SpellChoice best, Combatant self, Spell spell, int slotLevel, Combatant damageTarget,
            Combatant controlTarget, List<Combatant> enemies) {
        Combatant target = spell.kind() instanceof SpellKind.Control ? controlTarget : damageTarget;
        double ev = spellExpectedDamage(self, spell, slotLevel, target, enemies) - slotLevel * SLOT_PENALTY;
        if (best == null || ev > best.ev()) {
            return new SpellChoice(spell, slotLevel, ev, spell.rangeFt(), target);
        }
        return best;
    }

    // ---- healing, buffs and class riders -------------------------------------------------------

    /** An ally worth healing this turn: a downed ally first, else a badly wounded one (lowest HP fraction first). */
    private static Combatant pickHealTarget(TurnApi api) {
        List<Combatant> allies = api.allAllies();
        for (Combatant a : allies) {
            if (a.isDying()) {
                return a;
            }
        }
        Combatant best = null;
        double bestFrac = 0;
        for (Combatant a : allies) {
            if (!a.isConscious()) {
                continue;
            }
            double frac = (double) a.hp() / a.maxHp();
            if (frac < 0.4 && (best == null || frac < bestFrac)) {
                best = a;
                bestFrac = frac;
            }
        }
        return best;
    }

    private static Integer firstSlotAtLeast(Combatant c, int level) {
        for (int l : c.availableSlotLevels()) {
            if (l >= level) {
                return l;
            }
        }
        return null;
    }

    /**
     * If the caster has a heal spell and an ally needs it, heal them. Prefers a bonus-action heal (Healing Word) for a
     * downed ally so the caster can still act; returns true if the whole turn's action was spent healing.
     */
    private static boolean tryHeal(TurnApi api) {
        List<Spell> healSpells = new ArrayList<>();
        for (Spell s : api.self().spells()) {
            if (s.kind() instanceof SpellKind.Heal) {
                healSpells.add(s);
            }
        }
        if (healSpells.isEmpty()) {
            return false;
        }
        Combatant target = pickHealTarget(api);
        if (target == null) {
            return false;
        }
        Integer slot = firstSlotAtLeast(api.self(), 1);
        if (slot == null) {
            return false;
        }

        Spell bonusHeal = null;
        Spell actionHeal = null;
        for (Spell s : healSpells) {
            if (s.action() == Spell.CastingTime.BONUS && bonusHeal == null) {
                bonusHeal = s;
            }
            if (s.action() == Spell.CastingTime.ACTION && actionHeal == null) {
                actionHeal = s;
            }
        }
        // Prefer a bonus-action heal to revive while keeping the action for offense.
        if (target.isDying() && bonusHeal != null && api.resources().bonus()) {
            api.castSpell(bonusHeal, target, slot, false);
            return false; // action still free
        }
        Spell spell = actionHeal != null ? actionHeal : bonusHeal;
        api.castSpell(spell, target, slot, false);
        return spell.action() == Spell.CastingTime.ACTION;
    }

    private record BuffValue(double value, List<Combatant> targets) {}

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
            if (kind.extraAttackAction()) {
                value += 0.5 * threatOf(a); // roughly one extra attack
            }
            if (kind.attackBonusDice() != null) {
                value += 0.12 * threatOf(a); // +~2.5 to hit is about +12% of output
            }
            if (kind.acBonus() != 0) {
                value += 0.5 * kind.acBonus();
            }
        }
        return new BuffValue(value, ranked);
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
        List<Spell> buffSpells = new ArrayList<>();
        for (Spell s : self.spells()) {
            if (s.kind() instanceof SpellKind.Buff) {
                buffSpells.add(s);
            }
        }
        if (buffSpells.isEmpty()) {
            return false;
        }

        // Allies that actually attack are worth buffing.
        List<Combatant> combatants = new ArrayList<>();
        for (Combatant a : api.allies()) {
            if (threatOf(a) > 0) {
                combatants.add(a);
            }
        }
        if (combatants.isEmpty()) {
            return false;
        }

        // Rank affordable buffs by value against the allies currently in range, else by value against the strongest
        // ally we could approach.
        List<Spell> affordable = new ArrayList<>();
        for (Spell s : buffSpells) {
            if (firstSlotAtLeast(self, s.level()) != null) {
                affordable.add(s);
            }
        }
        if (affordable.isEmpty()) {
            return false;
        }

        Spell chosenSpell = null;
        Combatant chosenTarget = null;
        double bestValue = 0;
        for (Spell s : affordable) {
            BuffValue bv = buffValue(s, inRangeOf(self, combatants, s));
            if (!bv.targets().isEmpty() && bv.value() > bestValue) {
                bestValue = bv.value();
                chosenSpell = s;
                chosenTarget = bv.targets().get(0);
            }
        }

        // Nobody in range: approach the strongest ally for the best affordable buff, then retry.
        if (chosenSpell == null) {
            Combatant strongest = null;
            for (Combatant c : combatants) {
                if (strongest == null || threatOf(c) > threatOf(strongest)) {
                    strongest = c;
                }
            }
            Spell spell = affordable.get(0);
            for (Spell s : affordable) {
                if (s.level() > spell.level()) {
                    spell = s;
                }
            }
            approach(api, strongest, spell.rangeFt());
            BuffValue bv = buffValue(spell, inRangeOf(self, combatants, spell));
            if (bv.targets().isEmpty()) {
                return false;
            }
            chosenSpell = spell;
            chosenTarget = bv.targets().get(0);
        }

        Integer slot = firstSlotAtLeast(self, chosenSpell.level());
        var r = api.castSpell(chosenSpell, chosenTarget, slot, false);
        return r.isPresent() && chosenSpell.action() == Spell.CastingTime.ACTION;
    }

    private static List<Combatant> inRangeOf(Combatant self, List<Combatant> allies, Spell s) {
        List<Combatant> out = new ArrayList<>();
        for (Combatant a : allies) {
            if (GridMath.distanceFt(self.position(), a.position()) <= s.rangeFt()) {
                out.add(a);
            }
        }
        return out;
    }

    /**
     * Metamagic (Sorcerer): after the main action, spend Sorcery Points to cast a damage cantrip as a Bonus Action - a
     * second spell in the turn. Fires when the caster has a 'sorcery' pool, a free bonus action, a damage cantrip, and
     * an enemy in range of it.
     */
    private static void tryQuickenedCantrip(TurnApi api, Combatant damageTarget) {
        Combatant self = api.self();
        if (!api.resources().bonus() || self.resourceCount("sorcery") < QUICKEN_COST) {
            return;
        }
        Spell cantrip = null;
        for (Spell c : self.cantrips()) {
            if (c.kind() instanceof SpellKind.AttackDamage || c.kind() instanceof SpellKind.SaveDamage) {
                cantrip = c;
                break;
            }
        }
        if (cantrip == null) {
            return;
        }
        if (GridMath.distanceFt(self.position(), damageTarget.position()) > cantrip.rangeFt()) {
            return;
        }
        api.castSpell(cantrip, damageTarget, 0, true);
    }

    /**
     * Lay on Hands (Paladin): a Bonus Action top-up for a downed or badly hurt ally, from the healing pool, costing no
     * spell slot. It uses only the bonus action, so the paladin still takes its action normally this turn.
     */
    private static void tryLayOnHands(TurnApi api) {
        if (api.self().resourceCount("lay-on-hands") <= 0 || !api.resources().bonus()) {
            return;
        }
        Combatant target = pickHealTarget(api);
        if (target != null) {
            api.layOnHands(target);
        }
    }

    /**
     * Hunter's Mark (Ranger): place the mark on the kill target as a Bonus Action when the ranger has a free use and
     * isn't already concentrating, so its hits carry the extra damage.
     */
    private static void tryMark(TurnApi api, Combatant damageTarget) {
        Combatant self = api.self();
        if (self.concentratingOn() != null || !api.resources().bonus()) {
            return;
        }
        if (self.features().stream().noneMatch(f -> f.id().equals("hunters-mark"))) {
            return;
        }
        if (self.resourceCount("hunters-mark") <= 0) {
            return;
        }
        api.markTarget(damageTarget);
    }

    // ---- the turn ------------------------------------------------------------------------------

    private static void act(TurnApi api, TacticsWeights weights) {
        // Lay on Hands first: a free bonus-action top-up that keeps the action open.
        tryLayOnHands(api);
        // Healing takes priority when an ally is down or badly hurt.
        if (tryHeal(api)) {
            return;
        }
        // Then establish a buff (Bless/Haste) if we have one and aren't concentrating.
        if (tryBuff(api)) {
            return;
        }

        List<Combatant> enemies = api.enemies();
        if (enemies.isEmpty()) {
            return;
        }

        // The damage target (best to kill) and the control target (most dangerous).
        Combatant damageTarget = enemies.get(0);
        for (Combatant e : enemies) {
            if (scoreTarget(api.self(), e, weights) > scoreTarget(api.self(), damageTarget, weights)) {
                damageTarget = e;
            }
        }
        Combatant controlTarget = enemies.get(0);
        for (Combatant e : enemies) {
            if (threatOf(e) > threatOf(controlTarget)) {
                controlTarget = e;
            }
        }

        // Hunter's Mark on the kill target (bonus action), before attacking.
        tryMark(api, damageTarget);

        AttackProfile weapon = primaryWeapon(api.self());
        double weaponEv = weapon != null
                ? weaponAverageDamage(weapon) * (1 + api.self().extraAttacks()) * weights.assumedHitChance()
                : -1;
        SpellChoice spell = bestSpell(api.self(), damageTarget, controlTarget, enemies);

        // Cast if a spell beats the weapon; otherwise make weapon attacks.
        if (spell != null && spell.ev() > weaponEv) {
            approach(api, spell.target(), spell.rangeFt());
            if (GridMath.distanceFt(api.self().position(), spell.target().position()) <= spell.rangeFt()) {
                api.castSpell(spell.spell(), spell.target(), spell.slotLevel(), false);
            }
        } else if (weapon != null) {
            int rangeFt = weaponRangeFt(weapon);
            approach(api, damageTarget, rangeFt);
            if (GridMath.distanceFt(api.self().position(), damageTarget.position()) <= rangeFt) {
                // Drain the Attack action, its Extra Attacks, then any buff-granted extra attack action (Haste), so a
                // hasted striker actually uses the extra swing.
                var dmg = api.attack(damageTarget, weapon);
                while (dmg.isPresent()
                        && damageTarget.isConscious()
                        && (api.resources().attacksRemaining() > 0 || api.resources().extraAttackActions() > 0)) {
                    dmg = api.attack(damageTarget, weapon);
                }
            }
        }

        // Sorcerer Metamagic: a quickened cantrip as a bonus action, after the action.
        tryQuickenedCantrip(api, damageTarget);
    }
}
