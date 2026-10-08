package org.omnomnom.dnd.sim.application;

import java.util.List;
import org.omnomnom.dnd.sim.domain.combat.CombatEvent;
import org.omnomnom.dnd.sim.domain.combat.Side;
import org.omnomnom.dnd.sim.domain.opt.Genome;
import org.omnomnom.dnd.sim.domain.opt.Interval;

/**
 * Aggregate results of an encounter simulation.
 *
 * @param avgRoundsEffective a loss counts as the round cap
 * @param members every combatant, party first then enemies
 * @param log one run's full event log, or null when not requested
 */
public record EncounterResult(
        long seed,
        int level,
        String map,
        int runs,
        int roundCap,
        Interval winRate,
        double avgRounds,
        double avgRoundsEffective,
        List<MemberStats> members,
        CombatLog log) {

    /**
     * Per-combatant means over all runs, attributed from the event log.
     *
     * @param genome the effective (repaired) genome for build members, else null
     * @param avgDamageDealt attacks, opportunity attacks, spells and legendary actions
     * @param avgHpFracRetained 0 when downed
     * @param survivalRate fraction of runs the combatant was not dead
     */
    public record MemberStats(
            String id,
            String name,
            Side side,
            Genome genome,
            double avgDamageDealt,
            double avgHealingDone,
            double avgBuffAssists,
            double avgActionsDenied,
            double avgHpFracRetained,
            double survivalRate) {}

    /** The event log of one run. */
    public record CombatLog(int runIndex, int rounds, Side winner, List<CombatEvent> events) {}
}
