package org.omnomnom.dnd.sim.domain.content;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.omnomnom.dnd.sim.domain.core.Ability;

/** A class's hit die and saving-throw proficiencies, resolved from the seeds. */
public record ClassInfo(String slug, int hitDieSides, List<Ability> saveProficiencies) {

    public ClassInfo {
        saveProficiencies = List.copyOf(saveProficiencies);
    }

    /** The saving-throw proficiencies as a set (empty-safe, unlike {@code EnumSet.copyOf}). */
    public static Set<Ability> saveSet(ClassInfo c) {
        Set<Ability> set = EnumSet.noneOf(Ability.class);
        set.addAll(c.saveProficiencies());
        return set;
    }
}
