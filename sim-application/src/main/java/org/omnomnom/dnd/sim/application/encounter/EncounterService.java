package org.omnomnom.dnd.sim.application.encounter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.omnomnom.dnd.sim.application.content.ContentCatalogs;
import org.omnomnom.dnd.sim.application.error.UnprocessableException;
import org.omnomnom.dnd.sim.application.evaluation.GenomeResolver;
import org.omnomnom.dnd.sim.application.execution.SimLimits;
import org.omnomnom.dnd.sim.application.execution.SimulationExecutor;
import org.omnomnom.dnd.sim.domain.ai.TacticalPolicy;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.combat.Encounter;
import org.omnomnom.dnd.sim.domain.combat.event.EventLog;
import org.omnomnom.dnd.sim.domain.combat.event.EventSink;
import org.omnomnom.dnd.sim.domain.content.build.Role;
import org.omnomnom.dnd.sim.domain.content.monster.MonsterCompiler;
import org.omnomnom.dnd.sim.domain.content.monster.MonsterTemplate;
import org.omnomnom.dnd.sim.domain.core.Side;
import org.omnomnom.dnd.sim.domain.grid.Cell;
import org.omnomnom.dnd.sim.domain.grid.Grid;
import org.omnomnom.dnd.sim.domain.opt.evaluation.Stats;
import org.omnomnom.dnd.sim.domain.opt.genome.Genome;
import org.omnomnom.dnd.sim.domain.opt.genome.Genomes;
import org.omnomnom.dnd.sim.domain.rng.LabeledRandom;
import org.omnomnom.dnd.sim.domain.rng.Seeds;
import org.omnomnom.dnd.sim.domain.scenario.MapLayout;
import org.omnomnom.dnd.sim.domain.scenario.Maps;
import org.omnomnom.dnd.sim.domain.scenario.PartyScenarios;

/**
 * Simulates an encounter once or many times and reports aggregate statistics, optionally with one run's event log.
 *
 * <p>Run {@code i} is seeded from {@code (seed, scenarioKey, i)} where the key identifies the map and the enemies only,
 * never the party, so two requests with the same seed and enemies but different parties face identical enemy rolls
 * (common random numbers) and can be compared with far fewer runs.
 */
public final class EncounterService {

    /** Enemies are named {@code enemy-0}, {@code enemy-1}, ...; party ids may not take that prefix. */
    static final String ENEMY_ID_PREFIX = "enemy-";

    /** The request field validation errors name when the enemies are at fault. */
    private static final String ENEMIES_FIELD = "enemies";

    private final ContentCatalogs catalogs;
    private final SimulationExecutor executor;
    private final SimLimits limits;

    public EncounterService(ContentCatalogs catalogs, SimulationExecutor executor) {
        this(catalogs, executor, SimLimits.defaults());
    }

    public EncounterService(ContentCatalogs catalogs, SimulationExecutor executor, SimLimits limits) {
        this.catalogs = catalogs;
        this.executor = executor;
        this.limits = limits;
    }

    /** Where a fight happens and who the enemies are. */
    private record Arena(String mapId, Grid grid, List<Cell> partyCells, List<MonsterTemplate> plan, List<Cell> enemyCells) {

        String key() {
            StringBuilder sb = new StringBuilder(mapId);
            for (MonsterTemplate t : plan) {
                sb.append('|').append(t.slug());
            }
            return sb.toString();
        }
    }

    /** A party member prepared for repeated instantiation. */
    private record Member(String id, Genome genome, Role role) {}

    /** The aggregates of one party member or enemy slot across runs. */
    private static final class Acc {
        final String id;
        String name;
        final Side side;
        final Genome genome;
        double damage;
        double healing;
        double buffs;
        double denied;
        double hpFrac;
        int survived;

        Acc(String id, Side side, Genome genome) {
            this.id = id;
            this.side = side;
            this.genome = genome;
        }

