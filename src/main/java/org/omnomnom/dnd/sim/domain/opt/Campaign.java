package org.omnomnom.dnd.sim.domain.opt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.omnomnom.dnd.sim.domain.ai.TacticalPolicy;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.combat.Encounter;
import org.omnomnom.dnd.sim.domain.combat.EventSink;
import org.omnomnom.dnd.sim.domain.combat.Side;
import org.omnomnom.dnd.sim.domain.rng.LabeledRandom;
import org.omnomnom.dnd.sim.domain.rng.Seeds;
import org.omnomnom.dnd.sim.domain.scenario.Scenario;

/**
 * Campaign-path scoring: the adventuring day. Where the solo and party evaluators score a single encounter (the nova
 * view), this runs a sequence of encounters on one hero without a long rest, only short rests between, so long-rest
 * resources (spell slots, Rage, Sorcery Points) deplete across the day while short-rest resources (Warlock Pact slots,
 * Monk Focus) and at-will options carry the build. That separates a nova build from a sustained one.
 */
public final class Campaign {

    private Campaign() {}

    public static final int DEFAULT_DAYS = 16;
    public static final double DEFAULT_SHORT_REST_HEAL_FRACTION = 0.5;

    /**
     * @param dayWinRate fraction of days the hero cleared every encounter and survived
     * @param avgEncountersCleared mean encounters cleared before the hero fell
     * @param encountersPerDay encounters in a day
     */
    public record Result(double dayWinRate, double avgEncountersCleared, int encountersPerDay, int days, Interval dayWinRateCi) {}

    public static Result evaluateAdventuringDay(Genome genome, MartialCatalog catalog) {
        return evaluateAdventuringDay(genome, catalog, DEFAULT_DAYS, DEFAULT_SHORT_REST_HEAL_FRACTION);
    }

    /**
     * Run the catalog's scenario set, in order, on one persisted hero per day with a short rest between encounters.
     *
     * @param days simulated days, each a fresh hero running the sequence
     * @param shortRestHealFraction fraction of max HP recovered on each short rest (a hit-dice abstraction)
     */
    public static Result evaluateAdventuringDay(Genome genome, MartialCatalog catalog, int days, double shortRestHealFraction) {
        List<Scenario> scenarios = catalog.scenarios();
        int perDay = scenarios.size();
        List<Double> cleared = new ArrayList<>();
        int wins = 0;

        for (int day = 0; day < days; day++) {
            Combatant hero = Genomes.build(genome, catalog, SoloEvaluator.HERO_ID);
            int clearedCount = 0;
            for (int i = 0; i < perDay; i++) {
                if (i > 0) {
                    // Short rest: refresh short-rest resources and heal a slice of HP; long-rest resources stay depleted.
                    hero.shortRest();
                    hero.heal((int) Math.floor(hero.maxHp() * shortRestHealFraction));
                }
                // Reset the transient between-fight state the hero should not carry over.
                hero.setConcentratingOn(null);
                hero.setMarkedTarget(null);
                hero.clearForm();
                Scenario scenario = scenarios.get(i);
                hero.setPosition(scenario.heroStart());
                List<Combatant> all = new ArrayList<>();
                all.add(hero);
                all.addAll(scenario.spawnEnemies());
                Encounter.RunResult res = Encounter.builder(scenario.grid(), all, new LabeledRandom(Seeds.seedFrom("day", day, i)))
                        .policyFor(c -> TacticalPolicy.DEFAULT)
                        .sink(EventSink.NOOP)
                        .build()
                        .run(SoloEvaluator.ROUND_CAP);
                if (res.winner() == Side.PARTY && hero.isConscious()) {
                    clearedCount++;
                } else {
                    break; // the day ends when the hero falls or fails to clear a fight
                }
            }
            cleared.add((double) clearedCount);
            if (clearedCount == perDay) {
                wins++;
            }
        }
        return new Result(days > 0 ? (double) wins / days : 0, Stats.mean(cleared), perDay, days, Stats.wilsonInterval(wins, days));
    }

    /**
     * Annotate a report with each build's adventuring-day win rate, so the one-shot ranking can be read against
     * campaign viability. Only the reported builds (front and leaderboard) are simulated, keyed by genome so a build in
     * both is run once.
     */
    public static Reports.Report annotate(Reports.Report report, MartialCatalog catalog, int days, double shortRestHealFraction) {
        Map<String, Double> cache = new HashMap<>();
        java.util.function.UnaryOperator<List<Reports.Entry>> annotateAll = entries -> {
            List<Reports.Entry> out = new ArrayList<>();
            for (Reports.Entry e : entries) {
                double rate = cache.computeIfAbsent(e.key(),
                        k -> evaluateAdventuringDay(e.genome(), catalog, days, shortRestHealFraction).dayWinRate());
                out.add(new Reports.Entry(e.key(), e.rank(), e.genome(), e.description(), e.metrics(), e.objectives(),
                        e.weightedScore(), rate));
            }
            return List.copyOf(out);
        };
        return new Reports.Report(report.version(), report.runKey(), report.config(), report.objectiveNames(),
                report.objectiveBounds(), report.weights(), annotateAll.apply(report.paretoFront()), annotateAll.apply(report.leaderboard()));
    }
}
