package org.omnomnom.dnd.sim.domain.scenario;

import java.util.ArrayList;
import java.util.List;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.content.monster.MonsterCompiler;
import org.omnomnom.dnd.sim.domain.content.monster.MonsterTemplate;
import org.omnomnom.dnd.sim.domain.core.Side;
import org.omnomnom.dnd.sim.domain.grid.Cell;
import org.omnomnom.dnd.sim.domain.grid.Grid;

/**
 * A runnable solo-hero scenario: a fixed map and a way to spawn fresh enemies each run. Immutable, so one instance
 * serves any number of concurrent runs.
 *
 * @param plan the enemies in placement order; {@code plan.get(n)} spawns as {@code enemy-n} on {@code enemyCells.get(n)}
 * @param xp total SRD XP of the enemies
 */
public record Scenario(
        String id,
        int level,
        Difficulty difficulty,
        String shape,
        String mapId,
        int xp,
        Grid grid,
        Cell heroStart,
        List<MonsterTemplate> plan,
        List<Cell> enemyCells) {

    public Scenario {
        plan = List.copyOf(plan);
        enemyCells = List.copyOf(enemyCells);
        if (plan.size() != enemyCells.size()) {
            throw new IllegalArgumentException("plan and cells differ in size");
        }
    }

    /** Spawn a fresh set of enemies on their starting cells. */
    public List<Combatant> spawnEnemies() {
        List<Combatant> out = new ArrayList<>(plan.size());
        for (int n = 0; n < plan.size(); n++) {
            out.add(MonsterCompiler.spawn(plan.get(n), new MonsterCompiler.Placement("enemy-" + n, Side.ENEMY, enemyCells.get(n))));
        }
        return out;
    }
}
