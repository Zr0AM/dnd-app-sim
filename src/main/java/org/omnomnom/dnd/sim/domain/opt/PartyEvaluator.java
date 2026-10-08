package org.omnomnom.dnd.sim.domain.opt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.omnomnom.dnd.sim.domain.ai.TacticalPolicy;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.combat.Encounter;
import org.omnomnom.dnd.sim.domain.combat.Side;
import org.omnomnom.dnd.sim.domain.content.Fillers;
import org.omnomnom.dnd.sim.domain.content.MonsterCatalog;
import org.omnomnom.dnd.sim.domain.content.Role;
import org.omnomnom.dnd.sim.domain.rng.LabeledRandom;
import org.omnomnom.dnd.sim.domain.rng.Seeds;
import org.omnomnom.dnd.sim.domain.scenario.PartyScenario;
import org.omnomnom.dnd.sim.domain.scenario.PartyScenarios;
import org.omnomnom.dnd.sim.domain.scenario.PartyTemplate;

/**
 * Runs a hero inside the reference parties against party-scaled encounters, under common random numbers, and
 * attributes metrics to the hero, including the support signal (healing plus buff assists) and control that a solo
 * evaluation cannot produce. Aggregated across the party templates (R6, R4, R3).
 */
public final class PartyEvaluator {

    public static final int DEFAULT_RUNS_PER_SCENARIO = 12;
    public static final int ROUND_CAP = 50;

    private PartyEvaluator() {}

    /** The harness assets for one hero level, built once and immutable afterwards. */
    public record Harness(Map<Role, Fillers.Filler> fillers, Map<Integer, List<PartyScenario>> scenariosByPartySize) {

        public Harness {
            fillers = Map.copyOf(fillers);
            scenariosByPartySize = Map.copyOf(scenariosByPartySize);
        }

        public static Harness load(Map<Role, Fillers.Filler> fillers, MonsterCatalog monsters, int level) {
            Map<Integer, List<PartyScenario>> bySize = new java.util.HashMap<>();
            for (PartyTemplate t : PartyTemplate.ALL) {
                bySize.computeIfAbsent(t.roles().size(), size -> PartyScenarios.load(monsters, size, level));
            }
            return new Harness(fillers, bySize);
        }
    }

    public static PartyEvalResult evaluate(Function<String, Combatant> heroFactory, Harness harness) {
        return evaluate(heroFactory, harness, Role.CONTROLLER, PartyTemplate.ALL, DEFAULT_RUNS_PER_SCENARIO);
    }

    public static PartyEvalResult evaluate(
            Function<String, Combatant> heroFactory, Harness harness, Role heroRole, List<PartyTemplate> templates, int runsPerScenario) {
        List<Double> wins = new ArrayList<>();
        List<Double> damage = new ArrayList<>();
        List<Double> healing = new ArrayList<>();
        List<Double> assists = new ArrayList<>();
        List<Double> denied = new ArrayList<>();
        List<Double> heroHp = new ArrayList<>();
        List<Double> roundsEffective = new ArrayList<>();
        List<Double> alliesAlive = new ArrayList<>();

        for (PartyTemplate template : templates) {
            List<PartyScenario> scenarios = harness.scenariosByPartySize().getOrDefault(template.roles().size(), List.of());
            for (PartyScenario scenario : scenarios) {
                for (int i = 0; i < runsPerScenario; i++) {
                    LabeledRandom rng = new LabeledRandom(Seeds.seedFrom("party", template.id(), scenario.id(), i));
                    Combatant hero = heroFactory.apply(SoloEvaluator.HERO_ID);
                    List<Combatant> party = PartyScenarios.assemble(harness.fillers(), template, hero, heroRole, scenario.partyCells());
                    List<Combatant> all = new ArrayList<>(party);
                    all.addAll(scenario.spawnEnemies());
                    HeroTally tally = new HeroTally(SoloEvaluator.HERO_ID);
                    Encounter.RunResult res = Encounter.builder(scenario.grid(), all, rng)
                            .policyFor(c -> TacticalPolicy.DEFAULT)
                            .sink(tally)
                            .build()
                            .run(ROUND_CAP);
                    boolean won = res.winner() == Side.PARTY;
                    wins.add(won ? 1.0 : 0.0);
                    damage.add(tally.damage());
                    healing.add(tally.healing());
                    assists.add((double) tally.buffAssists());
                    denied.add((double) tally.actionsDenied());
                    heroHp.add(hero.isConscious() ? (double) hero.hp() / hero.maxHp() : 0);
                    roundsEffective.add(won ? (double) res.rounds() : ROUND_CAP);
                    int allies = 0;
                    int alive = 0;
                    for (Combatant c : party) {
                        if (c != hero) {
                            allies++;
                            if (c.isAlive()) {
                                alive++;
                            }
                        }
                    }
                    alliesAlive.add(allies > 0 ? (double) alive / allies : 1.0);
                }
            }
        }

        int runs = wins.size();
        int winCount = (int) wins.stream().mapToDouble(Double::doubleValue).sum();
        double avgHealing = Stats.mean(healing);
        double avgAssists = Stats.mean(assists);
        return new PartyEvalResult(
                runs > 0 ? (double) winCount / runs : 0,
                Stats.mean(damage),
                avgHealing,
                avgAssists,
                avgHealing + avgAssists,
                Stats.mean(denied),
                Stats.mean(heroHp),
                Stats.mean(roundsEffective),
                Stats.mean(alliesAlive),
                runs,
                Stats.wilsonInterval(winCount, runs));
    }
}
