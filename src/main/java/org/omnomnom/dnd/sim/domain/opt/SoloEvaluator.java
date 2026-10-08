package org.omnomnom.dnd.sim.domain.opt;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.omnomnom.dnd.sim.domain.ai.TacticalPolicy;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.combat.Encounter;
import org.omnomnom.dnd.sim.domain.combat.Side;
import org.omnomnom.dnd.sim.domain.rng.LabeledRandom;
import org.omnomnom.dnd.sim.domain.rng.Seeds;
import org.omnomnom.dnd.sim.domain.scenario.Scenario;

/**
 * Evaluates a hero by simulating it across the solo scenario library several times per scenario, under common random
 * numbers, and reducing the outcomes to a multi-objective vector and a scalar fitness, each with a confidence
 * interval. The seeds depend only on (scenario, run index), never the hero, so every build faces identical enemy rolls
 * and initiative: the paired comparison that makes few samples enough. Running across the whole library stops a build
 * from overfitting one encounter.
 *
 * <p>Stateless and thread-safe: each run builds fresh combatants from the supplied factory.
 */
public final class SoloEvaluator {

    public static final String HERO_ID = "hero";
    public static final int DEFAULT_RUNS_PER_SCENARIO = 16;
    public static final int ROUND_CAP = 50;

    private SoloEvaluator() {}

    public static EvalResult evaluate(Function<String, Combatant> heroFactory, List<Scenario> scenarios) {
        return evaluate(heroFactory, scenarios, DEFAULT_RUNS_PER_SCENARIO);
    }

    /**
     * @param heroFactory builds a fresh hero with the given id for each run
     * @param runsPerScenario total runs are this times the scenario count
     */
    public static EvalResult evaluate(Function<String, Combatant> heroFactory, List<Scenario> scenarios, int runsPerScenario) {
        List<Double> wins = new ArrayList<>();
        List<Double> hpOnWin = new ArrayList<>();
        List<Double> hpRetained = new ArrayList<>();
        List<Double> damage = new ArrayList<>();
        List<Double> rounds = new ArrayList<>();
        List<Double> roundsEffective = new ArrayList<>();
        List<Double> denied = new ArrayList<>();

        for (Scenario scenario : scenarios) {
            for (int i = 0; i < runsPerScenario; i++) {
                // CRN: the seed depends only on the scenario id and run index.
                LabeledRandom rng = new LabeledRandom(Seeds.seedFrom(scenario.id(), i));
                Combatant hero = heroFactory.apply(HERO_ID);
                hero.setPosition(scenario.heroStart());
                List<Combatant> all = new ArrayList<>();
                all.add(hero);
                all.addAll(scenario.spawnEnemies());
                HeroTally tally = new HeroTally(HERO_ID);
                Encounter.RunResult res = Encounter.builder(scenario.grid(), all, rng)
                        .policyFor(c -> TacticalPolicy.DEFAULT)
                        .sink(tally)
                        .build()
                        .run(ROUND_CAP);
                double retained = hero.isConscious() ? (double) hero.hp() / hero.maxHp() : 0;
                boolean won = res.winner() == Side.PARTY && hero.isConscious();
                wins.add(won ? 1.0 : 0.0);
                hpRetained.add(retained);
                damage.add(tally.damage());
                rounds.add((double) res.rounds());
                roundsEffective.add(won ? (double) res.rounds() : ROUND_CAP);
                denied.add((double) tally.actionsDenied());
                if (won) {
                    hpOnWin.add(retained);
                }
            }
        }

        int runs = wins.size();
        int winCount = (int) wins.stream().mapToDouble(Double::doubleValue).sum();
        double winRate = runs > 0 ? (double) winCount / runs : 0;
        double avgHpFracOnWin = Stats.mean(hpOnWin);
        double avgRounds = Stats.mean(rounds);
        // Win rate dominates; surviving HP breaks ties; faster is a small bonus.
        double fitness = winRate * 100 + avgHpFracOnWin * 10 - avgRounds * 0.1;
        return new EvalResult(
                fitness,
                winRate,
                avgHpFracOnWin,
                Stats.mean(hpRetained),
                Stats.mean(damage),
                avgRounds,
                Stats.mean(roundsEffective),
                Stats.mean(denied),
                runs,
                new EvalResult.Confidence(Stats.wilsonInterval(winCount, runs), Stats.meanInterval(damage), Stats.meanInterval(hpRetained)));
    }
}
