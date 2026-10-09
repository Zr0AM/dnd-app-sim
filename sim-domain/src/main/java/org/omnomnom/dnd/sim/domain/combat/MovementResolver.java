package org.omnomnom.dnd.sim.domain.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.omnomnom.dnd.sim.domain.combat.event.CombatEvent;
import org.omnomnom.dnd.sim.domain.grid.Cell;
import org.omnomnom.dnd.sim.domain.grid.Grid;
import org.omnomnom.dnd.sim.domain.grid.GridMath;

/** Movement along a straight line, and the opportunity attacks it provokes. */
final class MovementResolver {

    private final FightContext ctx;
    private final WeaponAttackResolver weapons;

    MovementResolver(FightContext ctx, WeaponAttackResolver weapons) {
        this.ctx = ctx;
        this.weapons = weapons;
    }

    /**
     * Straight-line move to {@code dest}. Cost is 5 ft per step, doubled for entering difficult terrain. Fails if any
     * cell on the path is impassable or the cost exceeds remaining movement. Opportunity attacks are resolved for
     * enemies the mover leaves the reach of (start-versus-end reach; a known simplification that ignores foes merely
     * passed through mid-path).
     */
    boolean moveTo(Combatant self, Cell dest, TurnResources resources) {
        if (!ctx.grid().isPassable(dest)) {
            return false;
        }
        OptionalInt cost = pathCostFt(linePath(self.position(), dest));
        if (cost.isEmpty() || cost.getAsInt() > resources.movementFt) {
            return false;
        }

        // Enemies who had the mover within reach before the move may take an opportunity attack.
        List<Combatant> provoked = ctx.roster().consciousOpponents(self).stream()
                .filter(e -> Conditions.canReact(e) && hasMeleeReach(e, self.position()) && !hasMeleeReach(e, dest))
                .toList();

        Cell from = self.position();
        self.setPosition(dest);
        resources.movementFt -= cost.getAsInt();
        ctx.log(new CombatEvent.Move(self.id(), from, dest, cost.getAsInt()));

        provoked.forEach(e -> opportunityAttack(e, self));
        return true;
    }

    /** The movement cost of entering every cell after the first, or empty if any of them is impassable. */
    private OptionalInt pathCostFt(List<Cell> path) {
        Grid grid = ctx.grid();
        int cost = 0;
        for (Cell step : path.subList(1, path.size())) {
            if (!grid.isPassable(step)) {
                return OptionalInt.empty();
            }
            cost += grid.cellFt() * (grid.isDifficult(step) ? 2 : 1);
        }
        return OptionalInt.of(cost);
    }

    private static Optional<AttackProfile> firstMelee(Combatant c) {
        return c.attacks().stream().filter(a -> a.kind() == AttackKind.MELEE).findFirst();
    }

    private boolean hasMeleeReach(Combatant attacker, Cell targetCell) {
        int reach = firstMelee(attacker).map(AttackProfile::reachFtOrDefault).orElse(AttackProfile.DEFAULT_REACH_FT);
        int d = GridMath.distanceFt(attacker.position(), targetCell, ctx.grid().cellFt());
        return d > 0 && d <= reach;
    }

    private void opportunityAttack(Combatant attacker, Combatant target) {
        firstMelee(attacker).ifPresent(profile -> {
            int dealt = weapons.resolve(attacker, target, profile, WeaponAttackResolver.Source.OPPORTUNITY).orElse(0);
            ctx.log(new CombatEvent.Opportunity(attacker.id(), target.id(), dealt > 0, dealt));
        });
    }

    /**
     * A grid path from {@code a} to {@code b} as a sequence of cells (inclusive of both), stepping one cell at a time
     * toward the destination (diagonals allowed). Length equals the Chebyshev distance plus one.
     */
    static List<Cell> linePath(Cell a, Cell b) {
        List<Cell> path = new ArrayList<>();
        path.add(a);
        int x = a.x();
        int y = a.y();
        int steps = GridMath.stepDistance(a, b);
        for (int i = 0; i < steps; i++) {
            x += Integer.signum(b.x() - x);
            y += Integer.signum(b.y() - y);
            path.add(new Cell(x, y));
        }
        return path;
    }
}
