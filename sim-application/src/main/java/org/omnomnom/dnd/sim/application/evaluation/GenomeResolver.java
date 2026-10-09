package org.omnomnom.dnd.sim.application.evaluation;

import java.util.ArrayList;
import java.util.List;
import org.omnomnom.dnd.sim.application.error.UnprocessableException;
import org.omnomnom.dnd.sim.domain.opt.genome.Genome;
import org.omnomnom.dnd.sim.domain.opt.genome.Genomes;
import org.omnomnom.dnd.sim.domain.opt.genome.MartialCatalog;
import org.omnomnom.dnd.sim.domain.rng.LabeledRandom;

/**
 * Turns a partial {@link GenomeInput} into a complete, legal {@link Genome}: a random genome for the class from the
 * request seed (as the CLI did), overlaid with whatever the caller specified, then repaired. Casters and the Monk use a
 * fixed gear package, so only their ability assignment is overlaid.
 */
public final class GenomeResolver {

    private GenomeResolver() {}

    public static Genome resolve(GenomeInput in, MartialCatalog catalog, long seed, String scope) {
        if (in.classSlug() == null) {
            throw new UnprocessableException("missing-class", "classSlug is required", scope + ".classSlug");
        }
        validate(in, catalog, scope);
        LabeledRandom random = new LabeledRandom(seed).child("eval:" + in.classSlug().code());
        Genome base = Genomes.randomGenome(catalog, random, "eval", List.of(in.classSlug()));
        List<Integer> assignment = in.abilityAssignment() != null ? in.abilityAssignment() : base.abilityAssignment();
        if (in.classSlug().isCaster()) {
            return new Genome(base.classSlug(), assignment, base.weaponName(), base.armorName(), base.shield(), base.twoHanded(), base.fightingStyle());
        }
        Genome over = new Genome(
                base.classSlug(),
                assignment,
                in.weaponName() != null ? in.weaponName() : base.weaponName(),
                in.armorName() != null ? in.armorName() : base.armorName(),
                in.shield() != null ? in.shield() : base.shield(),
                in.twoHanded() != null ? in.twoHanded() : base.twoHanded(),
                in.fightingStyle() != null ? in.fightingStyle() : base.fightingStyle());
        return Genomes.repair(over, catalog);
    }

    private static void validate(GenomeInput in, MartialCatalog catalog, String scope) {
        if (in.abilityAssignment() != null) {
            List<Integer> sorted = new ArrayList<>(in.abilityAssignment());
            sorted.sort(null);
            if (!sorted.equals(List.of(0, 1, 2, 3, 4, 5))) {
                throw new UnprocessableException("invalid-ability-assignment",
                        "abilityAssignment must be a permutation of 0..5", scope + ".abilityAssignment");
            }
        }
        if (in.weaponName() != null && catalog.weapons().stream().noneMatch(w -> w.name().equals(in.weaponName()))) {
            throw new UnprocessableException("unknown-weapon", "unknown weapon: " + in.weaponName(), scope + ".weaponName");
        }
        if (in.armorName() != null && catalog.armors().stream().noneMatch(a -> a.name().equals(in.armorName()))) {
            throw new UnprocessableException("unknown-armor", "unknown armor: " + in.armorName(), scope + ".armorName");
        }
    }
}
