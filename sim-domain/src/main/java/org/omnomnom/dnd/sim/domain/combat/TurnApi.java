package org.omnomnom.dnd.sim.domain.combat;

import java.util.List;
import java.util.OptionalInt;
import org.omnomnom.dnd.sim.domain.combat.spell.Spell;
import org.omnomnom.dnd.sim.domain.grid.Cell;

/**
 * The restricted interface a {@link TurnPolicy} uses to act on its turn. It enforces the action economy; illegal
 * actions return an empty result (or {@code false}) and change nothing.
 */
public interface TurnApi {

    Combatant self();

    TurnResources resources();

    /** Conscious opponents. */
    List<Combatant> enemies();

    /** Conscious allies, excluding self. */
    List<Combatant> allies();

    /** Allies including downed-but-alive ones (for healing), excluding self. */
    List<Combatant> allAllies();

    /** Straight-line move to {@code dest}, spending movement and provoking opportunity attacks. */
    boolean moveTo(Cell dest);

    /** Make a weapon attack with an action. Returns the damage dealt, or empty if illegal. */
    OptionalInt attack(Combatant target, AttackProfile profile);

    /**
     * Cast a spell at a target (or, for a point/area spell, aimed at the target's cell). Spends the action and a slot
     * (cantrips are free). {@code slotLevel} may be null for the spell's own level. Returns total damage dealt, or
     * empty if illegal (no slot, out of range, wrong action).
     */
    OptionalInt castSpell(Spell spell, Combatant target, Integer slotLevel, boolean quickened);

    default OptionalInt castSpell(Spell spell, Combatant target) {
        return castSpell(spell, target, null, false);
    }

    /** Heal an ally from a pool as a Bonus Action (Paladin Lay on Hands). Returns HP restored, or empty. */
    OptionalInt layOnHands(Combatant target);

    /** Place Hunter's Mark on an enemy as a Bonus Action (Ranger). Returns success. */
    boolean markTarget(Combatant target);
}
