package org.omnomnom.dnd.sim.domain.opt.campaign;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.omnomnom.dnd.sim.domain.ai.TacticalPolicy;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.combat.Encounter;
import org.omnomnom.dnd.sim.domain.combat.event.EventSink;
import org.omnomnom.dnd.sim.domain.core.Side;
import org.omnomnom.dnd.sim.domain.opt.evaluation.Interval;
import org.omnomnom.dnd.sim.domain.opt.evaluation.SoloEvaluator;
import org.omnomnom.dnd.sim.domain.opt.evaluation.Stats;
import org.omnomnom.dnd.sim.domain.opt.genome.Genome;
import org.omnomnom.dnd.sim.domain.opt.genome.Genomes;
import org.omnomnom.dnd.sim.domain.opt.genome.MartialCatalog;
import org.omnomnom.dnd.sim.domain.opt.report.Reports;
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
        return evaluateAdventuringDay(genome, catalog, days, shortRestHealFraction, null);
    }

    /**
     * As above, with a root seed. With a null seed fight {@code i} of day {@code d} is seeded from {@code ("day", d, i)},
     * as upstream does; with a seed it is seeded from {@code ("day", seed, d, i)}, so different seeds sample different
     * days while every build under one seed still faces the same enemy rolls (common random numbers).
     */
    public static Result evaluateAdventuringDay(Genome genome, MartialCatalog catalog, int days, double shortRestHealFraction, Long seed) {
        List<Scenario> scenarios = catalog.scenarios();
        int perDay = scenarios.size();
        List<Double> cleared = new ArrayList<>();
        int wins = 0;

        for (int day = 0; day < days; day++) {
            int clearedCount = playDay(genome, catalog, day, shortRestHealFraction, seed);
            cleared.add((double) clearedCount);
            if (clearedCount == perDay) {
                wins++;
            }
        }
        return new Result(days > 0 ? (double) wins / days : 0, Stats.mean(cleared), perDay, days, Stats.wilsonInterval(wins, days));
    }

    /** One day: a fresh hero runs the scenarios in order, resting briefly between them, until one is not cleared. */
    private static int playDay(Genome genome, MartialCatalog catalog, int day, double shortRestHealFraction, Long seed) {
        List<Scenario> scenarios = catalog.scenarios();
        Combatant hero = Genomes.build(genome, catalog, SoloEvaluator.HERO_ID);
        int clearedCount = 0;
        for (int i = 0; i < scenarios.size(); i++) {
            if (i > 0) {
                // Short rest: refresh short-rest resources and heal a slice of HP; long-rest resources stay depleted.
                hero.shortRest();
                hero.heal((int) Math.floor(hero.maxHp() * shortRestHealFraction));
            }
            if (!clears(hero, scenarios.get(i), seedFor(seed, day, i))) {
                break; // the day ends when the hero falls or fails to clear a fight
            }
            clearedCount++;
        }
        return clearedCount;
    }

    /** Fight {@code i} of day {@code day}: upstream's seeding without a root seed, else keyed by it too. */
    private static long seedFor(Long seed, int day, int i) {
        return seed == null ? Seeds.seedFrom("day", day, i) : Seeds.seedFrom("day", seed, day, i);
    }

    /** Whether the hero wins the fight and is still standing. */
    private static boolean clears(Combatant hero, Scenario scenario, long seed) {
        // Reset the transient between-fight state the hero should not carry over.
        hero.setConcentratingOn(null);
        hero.setMarkedTarget(null);
        hero.clearForm();
        hero.setPosition(scenario.heroStart());
        List<Combatant> all = new ArrayList<>();
        all.add(hero);
        all.addAll(scenario.spawnEnemies());
        Encounter.RunResult res = Encounter.builder(scenario.grid(), all, new LabeledRandom(seed))
                .policyFor(c -> TacticalPolicy.DEFAULT)
                .sink(EventSink.NOOP)
                .build()
                .run(SoloEvaluator.ROUND_CAP);
        return res.winner() == Side.PARTY && hero.isConscious();
    }

    /**
     * Annotate a report with each build's adventuring-day win rate, so the one-shot ranking can be read against
     * campaign viability. Only the reported builds (front and leaderboard) are simulated, keyed by genome so a build in
     * both is run once.
     */
    public static Reports.Report annotate(Reports.Report report, MartialCatalog catalog, int days, double shortRestHealFraction) {
        return annotate(report, catalog, days, shortRestHealFraction, null);
    }

    /** As above, with a root seed for the days (null for upstream's seeding); see {@link #evaluateAdventuringDay}. */
    public static Reports.Report annotate(Reports.Report report, MartialCatalog catalog, int days, double shortRestHealFraction, Long seed) {
        Map<String, Double> cache = new HashMap<>();
        UnaryOperator<List<Reports.Entry>> annotateAll = entries -> entries.stream()
                .map(e -> {
                    double rate = cache.computeIfAbsent(e.key(),
                            k -> evaluateAdventuringDay(e.genome(), catalog, days, shortRestHealFraction, seed).dayWinRate());
                    return new Reports.Entry(e.key(), e.rank(), e.genome(), e.description(), e.metrics(), e.objectives(),
                            e.weightedScore(), rate);
                })
                .toList();
        return new Reports.Report(report.version(), report.runKey(), report.config(), report.objectiveNames(),
                report.objectiveBounds(), report.weights(), annotateAll.apply(report.paretoFront()), annotateAll.apply(report.leaderboard()));
    }
}
