package org.omnomnom.dnd.sim.domain.combat;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.omnomnom.dnd.sim.domain.combat.AttackResolver.AttackParams;
import org.omnomnom.dnd.sim.domain.combat.AttackResolver.AttackResult;
import org.omnomnom.dnd.sim.domain.combat.AttackResolver.SaveResult;
import org.omnomnom.dnd.sim.domain.combat.event.CombatEvent;
import org.omnomnom.dnd.sim.domain.combat.spell.BuffSpec;
import org.omnomnom.dnd.sim.domain.combat.spell.Spell;
import org.omnomnom.dnd.sim.domain.combat.spell.SpellKind;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.dice.Dice;
import org.omnomnom.dnd.sim.domain.grid.Cell;
import org.omnomnom.dnd.sim.domain.rng.Rng;

/**
 * Resolves a spell cast. Cantrips cost the action only; leveled spells also spend a slot (by default the spell's own
 * level). An attack-damage spell makes a spell attack per ray; a save-damage spell makes the target (and, for an area
 * spell, every enemy in radius of its cell) roll a save; the other kinds heal, impose a condition or place a buff.
 */
final class SpellResolver {

    /** What a cast achieved, for the {@code SpellCast} event. */
    private record Outcome(int damage, int healing, int targetsHit) {}

    /** The facts of one cast that every kind's handler needs. */
    private record Cast(Combatant self, Spell spell, Combatant target, int slotLevel, int distanceFt, Rng damageStream) {

        String concentrationOwner() {
            return spell.concentration() ? self.id() : null;
        }

        /** The caster's spellcasting modifier, or 0 if it has no spellcasting ability. */
        int spellMod() {
            return self.spellAbility() != null ? self.abilityMod(self.spellAbility()) : 0;
        }
    }

    private final FightContext ctx;
    private final Rolls rolls;
    private final DamageApplier applier;

    SpellResolver(FightContext ctx, Rolls rolls, DamageApplier applier) {
        this.ctx = ctx;
        this.rolls = rolls;
        this.applier = applier;
    }

    /** Total damage dealt, or empty if the cast is illegal (nothing is spent in that case). */
    OptionalInt cast(Combatant self, Spell spell, Combatant target, Integer slotLevelArg, TurnResources resources, boolean quickened) {
        if (!canSpendAction(self, spell, resources, quickened)) {
            return OptionalInt.empty();
        }
        OptionalInt slot = slotLevelFor(self, spell, slotLevelArg);
        if (slot.isEmpty()) {
            return OptionalInt.empty();
        }
        int slotLevel = slot.getAsInt();

        // Range check against the primary target's cell.
        int dist = ctx.distanceFt(self, target);
        if (dist > spell.rangeFt()) {
            return OptionalInt.empty();
        }

        Cast cast = new Cast(self, spell, target, slotLevel, dist, ctx.rng().stream(self.id() + ":" + spell.id() + ":dmg"));
        Optional<Outcome> outcome = resolveKind(cast);
        if (outcome.isEmpty()) {
            return OptionalInt.empty();
        }

        spend(self, spell, slotLevel, resources, quickened);
        Outcome o = outcome.get();
        ctx.log(new CombatEvent.SpellCast(self.id(), spell.name(), slotLevel, o.targetsHit(), o.damage(), o.healing()));
        return OptionalInt.of(o.damage());
    }

    /**
     * Action economy: a spell normally uses the action (bonus-action spells use the bonus). Quickened Spell (Sorcerer
     * Metamagic) casts it as a Bonus Action for 2 Sorcery Points instead.
     */
    private static boolean canSpendAction(Combatant self, Spell spell, TurnResources resources, boolean quickened) {
        if (quickened) {
            return resources.bonus && self.resourceCount(ResourceIds.SORCERY) >= ResourceIds.QUICKENED_SPELL_COST;
        }
        return spell.action() == Spell.CastingTime.BONUS ? resources.bonus : resources.action;
    }

