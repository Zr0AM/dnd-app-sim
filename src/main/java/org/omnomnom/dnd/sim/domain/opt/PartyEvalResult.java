package org.omnomnom.dnd.sim.domain.opt;

/**
 * A hero's metrics inside the reference parties.
 *
 * @param avgHeroBuffAssists mean buff assists the hero delivered (boosted ally attacks plus Haste attacks)
 * @param avgHeroSupport healing done plus buff assists
 * @param avgHeroActionsDenied mean enemy actions the hero denied via control conditions
 * @param avgRoundsEffective mean rounds, counting a loss as the round cap
 */
public record PartyEvalResult(
        double winRate,
        double avgHeroDamage,
        double avgHeroHealing,
        double avgHeroBuffAssists,
        double avgHeroSupport,
        double avgHeroActionsDenied,
        double avgHeroHpFracRetained,
        double avgRoundsEffective,
        double avgAlliesAliveFrac,
        int runs,
        Interval winRateCi) {}