        /** Add one fight's outcome for this member. */
        void record(Combatant c, CombatTally.Counts counts) {
            name = c.name();
            damage += counts.damage;
            healing += counts.healing;
            buffs += counts.buffAssists;
            denied += counts.denied;
            hpFrac += c.isConscious() ? (double) c.hp() / c.maxHp() : 0;
            if (!c.dead()) {
                survived++;
            }
        }

        EncounterResult.MemberStats averagedOver(int runs) {
            return new EncounterResult.MemberStats(id, name, side, genome, damage / runs, healing / runs, buffs / runs, denied / runs,
                    hpFrac / runs, (double) survived / runs);
        }
    }

    /** One fight: who took part, what they did, how it ended, and its event log if this run was asked for. */
    private record Fight(List<Combatant> combatants, CombatTally tally, Encounter.RunResult result, Optional<EventLog> events) {}

    /** Wins and round counts summed over the runs. */
    private static final class Totals {
        int wins;
        double rounds;
        double roundsEffective;

        void add(Encounter.RunResult res, int roundCap) {
            boolean won = res.winner() == Side.PARTY;
            if (won) {
                wins++;
            }
            rounds += res.rounds();
            roundsEffective += won ? res.rounds() : roundCap;
        }
    }

    public EncounterResult simulate(EncounterCommand cmd) {
        ContentCatalogs.LevelContent level = catalogs.level(cmd.level());
        SimLimits.require(cmd.runs(), limits.encounterRuns(), "runs", "runs per encounter");
        long seed = cmd.seed() != null ? cmd.seed() : Seeds.randomSeed();
        List<Member> party = resolveParty(cmd, level, seed);
        Arena arena = resolveArena(cmd, party.size(), level);
        if (party.size() > arena.partyCells().size()) {
            throw new UnprocessableException("over-capacity",
                    "map " + arena.mapId() + " holds " + arena.partyCells().size() + " party members, got " + party.size(), "party");
        }
        if (cmd.includeLog() && cmd.logRun() >= cmd.runs()) {
            throw new UnprocessableException("invalid-log-run", "logRun must be below runs", "logRun");
        }
        return executor.call(() -> run(cmd, level, seed, party, arena));
    }

    // ---- request resolution --------------------------------------------------------------------

    private List<Member> resolveParty(EncounterCommand cmd, ContentCatalogs.LevelContent level, long seed) {
        List<Member> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int builds = 0;
        for (int i = 0; i < cmd.party().size(); i++) {
            PartyMemberSpec spec = cmd.party().get(i);
            if (spec instanceof PartyMemberSpec.Build) {
                builds++;
            }
            Member member = memberFor(spec, i, builds, level, seed);
            requireUsableId(member.id(), i, ids);
            out.add(member);
        }
        return out;
    }

    /** The member a spec describes; {@code builds} is how many build members there are up to and including this one. */
    private static Member memberFor(PartyMemberSpec spec, int index, int builds, ContentCatalogs.LevelContent level, long seed) {
        return switch (spec) {
            case PartyMemberSpec.Build(var id, var genome) -> {
                Genome resolved = GenomeResolver.resolve(
                        genome, level.martial(), Seeds.seedFrom(seed, "member", index), partyField(index, "genome"));
                yield new Member(id != null ? id : defaultHeroId(builds), resolved, null);
            }
            case PartyMemberSpec.Filler(var id, var role) ->
                    new Member(id != null ? id : "ally-" + role.code() + "-" + index, null, role);
        };
    }

    /** The request path of a field of party member {@code index}, as reported in validation errors. */
    private static String partyField(int index, String field) {
        return "party[" + index + "]." + field;
    }

    private static String defaultHeroId(int builds) {
        return builds == 1 ? "hero" : "hero-" + builds;
    }

    private static void requireUsableId(String id, int index, Set<String> taken) {
        if (id.startsWith(ENEMY_ID_PREFIX)) {
            throw new UnprocessableException("reserved-id", "party member ids may not start with '" + ENEMY_ID_PREFIX
                    + "', which names the enemies", partyField(index, "id"));
        }
        if (!taken.add(id)) {
            throw new UnprocessableException("duplicate-id", "duplicate party member id: " + id, partyField(index, "id"));
        }
    }

