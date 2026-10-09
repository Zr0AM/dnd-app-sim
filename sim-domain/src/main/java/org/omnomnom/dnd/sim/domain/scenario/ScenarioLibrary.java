package org.omnomnom.dnd.sim.domain.scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.omnomnom.dnd.sim.domain.content.monster.MonsterCatalog;
import org.omnomnom.dnd.sim.domain.content.monster.MonsterTemplate;

/**
 * The curated library of solo-hero encounters a build is tested against: a single foe, a pack, a swarm and a mixed
 * group, on two maps. Running every build across the same varied set keeps the optimizer honest (a build that only
 * beats two goblins should not win overall). Opponents are scaled for one hero at the nearest checkpoint level at or
 * below the hero's (3, 11 or 17).
 */
public final class ScenarioLibrary {

    private ScenarioLibrary() {}

    private record Group(String slug, int count) {}

    private record Spec(String id, Difficulty difficulty, Shape shape, String mapId, List<Group> enemies) {}

    private static Spec spec(String id, Difficulty d, Shape shape, String mapId, Group... enemies) {
        return new Spec(id, d, shape, mapId, List.of(enemies));
    }

    private static Group g(String slug, int count) {
        return new Group(slug, count);
    }

    // Level 3. XP (SRD): 1/8 = 25, 1/4 = 50, 1/2 = 100, 1 = 200.
    private static final List<Spec> LEVEL_3 = List.of(
            spec("l3-pair-goblins", Difficulty.LOW, Shape.PACK, Maps.OPEN_FIELD_ID, g(MonsterSlugs.GOBLIN_WARRIOR, 2)), // 100 XP
            spec("l3-single-bugbear", Difficulty.MODERATE, Shape.SINGLE, Maps.OPEN_FIELD_ID, g("bugbear-warrior", 1)), // 200
            spec("l3-swarm-minions", Difficulty.MODERATE, Shape.SWARM, Maps.OPEN_FIELD_ID, g("goblin-minion", 5)), // 125
            spec("l3-mixed-hobgoblin", Difficulty.MODERATE, Shape.MIXED, Maps.CORRIDOR_CHOKEPOINT_ID,
                    g("hobgoblin-warrior", 1), g(MonsterSlugs.GOBLIN_WARRIOR, 2)), // 200
            spec("l3-choke-gnolls", Difficulty.MODERATE, Shape.PACK, Maps.CORRIDOR_CHOKEPOINT_ID, g("gnoll-warrior", 2))); // 200

    // Level 11: CR-appropriate single foes and small packs for ONE hero (party-scale hordes are the party harness's job).
    private static final List<Spec> LEVEL_11 = List.of(
            spec("l11-single-troll", Difficulty.MODERATE, Shape.SINGLE, Maps.OPEN_FIELD_ID, g(MonsterSlugs.TROLL, 1)),
            spec("l11-pack-owlbears", Difficulty.HIGH, Shape.PACK, Maps.OPEN_FIELD_ID, g(MonsterSlugs.OWLBEAR, 2)),
            spec("l11-swarm-wolves", Difficulty.MODERATE, Shape.SWARM, Maps.CORRIDOR_CHOKEPOINT_ID, g(MonsterSlugs.WINTER_WOLF, 3)),
            spec("l11-mixed-troll-wolf", Difficulty.HIGH, Shape.MIXED, Maps.CORRIDOR_CHOKEPOINT_ID,
                    g(MonsterSlugs.TROLL, 1), g(MonsterSlugs.WINTER_WOLF, 1)));

    // Level 17: CR 7-10 single foes and packs for ONE hero.
    private static final List<Spec> LEVEL_17 = List.of(
            spec("l17-single-hezrou", Difficulty.MODERATE, Shape.SINGLE, Maps.OPEN_FIELD_ID, g("hezrou", 1)),
            spec("l17-elite-trex", Difficulty.HIGH, Shape.SINGLE, Maps.OPEN_FIELD_ID, g("tyrannosaurus-rex", 1)),
            spec("l17-pack-trolls", Difficulty.HIGH, Shape.PACK, Maps.OPEN_FIELD_ID, g(MonsterSlugs.TROLL, 2)),
            spec("l17-mixed-troll-owlbear", Difficulty.HIGH, Shape.MIXED, Maps.CORRIDOR_CHOKEPOINT_ID,
                    g(MonsterSlugs.TROLL, 1), g(MonsterSlugs.OWLBEAR, 1)));

    private static List<Spec> specsForLevel(int level) {
        if (level >= 17) {
            return LEVEL_17;
        }
        if (level >= 11) {
            return LEVEL_11;
        }
        return LEVEL_3;
    }

    /**
     * Build the scenarios for a hero level, resolving monsters and checking each fits its map.
     *
     * @param xpByCr SRD XP by challenge rating value, from the content source
     */
    public static List<Scenario> load(MonsterCatalog monsters, Map<Double, Integer> xpByCr, int level) {
        List<Scenario> out = new ArrayList<>();
        for (Spec spec : specsForLevel(level)) {
            MapLayout layout = Maps.byId(spec.mapId());
            List<MonsterTemplate> plan = new ArrayList<>();
            int totalXp = 0;
            for (Group group : spec.enemies()) {
                MonsterTemplate template = monsters.find(group.slug());
                if (template == null) {
                    throw new IllegalArgumentException("scenario " + spec.id() + ": monster not found: " + group.slug());
                }
                for (int i = 0; i < group.count(); i++) {
                    plan.add(template);
                    totalXp += xpByCr.getOrDefault(template.cr(), 0);
                }
            }
            if (plan.size() > layout.enemyStarts().size()) {
                throw new IllegalArgumentException("scenario " + spec.id() + ": " + plan.size()
                        + " enemies but only " + layout.enemyStarts().size() + " start cells");
            }
            out.add(new Scenario(spec.id(), level, spec.difficulty(), spec.shape(), layout.id(), totalXp, layout.grid(),
                    layout.heroStart(), plan, layout.enemyStarts().subList(0, plan.size())));
        }
        return List.copyOf(out);
    }
}
