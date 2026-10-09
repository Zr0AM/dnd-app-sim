package org.omnomnom.dnd.sim.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.omnomnom.dnd.sim.domain.ai.TacticalPolicy;
import org.omnomnom.dnd.sim.domain.combat.CombatEvent;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.combat.Encounter;
import org.omnomnom.dnd.sim.domain.combat.EventLog;
import org.omnomnom.dnd.sim.domain.combat.EventSink;
import org.omnomnom.dnd.sim.domain.combat.Side;
import org.omnomnom.dnd.sim.domain.content.MonsterCompiler;
import org.omnomnom.dnd.sim.domain.content.MonsterTemplate;
import org.omnomnom.dnd.sim.domain.content.Role;
import org.omnomnom.dnd.sim.domain.grid.Cell;
import org.omnomnom.dnd.sim.domain.grid.Grid;
import org.omnomnom.dnd.sim.domain.opt.Genome;
import org.omnomnom.dnd.sim.domain.opt.Genomes;
import org.omnomnom.dnd.sim.domain.opt.Stats;
import org.omnomnom.dnd.sim.domain.rng.LabeledRandom;
import org.omnomnom.dnd.sim.domain.rng.Seeds;
import org.omnomnom.dnd.sim.domain.scenario.Maps;
import org.omnomnom.dnd.sim.domain.scenario.MapLayout;
import org.omnomnom.dnd.sim.domain.scenario.PartyScenario;
import org.omnomnom.dnd.sim.domain.scenario.PartyScenarios;
import org.omnomnom.dnd.sim.domain.scenario.Scenario;

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
            String id;
            Member member;
            switch (spec) {
                case PartyMemberSpec.Build b -> {
                    builds++;
                    id = b.id() != null ? b.id() : builds == 1 ? "hero" : "hero-" + builds;
                    Genome g = GenomeResolver.resolve(b.genome(), level.martial(), Seeds.seedFrom(seed, "member", i), "party[" + i + "].genome");
                    member = new Member(id, g, null);
                }
                case PartyMemberSpec.Filler f -> {
                    id = f.id() != null ? f.id() : "ally-" + f.role().code() + "-" + i;
                    member = new Member(id, null, f.role());
                }
            }
            if (id.startsWith(ENEMY_ID_PREFIX)) {
                throw new UnprocessableException("reserved-id", "party member ids may not start with '" + ENEMY_ID_PREFIX
                        + "', which names the enemies", "party[" + i + "].id");
            }
            if (!ids.add(id)) {
                throw new UnprocessableException("duplicate-id", "duplicate party member id: " + id, "party[" + i + "].id");
            }
            out.add(member);
        }
        return out;
    }

    private Arena resolveArena(EncounterCommand cmd, int partySize, ContentCatalogs.LevelContent level) {
        boolean hasEnemies = cmd.enemies() != null && !cmd.enemies().isEmpty();
        if (hasEnemies == (cmd.scenarioId() != null)) {
            throw new UnprocessableException("invalid-enemy-spec", "give exactly one of enemies or scenarioId", "enemies");
        }
        if (cmd.scenarioId() != null) {
            return libraryArena(cmd, partySize, level);
        }
        MapLayout map = cmd.mapId() != null ? mapById(cmd.mapId()) : partySize == 1 ? Maps.OPEN_FIELD : Maps.PARTY_FIELD;
        List<MonsterTemplate> plan = new ArrayList<>();
        for (EncounterCommand.EnemyGroup g : cmd.enemies()) {
            MonsterTemplate t = catalogs.monsters().find(g.monsterSlug());
            if (t == null) {
                throw new UnprocessableException("unknown-monster", "unknown monster: " + g.monsterSlug(), "enemies");
            }
            if (t.attacks().isEmpty()) {
                throw new UnprocessableException("monster-has-no-attacks", g.monsterSlug() + " has no usable attack", "enemies");
            }
            for (int i = 0; i < g.count(); i++) {
                plan.add(t);
            }
        }
        if (plan.size() > map.enemyStarts().size()) {
            throw new UnprocessableException("over-capacity",
                    "map " + map.id() + " holds " + map.enemyStarts().size() + " enemies, got " + plan.size(), "enemies");
        }
        return new Arena(map.id(), map.grid(), map.partyStarts(), plan, map.enemyStarts().subList(0, plan.size()));
    }

    private static MapLayout mapById(String id) {
        try {
            return Maps.byId(id);
        } catch (IllegalArgumentException e) {
            throw new UnprocessableException("unknown-map", "unknown map: " + id, "map");
        }
    }

    private Arena libraryArena(EncounterCommand cmd, int partySize, ContentCatalogs.LevelContent level) {
        for (PartyScenario s : PartyScenarios.load(catalogs.monsters(), partySize, cmd.level())) {
            if (s.id().equals(cmd.scenarioId())) {
                requireMap(cmd.mapId(), Maps.PARTY_FIELD.id());
                return new Arena(Maps.PARTY_FIELD.id(), s.grid(), s.partyCells(), s.plan(), s.enemyCells());
            }
        }
        for (Scenario s : level.martial().scenarios()) {
            if (s.id().equals(cmd.scenarioId())) {
                requireMap(cmd.mapId(), s.mapId());
                return new Arena(s.mapId(), s.grid(), Maps.byId(s.mapId()).partyStarts(), s.plan(), s.enemyCells());
            }
        }
        throw new UnprocessableException("unknown-scenario", "unknown scenario at level " + cmd.level() + ": " + cmd.scenarioId(), "scenarioId");
    }

    private static void requireMap(String requested, String actual) {
        if (requested != null && !requested.equals(actual)) {
            throw new UnprocessableException("map-mismatch", "scenario is played on " + actual + ", not " + requested, "map");
        }
    }

    // ---- the simulation ------------------------------------------------------------------------

    private EncounterResult run(EncounterCommand cmd, ContentCatalogs.LevelContent level, long seed, List<Member> party, Arena arena) {
        String key = arena.key();
        Map<String, Acc> accs = new LinkedHashMap<>();
        for (Member m : party) {
            accs.put(m.id(), new Acc(m.id(), Side.PARTY, m.genome()));
        }
        for (int n = 0; n < arena.plan().size(); n++) {
            accs.put(ENEMY_ID_PREFIX + n, new Acc(ENEMY_ID_PREFIX + n, Side.ENEMY, null));
        }

        int wins = 0;
        double rounds = 0;
        double roundsEffective = 0;
        EncounterResult.CombatLog log = null;
        for (int i = 0; i < cmd.runs(); i++) {
            List<Combatant> combatants = new ArrayList<>();
            for (int p = 0; p < party.size(); p++) {
                combatants.add(instantiate(party.get(p), level, arena.partyCells().get(p)));
            }
            for (int n = 0; n < arena.plan().size(); n++) {
                combatants.add(MonsterCompiler.spawn(arena.plan().get(n),
                        new MonsterCompiler.Placement(ENEMY_ID_PREFIX + n, Side.ENEMY, arena.enemyCells().get(n))));
            }
            CombatTally tally = new CombatTally();
            EventLog events = cmd.includeLog() && i == cmd.logRun() ? new EventLog() : null;
            EventSink sink = events == null ? tally : e -> {
                tally.accept(e);
                events.accept(e);
            };
            Encounter.RunResult res = Encounter.builder(arena.grid(), combatants, new LabeledRandom(Seeds.seedFrom("encounter", seed, key, i)))
                    .policyFor(c -> TacticalPolicy.DEFAULT)
                    .sink(sink)
                    .build()
                    .run(cmd.roundCap());
            boolean won = res.winner() == Side.PARTY;
            if (won) {
                wins++;
            }
            rounds += res.rounds();
            roundsEffective += won ? res.rounds() : cmd.roundCap();
            for (Combatant c : combatants) {
                Acc a = accs.get(c.id());
                CombatTally.Counts counts = tally.counts(c.id());
                a.name = c.name();
                a.damage += counts.damage;
                a.healing += counts.healing;
                a.buffs += counts.buffAssists;
                a.denied += counts.denied;
                a.hpFrac += c.isConscious() ? (double) c.hp() / c.maxHp() : 0;
                if (!c.dead()) {
                    a.survived++;
                }
            }
            if (events != null) {
                List<CombatEvent> list = events.events();
                log = new EncounterResult.CombatLog(i, res.rounds(), res.winner(), list);
            }
        }

        int runs = cmd.runs();
        List<EncounterResult.MemberStats> members = new ArrayList<>();
        for (Acc a : accs.values()) {
            members.add(new EncounterResult.MemberStats(a.id, a.name, a.side, a.genome, a.damage / runs, a.healing / runs, a.buffs / runs,
                    a.denied / runs, a.hpFrac / runs, (double) a.survived / runs));
        }
        return new EncounterResult(seed, cmd.level(), arena.mapId(), runs, cmd.roundCap(), Stats.wilsonInterval(wins, runs),
                rounds / runs, roundsEffective / runs, members, log);
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
