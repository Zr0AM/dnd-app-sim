package org.omnomnom.dnd.sim.domain.content;

import java.util.List;
import org.omnomnom.dnd.sim.domain.core.Ability;

/** A class's hit die and saving-throw proficiencies, resolved from the seeds. */
public record ClassInfo(String slug, int hitDieSides, List<Ability> saveProficiencies) {

    public ClassInfo {
        saveProficiencies = List.copyOf(saveProficiencies);
    }
}
