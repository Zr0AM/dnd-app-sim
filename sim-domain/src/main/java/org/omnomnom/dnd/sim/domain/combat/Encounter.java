package org.omnomnom.dnd.sim.domain.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Function;
import org.omnomnom.dnd.sim.domain.combat.AttackResolver.AttackParams;
import org.omnomnom.dnd.sim.domain.combat.AttackResolver.AttackResult;
import org.omnomnom.dnd.sim.domain.combat.AttackResolver.SaveParams;
import org.omnomnom.dnd.sim.domain.combat.AttackResolver.SaveResult;
import org.omnomnom.dnd.sim.domain.combat.event.CombatEvent;
import org.omnomnom.dnd.sim.domain.combat.event.EventSink;
import org.omnomnom.dnd.sim.domain.combat.spell.BuffSpec;
import org.omnomnom.dnd.sim.domain.combat.spell.Spell;
import org.omnomnom.dnd.sim.domain.combat.spell.SpellKind;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.Condition;
import org.omnomnom.dnd.sim.domain.core.Picks;
import org.omnomnom.dnd.sim.domain.core.Side;
import org.omnomnom.dnd.sim.domain.dice.Advantage;
import org.omnomnom.dnd.sim.domain.dice.Dice;
import org.omnomnom.dnd.sim.domain.grid.Cell;
import org.omnomnom.dnd.sim.domain.grid.Grid;
import org.omnomnom.dnd.sim.domain.grid.GridMath;
import org.omnomnom.dnd.sim.domain.rng.LabeledRandom;

/**
 * The initiative order and turn loop that drives a fight round by round (SRD "The Order of Combat"). It handles
 * initiative, per-turn resources (action, bonus action, movement), movement with opportunity attacks, weapon
 * attacks wired to condition-derived advantage, spellcasting, death saves, legendary actions, and win detection.
 *
 * <p>What a creature does on its turn is an injected {@link TurnPolicy}, acting through a restricted
 * {@link TurnApi} that enforces the action economy. Fights are deterministic under a seed: every random draw comes
 * from a stream labeled by combatant, spell and target ids (never by draw order), exactly as in the TypeScript
 * engine, so two runs produce identical event logs.
 *
 * <p>An encounter is single-use and <strong>not thread-safe</strong>: create one per simulated fight.
 */
public final class Encounter {

    /** Sorcery Points a Quickened Spell costs (Metamagic). */
    private static final int QUICKEN_COST = 2;

    /** Suffix of the random-stream label for a saving throw (keeps each save's draws distinct and reproducible). */
    private static final String SAVE_LABEL = ":save";

    /** Default round cap for {@link #run()}. */
    public static final int DEFAULT_MAX_ROUNDS = 100;

    private final Grid grid;
    private final List<Combatant> combatants;
    private final Roster roster;
    private final LabeledRandom rng;
    private final Function<Combatant, TurnPolicy> policyFor;
    private final EventSink sink;
    private int round;
    private List<Combatant> order = List.of();
    /** Monotonic counter so each concentration save draws a distinct stream value. */
    private int concSeq;

    private Encounter(Builder b) {
        this.grid = b.grid;
        this.combatants = new ArrayList<>(b.combatants);
        this.roster = new Roster(combatants, b.grid.cellFt());
        this.rng = b.rng;
        this.policyFor = b.policyFor;
        this.sink = b.sink;
    }

    public static Builder builder(Grid grid, List<Combatant> combatants, LabeledRandom rng) {
        return new Builder(grid, combatants, rng);
    }

    public static final class Builder {
        private final Grid grid;
        private final List<Combatant> combatants;
        private final LabeledRandom rng;
        private Function<Combatant, TurnPolicy> policyFor = c -> TurnPolicy.IDLE;
        private EventSink sink = EventSink.NOOP;

        private Builder(Grid grid, List<Combatant> combatants, LabeledRandom rng) {
            this.grid = grid;
            this.combatants = combatants;
            this.rng = rng;
        }

        /** The policy for a given combatant. Defaults to {@link TurnPolicy#IDLE}. */
        public Builder policyFor(Function<Combatant, TurnPolicy> v) {
            this.policyFor = v;
            return this;
        }

        /** Where events go. Defaults to {@link EventSink#NOOP}. */
        public Builder sink(EventSink v) {
            this.sink = v;
            return this;
        }

        public Encounter build() {
            return new Encounter(this);
        }
    }

    /** The outcome of {@link #run}. {@code winner} is null for a draw or when the round cap was reached. */
    public record RunResult(int rounds, Side winner) {}

    public Grid grid() {
        return grid;
    }

    public List<Combatant> combatants() {
        return combatants;
    }

    public int round() {
        return round;
    }

    private void log(CombatEvent event) {
        sink.accept(event);
    }