    /** The slot level to cast at (0 for a cantrip), or empty if the caster cannot cast at the requested level. */
    private static OptionalInt slotLevelFor(Combatant self, Spell spell, Integer requested) {
        if (spell.level() == 0) {
            return OptionalInt.of(0);
        }
        int slotLevel = requested != null ? requested : spell.level();
        if (slotLevel < spell.level() || self.slotCount(slotLevel) <= 0) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(slotLevel);
    }

    /** Empty when nothing could be affected and the cast should not consume the slot or action. */
    private Optional<Outcome> resolveKind(Cast c) {
        return switch (c.spell().kind()) {
            case SpellKind.Heal heal -> Optional.of(heal(c, heal));
            case SpellKind.AttackDamage ad -> Optional.of(attackDamage(c, ad));
            case SpellKind.Control ctl -> Optional.of(control(c, ctl));
            case SpellKind.Buff buff -> buff(c, buff);
            case SpellKind.SaveDamage sd -> Optional.of(saveDamage(c, sd));
        };
    }

    /** Pay for the cast: the action, bonus action or Sorcery Points, the slot, and concentration. */
    private static void spend(Combatant self, Spell spell, int slotLevel, TurnResources resources, boolean quickened) {
        if (quickened) {
            resources.bonus = false;
            self.spendResource(ResourceIds.SORCERY, ResourceIds.QUICKENED_SPELL_COST);
        } else if (spell.action() == Spell.CastingTime.BONUS) {
            resources.bonus = false;
        } else {
            resources.action = false;
        }
        if (spell.level() > 0) {
            self.spendSlot(slotLevel);
        }
        if (spell.concentration()) {
            self.setConcentratingOn(spell.id());
        }
    }

    // ---- one handler per kind ------------------------------------------------------------------

    /** Target is an ally; restore HP (reviving if at 0). */
    private Outcome heal(Cast c, SpellKind.Heal heal) {
        int amount = heal.dice().at(c.slotLevel(), c.self().level()).roll(c.damageStream()) + (heal.addSpellMod() ? c.spellMod() : 0);
        return new Outcome(0, c.target().heal(amount), 1);
    }

    private Outcome attackDamage(Cast c, SpellKind.AttackDamage ad) {
        // Beam count: level-based (Eldritch Blast) or the upcast-ray path.
        int rays = ad.beams() != null
                ? ad.beams().applyAsInt(c.self().level())
                : Spell.raysAt(ad, c.slotLevel(), Math.max(1, c.spell().level()));
        Dice damage = ad.damage().at(c.slotLevel(), c.self().level());
        // Agonizing Blast adds the caster's spell modifier to each beam's damage.
        int perBeamBonus = ad.addSpellMod() ? c.spellMod() : 0;
        int total = 0;
        for (int ray = 0; ray < rays && c.target().isConscious(); ray++) {
            total += fireRay(c, ad, damage, perBeamBonus, ray);
        }
        return new Outcome(total, 0, total > 0 ? 1 : 0);
    }

    /** One spell attack roll; damage dealt on a hit, else 0. */
    private int fireRay(Cast c, SpellKind.AttackDamage ad, Dice damage, int perBeamBonus, int ray) {
        Combatant self = c.self();
        Combatant target = c.target();
        int buffToHit = rolls.buffAttackBonus(self, c.spell().id() + ":" + target.id() + ":" + ray);
        var atkStream = ctx.rng().stream(self.id() + ":" + c.spell().id() + ":" + target.id() + ":atk:" + ray);
        AttackResult result = AttackResolver.resolveAttack(atkStream, AttackParams.of(
                        self.spellAttackBonus() + buffToHit, target.effectiveAc())
                .withAdvantage(Conditions.attackAdvantage(self, target, c.distanceFt() <= 5)));
        if (!result.hit()) {
            return 0;
        }
        int raw = damage.roll(c.damageStream());
        if (result.crit()) {
            raw += damage.withoutBonus().roll(c.damageStream());
        }
        raw += perBeamBonus;
        int dealt = DamageMitigation.applyResponse(raw, target.damageResponseFor(ad.damageType()));
        return applier.applySpellDamage(self, target, dealt);
    }

