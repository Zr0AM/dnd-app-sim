package org.omnomnom.dnd.sim.domain.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Function;
import org.omnomnom.dnd.sim.domain.combat.event.CombatEvent;
import org.omnomnom.dnd.sim.domain.combat.event.EventSink;
import org.omnomnom.dnd.sim.domain.combat.spell.Spell;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.Condition;
import org.omnomnom.dnd.sim.domain.core.Picks;
import org.omnomnom.dnd.sim.domain.core.Side;
import org.omnomnom.dnd.sim.domain.dice.Advantage;
import org.omnomnom.dnd.sim.domain.dice.Dice;
import org.omnomnom.dnd.sim.domain.grid.Cell;
import org.omnomnom.dnd.sim.domain.grid.Grid;
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

    /** Default round cap for {@link #run()}. */
    public static final int DEFAULT_MAX_ROUNDS = 100;

    private final List<Combatant> combatants;
    private final FightContext ctx;
    private final Rolls rolls;
    private final DamageApplier applier;
    private final WeaponAttackResolver weapons;
    private final MovementResolver movement;
    private final SpellResolver spells;
    private final Function<Combatant, TurnPolicy> policyFor;
    private int round;
    private List<Combatant> order = List.of();

    private Encounter(Builder b) {
        this.combatants = new ArrayList<>(b.combatants);
        this.ctx = new FightContext(b.grid, combatants, b.rng, b.sink);
        this.rolls = new Rolls(ctx);
        this.applier = new DamageApplier(ctx, rolls);
        this.weapons = new WeaponAttackResolver(ctx, rolls, applier);
        this.movement = new MovementResolver(ctx, weapons);
        this.spells = new SpellResolver(ctx, rolls, applier);
        this.policyFor = b.policyFor;
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
        return ctx.grid();
    }

    public List<Combatant> combatants() {
        return combatants;
    }

    public int round() {
        return round;
    }

    private void log(CombatEvent event) {
        ctx.log(event);
    }

    /** The fight is over when at most one side still has a conscious combatant. */
    public boolean isOver() {
        return !ctx.roster().anyConscious(Side.PARTY) || !ctx.roster().anyConscious(Side.ENEMY);
    }

    /** The winning side, or null if both or neither side has a conscious combatant. */
    public Side winner() {
        boolean party = ctx.roster().anyConscious(Side.PARTY);
        boolean enemy = ctx.roster().anyConscious(Side.ENEMY);
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
            int total = Dice.rollD20(ctx.rng().stream("initiative:" + c.id()), adv) + c.abilityMod(Ability.DEX);
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
                    DeathSaveOutcome out = c.rollDeathSave(ctx.rng().stream("death:" + c.id()));
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
        c.tickTimedConditions(ctx.rng().stream("tick:" + c.id() + ":" + round));
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
            int dealt = weapons.resolve(boss, target, weapon, WeaponAttackResolver.Source.OPPORTUNITY).orElse(0);
            log(new CombatEvent.Legendary(boss.id(), target.id(), dealt));
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
        return ctx.distanceFt(a, b);
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
            return ctx.roster().consciousOpponents(self);
        }

        @Override
        public List<Combatant> allies() {
            return ctx.roster().consciousAllies(self);
        }

        @Override
        public List<Combatant> allAllies() {
            return ctx.roster().livingAllies(self);
        }

        @Override
        public boolean moveTo(Cell dest) {
            return movement.moveTo(self, dest, resources);
        }

        @Override
        public OptionalInt attack(Combatant target, AttackProfile profile) {
            return Encounter.this.attack(self, target, profile, resources);
        }

        @Override
        public OptionalInt castSpell(Spell spell, Combatant target, Integer slotLevel, boolean quickened) {
            return spells.cast(self, spell, target, slotLevel, resources, quickened);
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

        OptionalInt dmg = weapons.resolve(self, target, profile, WeaponAttackResolver.Source.ACTION);
        if (dmg.isEmpty()) {
            return dmg;
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
        return dmg;
    }

    // ---- helpers -------------------------------------------------------------------------------

    /**
     * Paladin Aura of Protection: the bonus a saving creature gets from nearby allied paladins' auras - the best
     * (non-stacking) Charisma modifier among conscious aura-bearing allies within 10 ft of {@code target}. Pure.
     */
    public static int auraSaveBonus(List<Combatant> combatants, Combatant target, int cellFt) {
        return new Roster(combatants, cellFt).auraSaveBonus(target);
    }

    /** A combatant's strongest attack by average damage (for legendary actions), or null. */
    private static AttackProfile bestAttack(Combatant c) {
        return Picks.firstMax(c.activeAttacks(), w -> w.damage().mean()).orElse(null);
    }

    /**
     * A grid path from {@code a} to {@code b} as a sequence of cells (inclusive of both), stepping one cell at a time
     * toward the destination (diagonals allowed). Length equals the Chebyshev distance plus one.
     */
    public static List<Cell> linePath(Cell a, Cell b) {
        return MovementResolver.linePath(a, b);
    }
}