    private Arena resolveArena(EncounterCommand cmd, int partySize, ContentCatalogs.LevelContent level) {
        boolean hasEnemies = cmd.enemies() != null && !cmd.enemies().isEmpty();
        if (hasEnemies == (cmd.scenarioId() != null)) {
            throw new UnprocessableException("invalid-enemy-spec", "give exactly one of enemies or scenarioId", ENEMIES_FIELD);
        }
        if (cmd.scenarioId() != null) {
            return libraryArena(cmd, partySize, level);
        }
        MapLayout map = mapFor(cmd.mapId(), partySize);
        List<MonsterTemplate> plan = cmd.enemies().stream()
                .flatMap(g -> Collections.nCopies(Math.max(0, g.count()), templateFor(g)).stream())
                .toList();
        if (plan.size() > map.enemyStarts().size()) {
            throw new UnprocessableException("over-capacity",
                    "map " + map.id() + " holds " + map.enemyStarts().size() + " enemies, got " + plan.size(), ENEMIES_FIELD);
        }
        return new Arena(map.id(), map.grid(), map.partyStarts(), plan, map.enemyStarts().subList(0, plan.size()));
    }

    /** The map asked for, else the open field for a lone hero and the party field for a group. */
    private static MapLayout mapFor(String mapId, int partySize) {
        if (mapId != null) {
            return mapById(mapId);
        }
        return partySize == 1 ? Maps.OPEN_FIELD : Maps.PARTY_FIELD;
    }

    private MonsterTemplate templateFor(EncounterCommand.EnemyGroup group) {
        MonsterTemplate template = catalogs.monsters().find(group.monsterSlug());
        if (template == null) {
            throw new UnprocessableException("unknown-monster", "unknown monster: " + group.monsterSlug(), ENEMIES_FIELD);
        }
        if (template.attacks().isEmpty()) {
            throw new UnprocessableException("monster-has-no-attacks", group.monsterSlug() + " has no usable attack", ENEMIES_FIELD);
        }
        return template;
    }

    private static MapLayout mapById(String id) {
        try {
            return Maps.byId(id);
        } catch (IllegalArgumentException e) {
            throw new UnprocessableException("unknown-map", "unknown map: " + id, "map");
        }
    }

    /** A scenario from the party library, else from the hero's solo library, else unknown. */
    private Arena libraryArena(EncounterCommand cmd, int partySize, ContentCatalogs.LevelContent level) {
        String wanted = cmd.scenarioId();
        Optional<Arena> party = PartyScenarios.load(catalogs.monsters(), partySize, cmd.level()).stream()
                .filter(s -> s.id().equals(wanted))
                .findFirst()
                .map(s -> {
                    requireMap(cmd.mapId(), Maps.PARTY_FIELD.id());
                    return new Arena(Maps.PARTY_FIELD.id(), s.grid(), s.partyCells(), s.plan(), s.enemyCells());
                });
        return party.or(() -> soloLibraryArena(cmd, level)).orElseThrow(() ->
                new UnprocessableException("unknown-scenario", "unknown scenario at level " + cmd.level() + ": " + wanted, "scenarioId"));
    }

    private static Optional<Arena> soloLibraryArena(EncounterCommand cmd, ContentCatalogs.LevelContent level) {
        return level.martial().scenarios().stream()
                .filter(s -> s.id().equals(cmd.scenarioId()))
                .findFirst()
                .map(s -> {
                    requireMap(cmd.mapId(), s.mapId());
                    return new Arena(s.mapId(), s.grid(), Maps.byId(s.mapId()).partyStarts(), s.plan(), s.enemyCells());
                });
    }

