package org.omnomnom.dnd.sim.domain.content;

import java.util.List;
import java.util.Map;
import org.omnomnom.dnd.sim.domain.combat.SpellcastingSpec;

/**
 * The domain's view of the SRD reference data (a driven port). Implemented by an outbound adapter backed by the seed
 * database; the domain compilers and catalogs read only through this interface, so they stay free of persistence.
 *
 * <p>Implementations are consulted while catalogs are built (at startup), then the results are immutable in-memory
 * objects, so simulations never touch the data source.
 */
public interface ContentSource {

    /** Every active monster with its related rows, ordered by slug. */
    List<MonsterSource> monsterSources();

    /** A weapon by equipment name (for example {@code "Longsword"}). Throws if the weapon is unknown. */
    WeaponInfo weapon(String name);

    /** An armor by equipment name (for example {@code "Chain Mail"}). Throws if the armor is unknown. */
    ArmorInfo armor(String name);

    /** A class's hit die and saving-throw proficiencies by slug. Throws if the class is unknown. */
    ClassInfo classInfo(String slug);

    /** Level-dependent feature values (rage, sneak attack, extra attacks) for a class at a level. */
    BuildProgression progression(String slug, int level);

    /** Spell slots by spell level for a class at a character level (levels with no slots are omitted). */
    List<SpellcastingSpec.Slot> spellSlots(String slug, int level);

    /** Experience points by challenge rating value, from the seeds. */
    Map<Double, Integer> xpByChallengeRating();
}
