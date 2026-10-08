package org.omnomnom.dnd.sim.domain.opt;

import java.util.List;

/**
 * The six-axis objective vector NSGA-II optimizes, all oriented so higher is better: reliability (win rate), offense
 * (damage), survival (HP retained), efficiency (negative rounds, fewer is better), control (enemy actions denied) and
 * support (healing plus buff assists given to allies).
 */
public final class Objectives {

    private Objectives() {}

    public static final List<String> NAMES =
            List.of("reliability", "offense", "survival", "efficiency", "control", "support");

    /**
     * Objectives of a solo result. A lone hero has no allies to heal or buff, so support is always 0 here; it carries
     * real values only in the party harness.
     */
    public static double[] of(EvalResult r) {
        return new double[] {
            r.winRate(), r.avgDamageDealt(), r.avgHpFracRetained(), -r.avgRoundsEffective(), r.avgActionsDenied(), 0
        };
    }

    /** Objectives of a party result, in the same order and orientation as {@link #of(EvalResult)}. */
    public static double[] of(PartyEvalResult r) {
        return new double[] {
            r.winRate(),
            r.avgHeroDamage(),
            r.avgHeroHpFracRetained(),
            -r.avgRoundsEffective(),
            r.avgHeroActionsDenied(),
            r.avgHeroSupport()
        };
    }
}
