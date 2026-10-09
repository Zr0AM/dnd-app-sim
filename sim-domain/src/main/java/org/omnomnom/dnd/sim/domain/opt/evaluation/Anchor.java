package org.omnomnom.dnd.sim.domain.opt.evaluation;

import java.util.List;
import java.util.Map;
import org.omnomnom.dnd.sim.domain.content.build.FightingStyle;
import org.omnomnom.dnd.sim.domain.content.equipment.Gear;
import org.omnomnom.dnd.sim.domain.opt.genome.BuildClass;
import org.omnomnom.dnd.sim.domain.opt.genome.Genome;
import org.omnomnom.dnd.sim.domain.opt.genome.Genomes;
import org.omnomnom.dnd.sim.domain.opt.genome.MartialCatalog;

/**
 * Reference-anchor normalization: scale a build's objectives against a frozen benchmark build's performance in the
 * same scenarios, so 1.0 means "as good as the benchmark" and above 1 beats it. The anchor does not move as the search
 * evolves, which is why the metrics spec prefers it to population min-max. Upstream defines it but does not wire it
 * into the reports, and neither does this port.
 */
public final class Anchor {

    private Anchor() {}

    /**
     * Per-objective floors (what a build contributing nothing scores). Efficiency is negative rounds, so its floor is
     * the negated round cap: a build that always times out scores 0 there.
     */
    public static final Map<String, Double> OBJECTIVE_FLOORS = Map.of(
            "reliability", 0.0, "offense", 0.0, "survival", 0.0, "efficiency", -50.0, "control", 0.0, "support", 0.0);

    /** The frozen benchmark: a sword-and-board Champion fighter (a modest baseline). */
    public static final Genome BENCHMARK = new Genome(BuildClass.FIGHTER, List.of(0, 3, 1, 4, 5, 2), Gear.LONGSWORD, Gear.CHAIN_MAIL, true,
            false, FightingStyle.DEFENSE);

    /** The benchmark's objective vector across the catalog's scenario library. */
    public static double[] compute(MartialCatalog catalog, int runsPerScenario) {
        return Objectives.of(SoloEvaluator.evaluate(id -> Genomes.build(BENCHMARK, catalog, id), catalog.scenarios(), runsPerScenario));
    }

    /**
     * Normalize an objective vector against the anchor. When the anchor sits at the floor on an axis (no signal to
     * scale against), a build at or above it scores 1, else 0.
     */
    public static double[] normalize(double[] objectives, double[] anchor) {
        double[] out = new double[objectives.length];
        for (int i = 0; i < objectives.length; i++) {
            double floor = OBJECTIVE_FLOORS.get(Objectives.NAMES.get(i));
            double denom = anchor[i] - floor;
            out[i] = denom == 0 ? (objectives[i] >= anchor[i] ? 1 : 0) : (objectives[i] - floor) / denom;
        }
        return out;
    }
}