    /** The fight is over when at most one side still has a conscious combatant. */
    public boolean isOver() {
        return !roster.anyConscious(Side.PARTY) || !roster.anyConscious(Side.ENEMY);
    }

    /** The winning side, or null if both or neither side has a conscious combatant. */
    public Side winner() {
        boolean party = roster.anyConscious(Side.PARTY);
        boolean enemy = roster.anyConscious(Side.ENEMY);
        if (party == enemy) {
            return null;
        }
        return party ? Side.PARTY : Side.ENEMY;
    }

    /**
     * Roll initiative: d20 + Dex modifier, advantage if Invisible, disadvantage if Incapacitated when rolling (the
     * surprise rule). Ordered high to low; ties break by Dex modifier, then party before enemy, then id.
     */
    public List<Combatant> rollInitiative() {
        record Scored(Combatant c, int total) {}
        List<Scored> scored = new ArrayList<>();
        for (Combatant c : combatants) {
            Advantage adv = Advantage.NORMAL;
            if (c.hasCondition(Condition.INVISIBLE)) {
                adv = Advantage.ADVANTAGE;
            }
            if (c.hasCondition(Condition.INCAPACITATED)) {
                adv = Advantage.DISADVANTAGE;
            }
            int total = Dice.rollD20(rng.stream("initiative:" + c.id()), adv) + c.abilityMod(Ability.DEX);
            scored.add(new Scored(c, total));
        }
        scored.sort(Comparator.<Scored>comparingInt(s -> -s.total())
                .thenComparingInt(s -> -s.c().abilityMod(Ability.DEX))
                .thenComparingInt(s -> s.c().side() == Side.PARTY ? 0 : 1)
                .thenComparing(s -> s.c().id()));
        List<Combatant> ordered = new ArrayList<>();
        List<CombatEvent.Initiative.Entry> entries = new ArrayList<>();
        for (Scored s : scored) {
            ordered.add(s.c());
            entries.add(new CombatEvent.Initiative.Entry(s.c().id(), s.total()));
        }
        this.order = ordered;
        log(new CombatEvent.Initiative(entries));
        return order;
    }

    /** Run one round: every combatant acts in initiative order. */
    public void runRound() {
        if (order.isEmpty()) {
            rollInitiative();
        }
        round += 1;
        log(new CombatEvent.Round(round));

        for (Combatant c : order) {
            if (isOver()) {
                break;
            }
            if (c.dead()) {
                continue;
            }

            // A boss refreshes its legendary actions at the start of its own turn.
            if (c.legendaryMax() > 0) {
                c.refreshLegendary();
            }

            // Start of turn: a dying creature rolls a death save and does nothing else.
            if (c.isDying()) {
                if (!c.stable()) {
                    DeathSaveOutcome out = c.rollDeathSave(rng.stream("death:" + c.id()));
                    log(new CombatEvent.DeathSave(c.id(), out.d20(), out.success()));
                    if (out.died()) {
                        log(new CombatEvent.Death(c.id()));
                    }
                }
                takeLegendaryActions(c);
                continue;
            }

            if (!Conditions.canAct(c)) {
                // The creature's turn is denied; attribute it to whoever controls it.
                for (String source : new java.util.LinkedHashSet<>(c.controlSources())) {
                    log(new CombatEvent.ControlDenied(c.id(), source));
                }
                endOfTurn(c);
                takeLegendaryActions(c);
                continue;
            }

            // Start-of-turn feature hooks (reset per-turn state, auto-activate Rage, ...).
            for (Feature f : c.features()) {
                f.onTurnStart(c);
            }

            log(new CombatEvent.Turn(c.id(), round));
            int extraAttackActions = c.hasExtraAttackAction() ? 1 : 0;
            for (Feature f : c.features()) {
                extraAttackActions += f.bonusAttackActions(c);
            }
            TurnResources resources = new TurnResources(Conditions.effectiveSpeedFt(c), extraAttackActions);
            policyFor.apply(c).act(new Api(c, resources));
            endOfTurn(c);
            takeLegendaryActions(c);
        }
    }

    /** End-of-turn upkeep: tick timed conditions (repeat saves, durations) and buffs. */
    private void endOfTurn(Combatant c) {
        c.tickTimedConditions(rng.stream("tick:" + c.id() + ":" + round));
        c.tickBuffs();
    }

