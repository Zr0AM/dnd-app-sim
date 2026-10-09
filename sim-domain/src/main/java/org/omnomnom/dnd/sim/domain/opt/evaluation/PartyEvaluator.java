package org.omnomnom.dnd.sim.domain.opt.evaluation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.omnomnom.dnd.sim.domain.ai.TacticalPolicy;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.combat.Encounter;
import org.omnomnom.dnd.sim.domain.content.build.Fillers;
import org.omnomnom.dnd.sim.domain.content.build.Role;
import org.omnomnom.dnd.sim.domain.content.monster.MonsterCatalog;
import org.omnomnom.dnd.sim.domain.core.Side;
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
        List<Run> runs = new ArrayList<>();
        for (PartyTemplate template : templates) {
            for (PartyScenario scenario : harness.scenariosByPartySize().getOrDefault(template.roles().size(), List.of())) {
                for (int i = 0; i < runsPerScenario; i++) {
                    runs.add(fight(heroFactory, harness, heroRole, template, scenario, i));
                }
            }
        }
        return summarize(runs);
    }

    /** What one fight contributes to the evaluation, attributed to the hero. */
    private record Run(
            boolean won, double damage, double healing, double assists, double denied, double heroHp, double roundsEffective,
            double alliesAlive) {}

    private static Run fight(
            Function<String, Combatant> heroFactory, Harness harness, Role heroRole, PartyTemplate template, PartyScenario scenario,
            int runIndex) {
        LabeledRandom rng = new LabeledRandom(Seeds.seedFrom("party", template.id(), scenario.id(), runIndex));
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
        double heroHp = hero.isConscious() ? (double) hero.hp() / hero.maxHp() : 0;
        return new Run(won, tally.damage(), tally.healing(), tally.buffAssists(), tally.actionsDenied(), heroHp,
                won ? res.rounds() : ROUND_CAP, fractionOfAlliesAlive(party, hero));
    }

    /** The share of the hero's allies still alive; 1 when it has none. */
    private static double fractionOfAlliesAlive(List<Combatant> party, Combatant hero) {
        long allies = party.stream().filter(c -> c != hero).count();
        long alive = party.stream().filter(c -> c != hero && c.isAlive()).count();
        return allies > 0 ? (double) alive / allies : 1.0;
    }

    private static PartyEvalResult summarize(List<Run> runs) {
        int count = runs.size();
        int winCount = (int) runs.stream().filter(Run::won).count();
        double avgHealing = Stats.mean(Samples.column(runs, Run::healing));
        double avgAssists = Stats.mean(Samples.column(runs, Run::assists));
        return new PartyEvalResult(
                count > 0 ? (double) winCount / count : 0,
                Stats.mean(Samples.column(runs, Run::damage)),
                avgHealing,
                avgAssists,
                avgHealing + avgAssists,
                Stats.mean(Samples.column(runs, Run::denied)),
                Stats.mean(Samples.column(runs, Run::heroHp)),
                Stats.mean(Samples.column(runs, Run::roundsEffective)),
                Stats.mean(Samples.column(runs, Run::alliesAlive)),
                count,
                Stats.wilsonInterval(winCount, count));
    }
}