    /**
     * Save-or-condition: each target saves; on a failure the condition is applied for a duration, repeating the save each
     * turn to shake it off.
     */
    private Outcome control(Cast c, SpellKind.Control ctl) {
        List<Combatant> victims = victims(c, ctl.aoeRadiusFt(), c.target().position());
        int dc = c.self().spellSaveDc();
        int affected = 0;
        for (Combatant v : victims) {
            if (!savesAgainst(c, v, ctl.save(), dc).success()) {
                v.applyTimedCondition(new TimedConditionSpec(
                        ctl.condition(),
                        c.self().id(),
                        ctl.rounds(),
                        ctl.repeatSaveEndsEffect() ? new RepeatSave(ctl.save(), dc, true) : null,
                        c.concentrationOwner()));
                affected++;
            }
        }
        return new Outcome(0, 0, affected);
    }

    /**
     * Place a beneficial effect on up to maxTargets allies (the chosen target first, then any others in range),
     * refreshing rather than stacking. A buff with no valid recipient is empty so it does not consume the slot or action.
     */
    private Optional<Outcome> buff(Cast c, SpellKind.Buff buff) {
        List<Combatant> inRange = ctx.roster().consciousAllies(c.self()).stream()
                .filter(ally -> ctx.distanceFt(c.self(), ally) <= c.spell().rangeFt())
                .toList();
        List<Combatant> chosen = Stream.concat(
                        inRange.stream().filter(ally -> ally == c.target()), inRange.stream().filter(ally -> ally != c.target()))
                .limit(buff.maxTargets())
                .toList();
        if (chosen.isEmpty()) {
            return Optional.empty();
        }
        for (Combatant ally : chosen) {
            ally.applyBuff(new BuffSpec(
                    buff.buffId(),
                    c.self().id(),
                    buff.rounds(),
                    buff.attackBonusDice(),
                    buff.saveBonusDice(),
                    buff.acBonus(),
                    buff.extraAttackAction(),
                    c.concentrationOwner()));
            ctx.log(new CombatEvent.BuffApplied(c.self().id(), buff.buffId(), ally.id()));
        }
        return Optional.of(new Outcome(0, 0, chosen.size()));
    }

    private Outcome saveDamage(Cast c, SpellKind.SaveDamage sd) {
        Cell origin = sd.selfOrigin() ? c.self().position() : c.target().position();
        List<Combatant> victims = victims(c, sd.aoeRadiusFt(), origin);
        Dice damage = sd.damage().at(c.slotLevel(), c.self().level());
        int dc = c.self().spellSaveDc();
        // Area damage is rolled once and shared (2024 rule).
        int rolled = damage.roll(c.damageStream());
        int total = 0;
        int hit = 0;
        for (Combatant v : victims) {
            SaveResult save = savesAgainst(c, v, sd.save(), dc);
            int amount = rolled;
            if (save.success()) {
                amount = sd.onSuccess() == SpellKind.OnSuccess.HALF ? rolled / 2 : 0;
            }
            int dealt = DamageMitigation.applyResponse(amount, v.damageResponseFor(sd.damageType()));
            if (dealt > 0) {
                total += applier.applySpellDamage(c.self(), v, dealt);
                hit++;
            }
        }
        return new Outcome(total, 0, hit);
    }

    /** The primary target alone, or for an area spell every enemy within the radius of {@code origin}. */
    private List<Combatant> victims(Cast c, Integer aoeRadiusFt, Cell origin) {
        return aoeRadiusFt != null ? ctx.roster().consciousOpponentsWithin(c.self(), origin, aoeRadiusFt) : List.of(c.target());
    }

    private SaveResult savesAgainst(Cast c, Combatant victim, Ability ability, int dc) {
        String label = c.self().id() + ":" + c.spell().id() + ":" + victim.id() + Rolls.SAVE_SUFFIX;
        return rolls.save(label, victim, ability, c.spell().id() + ":" + c.self().id(), dc);
    }
}
