package org.omnomnom.dnd.sim.domain.combat;

/**
 * What happened when a creature took damage, for events and metrics.
 *
 * @param hpLost HP actually removed from the HP pool (after temp HP absorption)
 * @param absorbedByTemp damage absorbed by temporary Hit Points
 * @param dropped the creature dropped to 0 HP on this hit
 * @param died the creature died outright (massive damage or third death-save failure)
 * @param deathSaveFailures death-save failures incurred by taking damage while already at 0 HP
 */
public record DamageOutcome(int hpLost, int absorbedByTemp, boolean dropped, boolean died, int deathSaveFailures) {

    static final DamageOutcome NONE = new DamageOutcome(0, 0, false, false, 0);
}
