package org.omnomnom.dnd.sim.domain.combat;

/** Per-turn resource budget. Mutated by the encounter as the policy acts. */
public final class TurnResources {

    boolean action = true;
    boolean bonus = true;
    int movementFt;
    /** Remaining attacks in the current Attack action (set when the action is spent). */
    int attacksRemaining;
    /** Extra single-attack actions from a buff (Haste) or feature, each good for one weapon attack. */
    int extraAttackActions;

    TurnResources(int movementFt, int extraAttackActions) {
        this.movementFt = movementFt;
        this.extraAttackActions = extraAttackActions;
    }

    public boolean action() {
        return action;
    }

    public boolean bonus() {
        return bonus;
    }

    public int movementFt() {
        return movementFt;
    }

    public int attacksRemaining() {
        return attacksRemaining;
    }

    public int extraAttackActions() {
        return extraAttackActions;
    }
}
