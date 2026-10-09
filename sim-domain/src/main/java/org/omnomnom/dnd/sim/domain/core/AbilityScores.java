package org.omnomnom.dnd.sim.domain.core;

/** The six ability scores (immutable). Replaces the TypeScript {@code Record<Ability, number>}. */
public record AbilityScores(int str, int dex, int con, int intelligence, int wis, int cha) {

    public static AbilityScores of(int str, int dex, int con, int intelligence, int wis, int cha) {
        return new AbilityScores(str, dex, con, intelligence, wis, cha);
    }

    /** All six scores set to 10 (a modifier of +0). */
    public static AbilityScores allTens() {
        return new AbilityScores(10, 10, 10, 10, 10, 10);
    }

    public int get(Ability ability) {
        return switch (ability) {
            case STR -> str;
            case DEX -> dex;
            case CON -> con;
            case INT -> intelligence;
            case WIS -> wis;
            case CHA -> cha;
        };
    }

    public int modifier(Ability ability) {
        return CoreRules.abilityModifier(get(ability));
    }
}
