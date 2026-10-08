package org.omnomnom.dnd.sim.domain.ai;

/**
 * Tunable weights for the tactical AI. The defaults are a sensible generalist; roles can bias target selection and
 * positioning by supplying other weights.
 *
 * @param woundedPreference preference for already-wounded targets (focus fire)
 * @param threatPreference preference for removing high-damage threats
 * @param distancePenalty penalty per 5 ft of distance to a target (prefer closer, less movement)
 * @param finishBonus bonus when this turn's expected damage can drop the target (secure the kill)
 * @param assumedHitChance nominal hit chance used to estimate this turn's damage
 */
public record TacticsWeights(
        double woundedPreference,
        double threatPreference,
        double distancePenalty,
        double finishBonus,
        double assumedHitChance) {

    public static final TacticsWeights DEFAULT = new TacticsWeights(2, 1, 0.1, 5, 0.6);
}
