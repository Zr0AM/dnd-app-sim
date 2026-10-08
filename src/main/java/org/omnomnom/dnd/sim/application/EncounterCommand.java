package org.omnomnom.dnd.sim.application;

import java.util.List;

/**
 * Simulate one encounter, possibly many times. Exactly one of {@code enemies} or {@code scenarioId} is given.
 *
 * @param enemies monster groups, or null
 * @param scenarioId a library scenario id, or null
 * @param mapId a map id, or null for the default (open field for one hero, the party field for more)
 * @param seed null to have the server draw one
 */
public record EncounterCommand(
        int level,
        List<PartyMemberSpec> party,
        List<EnemyGroup> enemies,
        String scenarioId,
        String mapId,
        int runs,
        Long seed,
        int roundCap,
        boolean includeLog,
        int logRun) {

    public static final int DEFAULT_ROUND_CAP = 50;

    public record EnemyGroup(String monsterSlug, int count) {}
}
