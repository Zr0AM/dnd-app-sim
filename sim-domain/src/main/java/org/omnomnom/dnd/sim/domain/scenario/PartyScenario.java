package org.omnomnom.dnd.sim.domain.scenario;

import java.util.ArrayList;
import java.util.List;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.content.monster.MonsterCompiler;
import org.omnomnom.dnd.sim.domain.content.monster.MonsterTemplate;
import org.omnomnom.dnd.sim.domain.core.Side;
import org.omnomnom.dnd.sim.domain.grid.Cell;
import org.omnomnom.dnd.sim.domain.grid.Grid;

/** A party scenario: a map and an enemy spawn scaled to the party size. Immutable. */
public record PartyScenario(String id, int partySize, Grid grid, List<Cell> partyCells, List<MonsterTemplate> plan,
        List<Cell> enemyCells) {

    public PartyScenario {
        partyCells = List.copyOf(partyCells);
        plan = List.copyOf(plan);
        enemyCells = List.copyOf(enemyCells);
    }

    public List<Combatant> spawnEnemies() {
        List<Combatant> out = new ArrayList<>(plan.size());
        for (int n = 0; n < plan.size(); n++) {
            out.add(MonsterCompiler.spawn(plan.get(n), new MonsterCompiler.Placement("enemy-" + n, Side.ENEMY, enemyCells.get(n))));
        }
        return out;
    }
}
