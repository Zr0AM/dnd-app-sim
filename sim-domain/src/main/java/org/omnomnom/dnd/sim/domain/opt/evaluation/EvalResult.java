package org.omnomnom.dnd.sim.domain.opt.evaluation;

/**
 * The outcome of evaluating one hero across the solo scenario library.
 *
 * @param fitness scalar fitness: win rate dominates, surviving HP breaks ties, faster is a small bonus
 * @param avgHpFracRetained mean HP fraction retained across all runs (0 when downed): survivability
 * @param avgDamageDealt mean damage the hero dealt per fight: offense
 * @param avgRoundsEffective mean rounds with a loss counted as the round cap, so "die fast" does not read as
 *     "efficient" (a gap NSGA-II exploited when efficiency was raw rounds)
 * @param avgActionsDenied mean enemy actions the hero denied via control conditions: control
 * @param runs total runs across all scenarios
 */
public record EvalResult(
        double fitness,
        double winRate,
        double avgHpFracOnWin,
        double avgHpFracRetained,
        double avgDamageDealt,
        double avgRounds,
        double avgRoundsEffective,
        double avgActionsDenied,
        int runs,
        Confidence ci) {

    /** 95% intervals for the headline metrics. */
    public record Confidence(Interval winRate, Interval damage, Interval hpRetained) {}
}
