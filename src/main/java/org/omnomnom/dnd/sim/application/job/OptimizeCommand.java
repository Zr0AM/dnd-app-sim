package org.omnomnom.dnd.sim.application.job;

import java.util.List;
import org.omnomnom.dnd.sim.domain.opt.genome.BuildClass;

/**
 * Start an NSGA-II optimization (the CLI {@code optimize}).
 *
 * @param role a role preset or {@code equal} (null means {@code equal}); weights the leaderboard
 * @param classes restricts the genome pool; null or empty means all twelve
 * @param preset {@code quick}, {@code standard} or {@code thorough}; null picks the level's default
 * @param ga explicit effort fields that override the chosen preset field by field; may be null
 * @param party true for a party-context run, which is not supported yet
 * @param campaign also score each reported build's adventuring day
 * @param seed null to have the server draw one
 */
public record OptimizeCommand(int level, String role, List<BuildClass> classes, String preset, GaParams ga, boolean party,
        boolean campaign, Long seed) {

    /** Effort fields; any may be null. */
    public record GaParams(Integer populationSize, Integer generations, Integer evalRuns, Double mutationRate) {}
}