    /**
     * At the end of {@code justActed}'s turn, each other conscious boss may spend one legendary action to attack its
     * nearest reachable opponent (a single attack with its strongest weapon). Legendary options beyond a simple
     * attack (wing buffets, moves, saves) are a documented simplification.
     */
    private void takeLegendaryActions(Combatant justActed) {
        for (Combatant boss : combatants) {
            if (boss == justActed || !boss.isConscious() || boss.legendaryRemaining() <= 0) {
                continue;
            }
            AttackProfile weapon = bestAttack(boss);
            if (weapon == null) {
                continue;
            }
            int reach;
            if (weapon.kind() == AttackKind.MELEE) {
                reach = weapon.reachFtOrDefault();
            } else if (weapon.rangeLongFt() != null) {
                reach = weapon.rangeLongFt();
            } else if (weapon.rangeFt() != null) {
                reach = weapon.rangeFt();
            } else {
                reach = 5;
            }
            Combatant target = null;
            int best = Integer.MAX_VALUE;
            for (Combatant t : combatants) {
                if (t.side() != boss.side() && t.isConscious()) {
                    int d = distanceFt(boss, t);
                    if (d < best) {
                        best = d;
                        target = t;
                    }
                }
            }
            if (target == null || best > reach) {
                continue;
            }
            if (!boss.spendLegendary()) {
                continue;
            }
            Integer dmg = resolveWeaponAttack(boss, target, weapon, AttackSource.OPPORTUNITY);
            log(new CombatEvent.Legendary(boss.id(), target.id(), dmg == null ? 0 : dmg));
        }
    }

    /** Run the fight to a conclusion (or the round cap). */
    public RunResult run(int maxRounds) {
        if (order.isEmpty()) {
            rollInitiative();
        }
        while (!isOver() && round < maxRounds) {
            runRound();
        }
        Side winner = winner();
        log(new CombatEvent.End(round, winner));
        return new RunResult(round, winner);
    }

    public RunResult run() {
        return run(DEFAULT_MAX_ROUNDS);
    }

    private int distanceFt(Combatant a, Combatant b) {
        return GridMath.distanceFt(a.position(), b.position(), grid.cellFt());
    }

    // ---- the policy-facing API -----------------------------------------------------------------

    private final class Api implements TurnApi {
        private final Combatant self;
        private final TurnResources resources;

        Api(Combatant self, TurnResources resources) {
            this.self = self;
            this.resources = resources;
        }

        @Override
        public Combatant self() {
            return self;
        }

        @Override
        public TurnResources resources() {
            return resources;
        }

        @Override
        public List<Combatant> enemies() {
            return roster.consciousOpponents(self);
        }

        @Override
        public List<Combatant> allies() {
            return roster.consciousAllies(self);
        }

        @Override
        public List<Combatant> allAllies() {
            return roster.livingAllies(self);
        }

        @Override
        public boolean moveTo(Cell dest) {
            return Encounter.this.moveTo(self, dest, resources);
        }

        @Override
        public OptionalInt attack(Combatant target, AttackProfile profile) {
            return Encounter.this.attack(self, target, profile, resources);
        }

        @Override
        public OptionalInt castSpell(Spell spell, Combatant target, Integer slotLevel, boolean quickened) {
            return Encounter.this.castSpell(self, spell, target, slotLevel, resources, quickened);
        }

        @Override
        public OptionalInt layOnHands(Combatant target) {
            return Encounter.this.layOnHands(self, target, resources);
        }

        @Override
        public boolean markTarget(Combatant target) {
            return Encounter.this.markTarget(self, target, resources);
        }
    }

    /**
     * Place Hunter's Mark (Ranger): a Bonus Action that marks an enemy and starts concentration, spending one of the
     * ranger's free uses (Favored Enemy). The Hunter's Mark feature then adds its damage to hits on the target.
     */
    private boolean markTarget(Combatant self, Combatant target, TurnResources resources) {
        if (!resources.bonus || target.side() == self.side()) {
            return false;
        }
        if (self.resourceCount(ResourceIds.HUNTERS_MARK) <= 0) {
            return false;
        }
        self.spendResource(ResourceIds.HUNTERS_MARK, 1);
        self.setMarkedTarget(target.id());
        self.setConcentratingOn(ResourceIds.HUNTERS_MARK);
        resources.bonus = false;
        log(new CombatEvent.Marked(self.id(), target.id()));
        return true;
    }

    /** Lay on Hands: a Bonus Action that heals {@code target} from the 'lay-on-hands' pool. */
    private OptionalInt layOnHands(Combatant self, Combatant target, TurnResources resources) {
        if (!resources.bonus) {
            return OptionalInt.empty();
        }
        int pool = self.resourceCount(ResourceIds.LAY_ON_HANDS);
        if (pool <= 0 || !target.isAlive()) {
            return OptionalInt.empty();
        }
        int missing = Math.max(1, target.maxHp() - target.hp());
        int draw = Math.min(pool, missing);
        int healed = target.heal(draw);
        self.spendResource(ResourceIds.LAY_ON_HANDS, draw);
        resources.bonus = false;
        log(new CombatEvent.Heal(self.id(), target.id(), healed));
        return OptionalInt.of(healed);
    }

    // ---- spellcasting --------------------------------------------------------------------------

