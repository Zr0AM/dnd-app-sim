package org.omnomnom.dnd.sim.domain.core;

/** Ability-modifier and proficiency-bonus rules (2024 SRD). */
public final class CoreRules {

    private CoreRules() {}

    /** The 2024 ability modifier: {@code floor((score - 10) / 2)}. */
    public static int abilityModifier(int score) {
        return Math.floorDiv(score - 10, 2);
    }

    /**
     * Proficiency bonus by total character level (SRD Character Advancement): +2 at 1-4, +3 at 5-8, +4 at 9-12,
     * +5 at 13-16, +6 at 17-20.
     */
    public static int proficiencyBonus(int level) {
        if (level < 1 || level > 20) {
            throw new IllegalArgumentException("level must be an integer in 1..20, got " + level);
        }
        return 2 + (level - 1) / 4;
    }
}
