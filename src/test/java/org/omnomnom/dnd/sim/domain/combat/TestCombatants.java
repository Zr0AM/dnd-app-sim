package org.omnomnom.dnd.sim.domain.combat;

import java.util.function.UnaryOperator;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.AbilityScores;
import org.omnomnom.dnd.sim.domain.grid.Cell;

/** Shared builders for combat tests. */
final class TestCombatants {

    private TestCombatants() {}

    /** Level 5, AC 16, 40 HP, Str/Con save proficiencies (the {@code actor.spec.ts} default). */
    static CombatantSpec.Builder base() {
        return CombatantSpec.builder("c1", "Test", Side.PARTY, 5, AbilityScores.of(16, 14, 14, 10, 12, 8), 16, 40)
                .saveProficiencies(java.util.Set.of(Ability.STR, Ability.CON));
    }

    static Combatant make() {
        return new Combatant(base().build());
    }

    static Combatant make(UnaryOperator<CombatantSpec.Builder> customize) {
        return new Combatant(customize.apply(base()).build());
    }

    /** The {@code conditions.spec.ts} default: level 5, AC 15, 30 HP, all 14s in the physical scores. */
    static Combatant plain() {
        return new Combatant(CombatantSpec.builder("x", "X", Side.PARTY, 5, AbilityScores.of(14, 14, 14, 10, 10, 10), 15, 30)
                .position(new Cell(0, 0))
                .build());
    }
}