    /**
     * Resolve a spell cast. Cantrips cost the action only; leveled spells also spend a slot of {@code slotLevel}
     * (default the spell's own level). An attack-damage spell makes a spell attack per ray; a save-damage spell makes
     * the target (and, for an area spell, every enemy in radius of its cell) roll a save. Returns total damage, or
     * empty if the cast is illegal.
     */
    private OptionalInt castSpell(
            Combatant self, Spell spell, Combatant target, Integer slotLevelArg, TurnResources resources, boolean quickened) {
        // Action economy: a spell normally uses the action (bonus-action spells use the bonus). Quickened Spell
        // (Sorcerer Metamagic) casts it as a Bonus Action for 2 Sorcery Points instead.
        if (quickened) {
            if (!resources.bonus || self.resourceCount(ResourceIds.SORCERY) < QUICKEN_COST) {
                return OptionalInt.empty();
            }
        } else if (spell.action() == Spell.CastingTime.BONUS) {
            if (!resources.bonus) {
                return OptionalInt.empty();
            }
        } else if (!resources.action) {
            return OptionalInt.empty();
        }

        int slotLevel = spell.level() == 0 ? 0 : (slotLevelArg != null ? slotLevelArg : spell.level());
        if (spell.level() > 0 && slotLevel < spell.level()) {
            return OptionalInt.empty();
        }
        if (spell.level() > 0 && self.slotCount(slotLevel) <= 0) {
            return OptionalInt.empty();
        }

        // Range check against the primary target's cell.
        int dist = distanceFt(self, target);
        if (dist > spell.rangeFt()) {
            return OptionalInt.empty();
        }

        var dmgStream = rng.stream(self.id() + ":" + spell.id() + ":dmg");
        int totalDamage = 0;
        int totalHealing = 0;
        int targetsHit = 0;

        SpellKind kind = spell.kind();
        if (kind instanceof SpellKind.Heal heal) {
            // Target is an ally; restore HP (reviving if at 0).
            int mod = self.spellAbility() != null ? self.abilityMod(self.spellAbility()) : 0;
            int amount = heal.dice().at(slotLevel, self.level()).roll(dmgStream) + (heal.addSpellMod() ? mod : 0);
            totalHealing = target.heal(amount);
            targetsHit = 1;
        } else if (kind instanceof SpellKind.AttackDamage ad) {
            // Beam count: level-based (Eldritch Blast) or the upcast-ray path.
            int rays = ad.beams() != null
                    ? ad.beams().applyAsInt(self.level())
                    : Spell.raysAt(ad, slotLevel, Math.max(1, spell.level()));
            Dice damage = ad.damage().at(slotLevel, self.level());
            // Agonizing Blast adds the caster's spell modifier to each beam's damage.
            int perBeamBonus = ad.addSpellMod() && self.spellAbility() != null ? self.abilityMod(self.spellAbility()) : 0;
            for (int r = 0; r < rays; r++) {
                if (!target.isConscious()) {
                    break;
                }
                int buffToHit = rollBuffAttackBonus(self, spell.id() + ":" + target.id() + ":" + r);
                var atkStream = rng.stream(self.id() + ":" + spell.id() + ":" + target.id() + ":atk:" + r);
                AttackResult result = AttackResolver.resolveAttack(atkStream, AttackParams.of(
                                self.spellAttackBonus() + buffToHit, target.effectiveAc())
                        .withAdvantage(Conditions.attackAdvantage(self, target, dist <= 5)));
                if (result.hit()) {
                    int raw = damage.roll(dmgStream);
                    if (result.crit()) {
                        raw += damage.withoutBonus().roll(dmgStream);
                    }
                    raw += perBeamBonus;
                    int dealt = DamageMitigation.applyResponse(raw, target.damageResponseFor(ad.damageType()));
                    totalDamage += applySpellDamage(self, target, dealt);
                }
            }
            if (totalDamage > 0) {
                targetsHit = 1;
            }
        } else if (kind instanceof SpellKind.Control ctl) {
            // Save-or-condition: each target saves; on a failure the condition is applied for a duration,
            // repeating the save each turn to shake it off.
            List<Combatant> victims = ctl.aoeRadiusFt() != null
                    ? roster.consciousOpponentsWithin(self, target.position(), ctl.aoeRadiusFt())
                    : List.of(target);
            int dc = self.spellSaveDc();
            for (Combatant v : victims) {
                SaveResult save = AttackResolver.resolveSave(
                        rng.stream(self.id() + ":" + spell.id() + ":" + v.id() + SAVE_LABEL),
                        SaveParams.of(
                                v.saveBonus(ctl.save())
                                        + rollBuffSaveBonus(v, spell.id() + ":" + self.id())
                                        + auraSaveBonus(v),
                                dc));
                if (!save.success()) {
                    v.applyTimedCondition(new TimedConditionSpec(
                            ctl.condition(),
                            self.id(),
                            ctl.rounds(),
                            ctl.repeatSaveEndsEffect() ? new RepeatSave(ctl.save(), dc, true) : null,
                            spell.concentration() ? self.id() : null));
                    targetsHit++;
                }
            }
        } else if (kind instanceof SpellKind.Buff buff) {
            // Place a beneficial effect on up to maxTargets allies (the chosen target first, then any others in
            // range), refreshing rather than stacking.
            List<Combatant> chosen = new ArrayList<>();
            if (buffEligible(self, spell, target)) {
                chosen.add(target);
            }
            for (Combatant c : combatants) {
                if (c != target && buffEligible(self, spell, c)) {
                    chosen.add(c);
                }
            }
            if (chosen.size() > buff.maxTargets()) {
                chosen = chosen.subList(0, buff.maxTargets());
            }
            for (Combatant ally : chosen) {
                ally.applyBuff(new BuffSpec(
                        buff.buffId(),
                        self.id(),
                        buff.rounds(),
                        buff.attackBonusDice(),
                        buff.saveBonusDice(),
                        buff.acBonus(),
                        buff.extraAttackAction(),
                        spell.concentration() ? self.id() : null));
                log(new CombatEvent.BuffApplied(self.id(), buff.buffId(), ally.id()));
                targetsHit++;
            }
            // A buff with no valid recipient should not consume the slot or action.
            if (chosen.isEmpty()) {
                return OptionalInt.empty();
            }
        } else {
            SpellKind.SaveDamage sd = (SpellKind.SaveDamage) kind;
            // Save-damage: gather targets (area or single).
            List<Combatant> victims = sd.aoeRadiusFt() != null
                    ? roster.consciousOpponentsWithin(self, sd.selfOrigin() ? self.position() : target.position(), sd.aoeRadiusFt())
                    : List.of(target);
            Dice damage = sd.damage().at(slotLevel, self.level());
            int dc = self.spellSaveDc();
            // Area damage is rolled once and shared (2024 rule).
            int rolled = damage.roll(dmgStream);
            for (Combatant v : victims) {
                SaveResult save = AttackResolver.resolveSave(
                        rng.stream(self.id() + ":" + spell.id() + ":" + v.id() + SAVE_LABEL),
                        SaveParams.of(
                                v.saveBonus(sd.save())
                                        + rollBuffSaveBonus(v, spell.id() + ":" + self.id())
                                        + auraSaveBonus(v),
                                dc));
                int amount = rolled;
                if (save.success()) {
                    amount = sd.onSuccess() == SpellKind.OnSuccess.HALF ? rolled / 2 : 0;
                }
                int dealt = DamageMitigation.applyResponse(amount, v.damageResponseFor(sd.damageType()));
                if (dealt > 0) {
                    totalDamage += applySpellDamage(self, v, dealt);
                    targetsHit++;
                }
            }
        }

        // Spend resources.
        if (quickened) {
            resources.bonus = false;
            self.spendResource(ResourceIds.SORCERY, QUICKEN_COST);
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

        log(new CombatEvent.SpellCast(self.id(), spell.name(), slotLevel, targetsHit, totalDamage, totalHealing));
        return OptionalInt.of(totalDamage);
    }

    private boolean buffEligible(Combatant self, Spell spell, Combatant c) {
        return c.side() == self.side() && c != self && c.isConscious() && distanceFt(self, c) <= spell.rangeFt();
    }

    /** Apply spell damage to a target and log any down/death. */
    private int applySpellDamage(Combatant source, Combatant target, int dealt) {
        boolean before = target.isConscious();
        DamageOutcome outcome = target.takeDamage(dealt);
        if (before && outcome.dropped()) {
            log(new CombatEvent.Down(target.id()));
            fireOnKill(source, target);
        }
        if (outcome.died()) {
            log(new CombatEvent.Death(target.id()));
        }
        checkConcentration(target, dealt);
        return dealt;
    }

    /** Notify the killer's features that it dropped {@code victim} (Warlock Dark One's Blessing). */
    private void fireOnKill(Combatant killer, Combatant victim) {
        for (Feature f : killer.features()) {
            f.onKill(killer, victim);
        }
    }

    /**
     * A creature that takes damage while concentrating makes a Constitution save (DC 10 or half the damage, whichever
     * is higher). On a failure its concentration ends and every effect it was sustaining is removed.
     */
    private void checkConcentration(Combatant target, int dealt) {
        if (dealt <= 0 || target.concentratingOn() == null || !target.isConscious()) {
            return;
        }
        int dc = Math.max(10, dealt / 2);
        // The label uses the counter before it is incremented; the buff tag below sees the incremented value,
        // matching the TypeScript argument evaluation order.
        var stream = rng.stream(target.id() + ":conc:" + concSeq++);
        SaveResult save = AttackResolver.resolveSave(stream,
                SaveParams.of(
                        target.saveBonus(Ability.CON) + rollBuffSaveBonus(target, "conc:" + concSeq) + auraSaveBonus(target),
                        dc));
        if (!save.success()) {
            target.setConcentratingOn(null);
            target.setMarkedTarget(null); // Hunter's Mark drops with concentration
            for (Combatant c : combatants) {
                c.endConcentrationConditions(target.id());
                c.endConcentrationBuffs(target.id());
            }
            log(new CombatEvent.ConcentrationBroken(target.id()));
        }
    }

    // ---- movement ------------------------------------------------------------------------------

    /**
     * Straight-line move to {@code dest}. Cost is 5 ft per step, doubled for entering difficult terrain. Fails if any
     * cell on the path is impassable or the cost exceeds remaining movement. Opportunity attacks are resolved for
     * enemies the mover leaves the reach of (start-versus-end reach; a known simplification that ignores foes merely
     * passed through mid-path).
     */
    private boolean moveTo(Combatant self, Cell dest, TurnResources resources) {
        if (!grid.isPassable(dest)) {
            return false;
        }
        List<Cell> path = linePath(self.position(), dest);
        int cost = 0;
        for (int i = 1; i < path.size(); i++) {
            Cell step = path.get(i);
            if (!grid.isPassable(step)) {
                return false;
            }
            cost += grid.cellFt() * (grid.isDifficult(step) ? 2 : 1);
        }
        if (cost > resources.movementFt) {
            return false;
        }

        // Enemies who had the mover within reach before the move may take an opportunity attack.
        List<Combatant> provoked = new ArrayList<>();
        for (Combatant e : combatants) {
            if (e.side() != self.side()
                    && e.isConscious()
                    && Conditions.canReact(e)
                    && hasMeleeReach(e, self.position())
                    && !hasMeleeReach(e, dest)) {
                provoked.add(e);
            }
        }

        Cell from = self.position();
        self.setPosition(dest);
        resources.movementFt -= cost;
        log(new CombatEvent.Move(self.id(), from, dest, cost));

        for (Combatant e : provoked) {
            opportunityAttack(e, self);
        }
        return true;
    }

    /** An ally of {@code attacker} (not itself, not incapacitated) is within 5 ft of {@code target}. */
    private boolean hasAllyAdjacentTo(Combatant attacker, Combatant target) {
        for (Combatant c : combatants) {
            if (c != attacker
                    && c.side() == attacker.side()
                    && c.isConscious()
                    && !c.hasCondition(Condition.INCAPACITATED)
                    && distanceFt(c, target) <= 5) {
                return true;
            }
        }
        return false;
    }

    private boolean hasMeleeReach(Combatant attacker, Cell targetCell) {
        int reach = AttackProfile.DEFAULT_REACH_FT;
        for (AttackProfile a : attacker.attacks()) {
            if (a.kind() == AttackKind.MELEE) {
                reach = a.reachFtOrDefault();
                break;
            }
        }
        int d = GridMath.distanceFt(attacker.position(), targetCell, grid.cellFt());
        return d > 0 && d <= reach;
    }

    private void opportunityAttack(Combatant attacker, Combatant target) {
        AttackProfile profile = null;
        for (AttackProfile a : attacker.attacks()) {
            if (a.kind() == AttackKind.MELEE) {
                profile = a;
                break;
            }
        }
        if (profile == null) {
            return;
        }
        Integer dmg = resolveWeaponAttack(attacker, target, profile, AttackSource.OPPORTUNITY);
        log(new CombatEvent.Opportunity(attacker.id(), target.id(), dmg != null && dmg > 0, dmg == null ? 0 : dmg));
    }

    // ---- weapon attacks ------------------------------------------------------------------------

    private OptionalInt attack(Combatant self, Combatant target, AttackProfile profile, TurnResources resources) {
        if (!target.isConscious()) {
            return OptionalInt.empty();
        }
        // The first attack spends the Attack action and grants the Extra Attack(s); further attacks in the same
        // action draw from attacksRemaining. Once both are spent, a Haste-style extra action can fund one more single
        // weapon attack.
        boolean usingAction = resources.action;
        boolean usingExtra = !usingAction && resources.attacksRemaining <= 0 && resources.extraAttackActions > 0;
        if (!usingAction && resources.attacksRemaining <= 0 && !usingExtra) {
            return OptionalInt.empty();
        }

        Integer dmg = resolveWeaponAttack(self, target, profile, AttackSource.ACTION);
        if (dmg == null) {
            return OptionalInt.empty();
        }

        if (usingAction) {
            resources.action = false;
            resources.attacksRemaining = self.extraAttacks();
        } else if (usingExtra) {
            resources.extraAttackActions -= 1; // one attack only, no Extra Attack chain
            String source = self.buffSourceFor("haste");
            if (source != null) {
                log(new CombatEvent.BuffBoost(source, "haste", self.id(), 1));
            }
        } else {
            resources.attacksRemaining -= 1;
        }
        return OptionalInt.of(dmg);
    }

    /**
     * Roll the attacker's buff bonus to an attack roll (Bless's +1d4), logging the assist against the buff's caster.
     * {@code tag} keeps the stream distinct per attack.
     */
    private int rollBuffAttackBonus(Combatant self, String tag) {
        int bonus = 0;
        for (BuffBonus b : self.buffAttackBonuses()) {
            int rolled = b.dice().roll(rng.stream(self.id() + ":buff-atk:" + b.id() + ":" + tag));
            bonus += rolled;
            log(new CombatEvent.BuffBoost(b.source(), b.id(), self.id(), rolled));
        }
        return bonus;
    }

    /** Roll the defender's buff bonus to a saving throw (Bless's +1d4). */
    private int rollBuffSaveBonus(Combatant self, String tag) {
        int bonus = 0;
        for (BuffBonus b : self.buffSaveBonuses()) {
            bonus += b.dice().roll(rng.stream(self.id() + ":buff-save:" + b.id() + ":" + tag));
        }
        return bonus;
    }

    /**
     * Paladin Aura of Protection: a saving creature within 10 ft of a conscious allied paladin that has the aura adds
     * that paladin's Charisma modifier to the save. Auras do not stack, so the best nearby aura applies.
     */
    private int auraSaveBonus(Combatant target) {
        return auraSaveBonus(combatants, target, grid.cellFt());
    }

    /** Where a weapon attack came from; only {@code ACTION} attacks are logged as {@code Attack} events. */
    private enum AttackSource {
        ACTION,
        OPPORTUNITY
    }

    /**
     * Resolve one weapon attack: range/reach check, condition-derived advantage, the roll, auto-crit versus inert
     * targets, damage (dice doubled on a crit), mitigation and application. Returns damage dealt, or null if out of
     * range.
     */
    private Integer resolveWeaponAttack(Combatant self, Combatant target, AttackProfile profile, AttackSource source) {
        int dist = distanceFt(self, target);
        Advantage rangePenalty = Advantage.NORMAL;
        if (profile.kind() == AttackKind.MELEE) {
            if (dist > profile.reachFtOrDefault() || dist == 0) {
                return null;
            }
        } else {
            int normal = profile.rangeFt() != null ? profile.rangeFt() : 0;
            int lng = profile.rangeLongFt() != null ? profile.rangeLongFt() : normal;
            if (dist > lng) {
                return null;
            }
            if (dist > normal) {
                rangePenalty = Advantage.DISADVANTAGE;
            }
        }

        boolean within5 = dist <= 5;
        Advantage condAdv = Conditions.attackAdvantage(self, target, within5);

        // Feature-driven modifiers: the attacker's own features (Reckless Attack), the target's features that expose
        // it (Reckless grants attackers advantage), and any flat to-hit bonus.
        boolean featAdv = false;
        boolean featDis = false;
        int toHitBonus = 0;
        for (Feature f : self.features()) {
            OutgoingAttackMods mods = f.outgoingAttack(self, target, profile);
            if (mods.advantage()) {
                featAdv = true;
            }
            if (mods.disadvantage()) {
                featDis = true;
            }
            toHitBonus += mods.toHit();
        }
        for (Feature f : target.features()) {
            if (f.grantsAttackersAdvantage(target)) {
                featAdv = true;
            }
        }
        Advantage featureAdv = combineAdvantage(
                featAdv ? Advantage.ADVANTAGE : Advantage.NORMAL, featDis ? Advantage.DISADVANTAGE : Advantage.NORMAL);
        Advantage adv = combineAdvantage(combineAdvantage(condAdv, rangePenalty), featureAdv);

        int buffToHit = rollBuffAttackBonus(self, profile.name() + ":" + target.id());
        var stream = rng.stream(self.id() + ":" + profile.name() + ":" + target.id());
        AttackResult result = AttackResolver.resolveAttack(stream,
                new AttackParams(profile.attackBonus() + toHitBonus + buffToHit, target.effectiveAc(), adv,
                        profile.critRangeOrDefault()));

        if (!result.hit()) {
            if (source == AttackSource.ACTION) {
                log(new CombatEvent.Attack(self.id(), target.id(), profile.name(), result.d20(), false, false, 0));
            }
            return 0;
        }

        boolean crit = result.crit() || Conditions.isAutoCritTarget(target, within5);
        var dmgStream = rng.stream(self.id() + ":" + profile.name() + ":" + target.id() + ":dmg");

        // Primary damage, then each extra rider, each mitigated by its own type. A crit doubles the dice of every
        // component but never the flat bonuses.
        List<ExtraDamage> components = new ArrayList<>(profile.extraDamage());
        // Feature damage riders (Rage bonus, Sneak Attack dice) on a hit.
        OnHitContext onHitCtx =
                new OnHitContext(self, target, profile, crit, adv, hasAllyAdjacentTo(self, target));
        for (Feature f : self.features()) {
            components.addAll(f.onHit(onHitCtx));
        }

        int raw = profile.damage().roll(dmgStream);
        if (crit) {
            raw += profile.damage().withoutBonus().roll(dmgStream);
        }
        int dealt = DamageMitigation.applyResponse(raw, target.damageResponseFor(profile.damageType()));
        for (ExtraDamage extra : components) {
            int r = extra.damage().roll(dmgStream);
            if (crit) {
                r += extra.damage().withoutBonus().roll(dmgStream);
            }
            dealt += DamageMitigation.applyResponse(r, target.damageResponseFor(extra.type()));
        }

        boolean before = target.isConscious();
        DamageOutcome outcome = target.takeDamage(dealt, crit);

        if (source == AttackSource.ACTION) {
            log(new CombatEvent.Attack(self.id(), target.id(), profile.name(), result.d20(), true, crit, dealt));
        }
        if (before && outcome.dropped()) {
            log(new CombatEvent.Down(target.id()));
            fireOnKill(self, target);
        }
        if (outcome.died()) {
            log(new CombatEvent.Death(target.id()));
        }
        checkConcentration(target, dealt);

        // Save-or-condition riders on a hit (Monk Stunning Strike): each feature that triggers makes the target
        // save; on a failure the condition is applied and attributed to the attacker (feeding the control metric).
        if (target.isConscious()) {
            for (Feature f : self.features()) {
                var effect = f.onHitEffect(new OnHitContext(self, target, profile, crit, adv, hasAllyAdjacentTo(self, target)));
                if (effect.isEmpty()) {
                    continue;
                }
                HitEffect he = effect.get();
                SaveResult save = AttackResolver.resolveSave(
                        rng.stream(self.id() + ":" + f.id() + ":" + target.id() + SAVE_LABEL),
                        SaveParams.of(
                                target.saveBonus(he.save()) + rollBuffSaveBonus(target, f.id()) + auraSaveBonus(target),
                                he.dc()));
                if (!save.success()) {
                    target.applyTimedCondition(new TimedConditionSpec(he.condition(), self.id(), he.rounds(), null, null));
                }
            }
        }
        return dealt;
    }

    // ---- helpers -------------------------------------------------------------------------------

    /**
     * Paladin Aura of Protection: the bonus a saving creature gets from nearby allied paladins' auras - the best
     * (non-stacking) Charisma modifier among conscious aura-bearing allies within 10 ft of {@code target}. Pure.
     */
    public static int auraSaveBonus(List<Combatant> combatants, Combatant target, int cellFt) {
        int best = 0;
        for (Combatant p : combatants) {
            if (p.side() != target.side() || !p.isConscious()) {
                continue;
            }
            boolean hasAura = false;
            for (Feature f : p.features()) {
                if (f.id().equals("aura-of-protection")) {
                    hasAura = true;
                    break;
                }
            }
            if (!hasAura) {
                continue;
            }
            if (GridMath.distanceFt(p.position(), target.position(), cellFt) > 10) {
                continue;
            }
            best = Math.max(best, p.abilityMod(Ability.CHA));
        }
        return best;
    }

    /** A combatant's strongest attack by average damage (for legendary actions), or null. */
    private static AttackProfile bestAttack(Combatant c) {
        return Picks.firstMax(c.activeAttacks(), w -> w.damage().mean()).orElse(null);
    }

    /** Combine two advantage sources under the no-stacking rule. */
    private static Advantage combineAdvantage(Advantage a, Advantage b) {
        boolean adv = a == Advantage.ADVANTAGE || b == Advantage.ADVANTAGE;
        boolean dis = a == Advantage.DISADVANTAGE || b == Advantage.DISADVANTAGE;
        if (adv == dis) {
            return Advantage.NORMAL;
        }
        return adv ? Advantage.ADVANTAGE : Advantage.DISADVANTAGE;
    }

    /**
     * A grid path from {@code a} to {@code b} as a sequence of cells (inclusive of both), stepping one cell at a time
     * toward the destination (diagonals allowed). Length equals the Chebyshev distance plus one.
     */
    public static List<Cell> linePath(Cell a, Cell b) {
        List<Cell> path = new ArrayList<>();
        path.add(a);
        int x = a.x();
        int y = a.y();
        int steps = GridMath.stepDistance(a, b);
        for (int i = 0; i < steps; i++) {
            x += Integer.signum(b.x() - x);
            y += Integer.signum(b.y() - y);
            path.add(new Cell(x, y));
        }
        return path;
    }
}
