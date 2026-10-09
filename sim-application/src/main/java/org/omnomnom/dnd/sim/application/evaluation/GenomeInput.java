package org.omnomnom.dnd.sim.application.evaluation;

import java.util.List;
import org.omnomnom.dnd.sim.domain.content.build.FightingStyle;
import org.omnomnom.dnd.sim.domain.opt.genome.BuildClass;

/**
 * A partial build: only the class is required. Omitted fields are filled from the request seed the way the CLI did,
 * then the genome is repaired to be legal.
 */
public record GenomeInput(
        BuildClass classSlug,
        List<Integer> abilityAssignment,
        String weaponName,
        String armorName,
        Boolean shield,
        Boolean twoHanded,
        FightingStyle fightingStyle) {

    public static GenomeInput of(BuildClass classSlug) {
        return new GenomeInput(classSlug, null, null, null, null, null, null);
    }
}
