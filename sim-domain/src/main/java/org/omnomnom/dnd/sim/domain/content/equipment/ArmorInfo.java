package org.omnomnom.dnd.sim.domain.content.equipment;

/** Armor resolved from the seeds. {@code dexCap} is null when Dexterity is uncapped (or not added). */
public record ArmorInfo(String name, Category category, int baseAc, boolean addsDex, Integer dexCap) {

    /** The Dexterity modifier this armor lets its wearer add to Armor Class, after any cap. */
    public int dexBonus(int dexMod) {
        if (!addsDex) {
            return 0;
        }
        return dexCap != null ? Math.min(dexMod, dexCap) : dexMod;
    }

    public enum Category {
        LIGHT,
        MEDIUM,
        HEAVY,
        SHIELD
    }
}
