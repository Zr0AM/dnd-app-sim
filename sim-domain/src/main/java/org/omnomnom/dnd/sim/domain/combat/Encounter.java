package org.omnomnom.dnd.sim.domain.combat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.omnomnom.dnd.sim.domain.combat.event.CombatEvent;
import org.omnomnom.dnd.sim.domain.combat.event.EventSink;
import org.omnomnom.dnd.sim.domain.core.Picks;
import org.omnomnom.dnd.sim.domain.core.Side;
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
    private final TurnActions.Resolvers resolvers;
    private final Function<Combatant, TurnPolicy> policyFor;
    private int round;
    private List<Combatant> order = List.of();

    private Encounter(Builder b) {
        this.combatants = new ArrayList<>(b.combatants);
        this.ctx = new FightContext(b.grid, combatants, b.rng, b.sink);
        this.resolvers = TurnActions.Resolvers.create(ctx);
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
        Initiative.Result rolled = Initiative.roll(combatants, ctx.rng());
        this.order = rolled.order();
        ctx.log(rolled.event());
        return order;
    }

    /** Run one round: every combatant acts in initiative order. */
    public void runRound() {
        if (order.isEmpty()) {
            rollInitiative();
        }
        round += 1;
        ctx.log(new CombatEvent.Round(round));
        for (Combatant c : order) {
            if (isOver()) {
                break;
            }
            if (!c.dead()) {
                takeTurn(c);
            }
        }
    }

    private void takeTurn(Combatant c) {
        // A boss refreshes its legendary actions at the start of its own turn.
        if (c.legendaryMax() > 0) {
            c.refreshLegendary();
        }
        if (c.isDying()) {
            // Start of turn: a dying creature rolls a death save and does nothing else.
            rollDeathSave(c);
        } else {
            if (Conditions.canAct(c)) {
                act(c);
            } else {
                denyTurn(c);
            }
            endOfTurn(c);
        }
        takeLegendaryActions(c);
    }

    private void rollDeathSave(Combatant c) {
        if (c.stable()) {
            return;
        }
        DeathSaveOutcome out = c.rollDeathSave(ctx.rng().stream("death:" + c.id()));
        ctx.log(new CombatEvent.DeathSave(c.id(), out.d20(), out.success()));
        if (out.died()) {
            ctx.log(new CombatEvent.Death(c.id()));
        }
    }

    /** The creature's turn is denied; attribute it to whoever controls it. */
    private void denyTurn(Combatant c) {
        new LinkedHashSet<>(c.controlSources()).forEach(source -> ctx.log(new CombatEvent.ControlDenied(c.id(), source)));
    }

    /** A normal turn: start-of-turn feature hooks, then the creature's policy acts with fresh turn resources. */
    private void act(Combatant c) {
        // Start-of-turn feature hooks (reset per-turn state, auto-activate Rage, ...).
        for (Feature f : c.features()) {
            f.onTurnStart(c);
        }

        ctx.log(new CombatEvent.Turn(c.id(), round));
        int extraAttackActions = c.hasExtraAttackAction() ? 1 : 0;
        for (Feature f : c.features()) {
            extraAttackActions += f.bonusAttackActions(c);
        }
        TurnResources resources = new TurnResources(Conditions.effectiveSpeedFt(c), extraAttackActions);
        policyFor.apply(c).act(new TurnActions(c, resources, resolvers));
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
            if (boss != justActed && boss.isConscious() && boss.legendaryRemaining() > 0) {
                legendaryAttack(boss);
            }
        }
    }

    private void legendaryAttack(Combatant boss) {
        AttackProfile weapon = bestAttack(boss);
        if (weapon == null) {
            return;
        }
        Optional<Combatant> nearest = Picks.firstMax(ctx.roster().consciousOpponents(boss), t -> -ctx.distanceFt(boss, t));
        if (nearest.isEmpty() || ctx.distanceFt(boss, nearest.get()) > reachFt(weapon) || !boss.spendLegendary()) {
            return;
        }
        Combatant target = nearest.get();
        int dealt = resolvers.weapons().resolve(boss, target, weapon, WeaponAttackResolver.Source.OPPORTUNITY).orElse(0);
        ctx.log(new CombatEvent.Legendary(boss.id(), target.id(), dealt));
    }

    /** How far a weapon reaches: its melee reach, else its longest stated range, else the standard 5 ft. */
    private static int reachFt(AttackProfile weapon) {
        if (weapon.kind() == AttackKind.MELEE) {
            return weapon.reachFtOrDefault();
        }
        if (weapon.rangeLongFt() != null) {
            return weapon.rangeLongFt();
        }
        return weapon.rangeFt() != null ? weapon.rangeFt() : AttackProfile.DEFAULT_REACH_FT;
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
        ctx.log(new CombatEvent.End(round, winner));
        return new RunResult(round, winner);
    }

    public RunResult run() {
        return run(DEFAULT_MAX_ROUNDS);
    }

    /**
     * The bonus a saving creature gets from allied auras such as Aura of Protection - the best (non-stacking,
     * never below 0) save bonus any conscious ally's features grant at {@code target}'s distance. Pure.
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
