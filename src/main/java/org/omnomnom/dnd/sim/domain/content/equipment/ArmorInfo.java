package org.omnomnom.dnd.sim.domain.content.equipment;

/** Armor resolved from the seeds. {@code dexCap} is null when Dexterity is uncapped (or not added). */
public record ArmorInfo(String name, Category category, int baseAc, boolean addsDex, Integer dexCap) {

    public enum Category {
        LIGHT,
        MEDIUM,
        HEAVY,
        SHIELD
    }
}