    private static void requireMap(String requested, String actual) {
        if (requested != null && !requested.equals(actual)) {
            throw new UnprocessableException("map-mismatch", "scenario is played on " + actual + ", not " + requested, "map");
        }
    }

    // ---- the simulation ------------------------------------------------------------------------

    private EncounterResult run(EncounterCommand cmd, ContentCatalogs.LevelContent level, long seed, List<Member> party, Arena arena) {
        Map<String, Acc> accs = accumulators(party, arena);
        Totals totals = new Totals();
        EncounterResult.CombatLog log = null;
        for (int i = 0; i < cmd.runs(); i++) {
            Fight fight = fight(cmd, level, seed, party, arena, i);
            totals.add(fight.result(), cmd.roundCap());
            for (Combatant c : fight.combatants()) {
                accs.get(c.id()).record(c, fight.tally().counts(c.id()));
            }
            int run = i;
            Encounter.RunResult res = fight.result();
            log = fight.events()
                    .map(events -> new EncounterResult.CombatLog(run, res.rounds(), res.winner(), events.events()))
                    .orElse(log);
        }

        int runs = cmd.runs();
        List<EncounterResult.MemberStats> members = accs.values().stream().map(a -> a.averagedOver(runs)).toList();
        return new EncounterResult(seed, cmd.level(), arena.mapId(), runs, cmd.roundCap(), Stats.wilsonInterval(totals.wins, runs),
                totals.rounds / runs, totals.roundsEffective / runs, members, log);
    }

    /** One accumulator per party member, then per enemy slot, in that order. */
    private static Map<String, Acc> accumulators(List<Member> party, Arena arena) {
        Map<String, Acc> accs = new LinkedHashMap<>();
        party.forEach(m -> accs.put(m.id(), new Acc(m.id(), Side.PARTY, m.genome())));
        for (int n = 0; n < arena.plan().size(); n++) {
            accs.put(ENEMY_ID_PREFIX + n, new Acc(ENEMY_ID_PREFIX + n, Side.ENEMY, null));
        }
        return accs;
    }

    private static Fight fight(
            EncounterCommand cmd, ContentCatalogs.LevelContent level, long seed, List<Member> party, Arena arena, int runIndex) {
        List<Combatant> combatants = spawn(party, arena, level);
        CombatTally tally = new CombatTally();
        Optional<EventLog> events = cmd.includeLog() && runIndex == cmd.logRun() ? Optional.of(new EventLog()) : Optional.empty();
        EventSink sink = events.<EventSink>map(log -> e -> {
            tally.accept(e);
            log.accept(e);
        }).orElse(tally);
        Encounter.RunResult res = Encounter.builder(
                        arena.grid(), combatants, new LabeledRandom(Seeds.seedFrom("encounter", seed, arena.key(), runIndex)))
                .policyFor(c -> TacticalPolicy.DEFAULT)
                .sink(sink)
                .build()
                .run(cmd.roundCap());
        return new Fight(combatants, tally, res, events);
    }

    /** Fresh combatants for one fight: the party in order, then the enemies. */
    private static List<Combatant> spawn(List<Member> party, Arena arena, ContentCatalogs.LevelContent level) {
        List<Combatant> combatants = new ArrayList<>();
        for (int p = 0; p < party.size(); p++) {
            combatants.add(instantiate(party.get(p), level, arena.partyCells().get(p)));
        }
        for (int n = 0; n < arena.plan().size(); n++) {
            combatants.add(MonsterCompiler.spawn(arena.plan().get(n),
                    new MonsterCompiler.Placement(ENEMY_ID_PREFIX + n, Side.ENEMY, arena.enemyCells().get(n))));
        }
        return combatants;
    }

    private static Combatant instantiate(Member m, ContentCatalogs.LevelContent level, Cell position) {
        if (m.genome() != null) {
            Combatant hero = Genomes.build(m.genome(), level.martial(), m.id());
            hero.setPosition(position);
            return hero;
        }
        return level.fillers().get(m.role()).make(m.id(), Side.PARTY, position);
    }
}
