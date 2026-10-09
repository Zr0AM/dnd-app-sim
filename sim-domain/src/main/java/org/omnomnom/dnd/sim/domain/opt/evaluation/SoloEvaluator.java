package org.omnomnom.dnd.sim.domain.opt.evaluation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.omnomnom.dnd.sim.domain.ai.TacticalPolicy;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.combat.Encounter;
import org.omnomnom.dnd.sim.domain.core.Side;
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
        List<Run> runs = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            for (int i = 0; i < runsPerScenario; i++) {
                runs.add(fight(heroFactory, scenario, i));
            }
        }
        return summarize(runs);
    }

    /** What one fight contributes to the evaluation. */
    private record Run(boolean won, double hpRetained, double damage, double rounds, double roundsEffective, double denied) {}

    private static Run fight(Function<String, Combatant> heroFactory, Scenario scenario, int runIndex) {
        // CRN: the seed depends only on the scenario id and run index.
        LabeledRandom rng = new LabeledRandom(Seeds.seedFrom(scenario.id(), runIndex));
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
        return new Run(won, retained, tally.damage(), res.rounds(), won ? res.rounds() : ROUND_CAP, tally.actionsDenied());
    }

    private static EvalResult summarize(List<Run> runs) {
        int count = runs.size();
        int winCount = (int) runs.stream().filter(Run::won).count();
        double winRate = count > 0 ? (double) winCount / count : 0;
        double avgHpFracOnWin = Stats.mean(Samples.column(runs.stream().filter(Run::won).toList(), Run::hpRetained));
        double avgRounds = Stats.mean(Samples.column(runs, Run::rounds));
        List<Double> hpRetained = Samples.column(runs, Run::hpRetained);
        List<Double> damage = Samples.column(runs, Run::damage);
        // Win rate dominates; surviving HP breaks ties; faster is a small bonus.
        double fitness = winRate * 100 + avgHpFracOnWin * 10 - avgRounds * 0.1;
        return new EvalResult(
                fitness,
                winRate,
                avgHpFracOnWin,
                Stats.mean(hpRetained),
                Stats.mean(damage),
                avgRounds,
                Stats.mean(Samples.column(runs, Run::roundsEffective)),
                Stats.mean(Samples.column(runs, Run::denied)),
                count,
                new EvalResult.Confidence(Stats.wilsonInterval(winCount, count), Stats.meanInterval(damage), Stats.meanInterval(hpRetained)));
    }
}
