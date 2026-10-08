package org.omnomnom.dnd.sim.domain.content;

import java.util.List;
import java.util.Map;
import org.omnomnom.dnd.sim.domain.content.MonsterTemplate.MultiattackEntry;

/**
 * Monster Multiattack counts and legendary actions, hand-authored from the 2024 SRD stat blocks because the structured
 * data stores a single attack per action and the Multiattack trait lives in prose. Centralized so every loader
 * compiles a monster with the same overrides; a monster with no entry simply attacks once. Counts are a fidelity-tier
 * approximation (the common bruiser pattern), as upstream.
 */
public final class MonsterMultiattack {

    private static final Map<String, List<MultiattackEntry>> MULTIATTACK = Map.ofEntries(
            // Brutes and beasts.
            Map.entry("troll", List.of(new MultiattackEntry("Rend", 3))),
            Map.entry("owlbear", List.of(new MultiattackEntry("Rend", 2))),
            Map.entry("tyrannosaurus-rex", List.of(new MultiattackEntry("Bite", 1), new MultiattackEntry("Tail", 1))),
            // Giants: two weapon attacks.
            Map.entry("hill-giant", List.of(new MultiattackEntry("Tree Club", 2))),
            Map.entry("stone-giant", List.of(new MultiattackEntry("Stone Club", 2))),
            Map.entry("frost-giant", List.of(new MultiattackEntry("Frost Axe", 2))),
            Map.entry("fire-giant", List.of(new MultiattackEntry("Flame Sword", 2))),
            Map.entry("storm-giant", List.of(new MultiattackEntry("Storm Sword", 2))),
            // Dragons: three Rend attacks (the breath weapon is deferred).
            Map.entry("young-red-dragon", List.of(new MultiattackEntry("Rend", 3))),
            Map.entry("adult-red-dragon", List.of(new MultiattackEntry("Rend", 3))),
            // Lesser packs reused at high level.
            Map.entry("winter-wolf", List.of(new MultiattackEntry("Bite", 1))));

    /** Legendary actions per round (a boss acting between other creatures' turns). */
    private static final Map<String, Integer> LEGENDARY = Map.of("young-red-dragon", 3, "adult-red-dragon", 3);

    private MonsterMultiattack() {}

    /** The compile-time overrides (Multiattack and legendary actions) for a monster slug. */
    public static MonsterOverrides overridesFor(String slug) {
        return new MonsterOverrides(MULTIATTACK.getOrDefault(slug, List.of()), LEGENDARY.getOrDefault(slug, 0));
    }
}
