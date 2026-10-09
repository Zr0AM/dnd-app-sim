package org.omnomnom.dnd.sim.domain.combat;

import java.util.List;
import java.util.function.Consumer;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.Condition;
import org.omnomnom.dnd.sim.domain.core.Side;
import org.omnomnom.dnd.sim.domain.grid.Cell;
import org.omnomnom.dnd.sim.domain.grid.GridMath;

/**
 * Read-only queries over an encounter's combatants. It wraps the encounter's live list rather than copying it, so it
 * always reflects the current state, and every result keeps the list's order (which seeded results depend on).
 */
final class Roster {

    private static final int ADJACENT_FT = 5;
    private static final int AURA_RANGE_FT = 10;

    private final List<Combatant> combatants;
    private final int cellFt;

    Roster(List<Combatant> combatants, int cellFt) {
        this.combatants = combatants;
        this.cellFt = cellFt;
    }

    boolean anyConscious(Side side) {
        return combatants.stream().anyMatch(c -> c.side() == side && c.isConscious());
    }

    /** Conscious combatants on the other side from {@code self}. */
    List<Combatant> consciousOpponents(Combatant self) {
        return combatants.stream().filter(c -> c.side() != self.side() && c.isConscious()).toList();
    }

    /** Conscious combatants on {@code self}'s side, not counting {@code self}. */
    List<Combatant> consciousAllies(Combatant self) {
        return combatants.stream().filter(c -> c.side() == self.side() && c.isConscious() && c != self).toList();
    }

    /** Allies of {@code self} that are not dead (a dying ally can still be healed), not counting {@code self}. */
    List<Combatant> livingAllies(Combatant self) {
        return combatants.stream().filter(c -> c.side() == self.side() && c.isAlive() && c != self).toList();
    }

    /** An ally of {@code attacker} (not itself, conscious, not incapacitated) is within 5 ft of {@code target}. */
    boolean hasAdjacentAlly(Combatant attacker, Combatant target) {
        return combatants.stream()
                .anyMatch(c -> c != attacker
                        && c.side() == attacker.side()
                        && c.isConscious()
                        && !c.hasCondition(Condition.INCAPACITATED)
                        && GridMath.distanceFt(c.position(), target.position(), cellFt) <= ADJACENT_FT);
    }

    /**
     * Paladin Aura of Protection: the bonus a saving creature gets from nearby allied paladins' auras - the best
     * (non-stacking) Charisma modifier among conscious aura-bearing allies within 10 ft of {@code target}, never below 0.
     */
    int auraSaveBonus(Combatant target) {
        int best = combatants.stream()
                .filter(p -> p.side() == target.side() && p.isConscious() && hasAura(p)
                        && GridMath.distanceFt(p.position(), target.position(), cellFt) <= AURA_RANGE_FT)
                .mapToInt(p -> p.abilityMod(Ability.CHA))
                .max()
                .orElse(0);
        return Math.max(0, best);
    }

    private static boolean hasAura(Combatant c) {
        return c.features().stream().anyMatch(f -> f.id().equals(FeatureIds.AURA_OF_PROTECTION));
    }

    /** Visit every combatant in list order. */
    void forEach(Consumer<Combatant> action) {
        combatants.forEach(action);
    }

    /** Conscious opponents of {@code self} within {@code radiusFt} of {@code origin}. */
    List<Combatant> consciousOpponentsWithin(Combatant self, Cell origin, int radiusFt) {
        return combatants.stream()
                .filter(c -> c.side() != self.side() && c.isConscious() && GridMath.distanceFt(origin, c.position(), cellFt) <= radiusFt)
                .toList();
    }
}
