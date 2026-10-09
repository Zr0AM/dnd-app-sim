package org.omnomnom.dnd.sim.domain.combat;

/** Test-only access to package-private combatant state (for example a starting wound). */
public final class CombatantTestAccess {

    private CombatantTestAccess() {}

    public static void setHp(Combatant c, int hp) {
        c.setHp(hp);
    }
}
