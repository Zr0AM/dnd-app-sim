package org.omnomnom.dnd.sim.application.execution;

import org.omnomnom.dnd.sim.application.error.UnprocessableException;

/**
 * Operator-tunable ceilings on how much work one request may ask for. The API schema's own maxima are hard 400s; these
 * can be set lower (or, up to the schema maxima, left at their defaults) and are enforced as {@code 422 limit-exceeded}.
 *
 * @param encounterRuns runs per encounter request
 * @param evalRunsPerScenario runs per scenario for an evaluation
 * @param campaignDays simulated days for a campaign
 * @param optimizePopulation NSGA-II population size
 * @param optimizeGenerations NSGA-II generations
 * @param optimizeEvalRuns runs per scenario per evaluation inside an optimization
 * @param optimizeMaxFights an upper bound on the fights one optimization may simulate: population x (generations + 1) x
 *     evaluation runs x scenarios, ignoring the cache that makes repeated genomes free
 */
public record SimLimits(
        int encounterRuns,
        int evalRunsPerScenario,
        int campaignDays,
        int optimizePopulation,
        int optimizeGenerations,
        int optimizeEvalRuns,
        long optimizeMaxFights) {

    public static SimLimits defaults() {
        return new SimLimits(2000, 200, 100, 128, 100, 64, 2_000_000L);
    }

    public static void require(long value, long max, String field, String what) {
        if (value > max) {
            throw new UnprocessableException("limit-exceeded", what + " is limited to " + max + " here, got " + value, field);
        }
    }
}
