package org.omnomnom.dnd.sim.application.content;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.omnomnom.dnd.sim.domain.content.equipment.ArmorInfo;
import org.omnomnom.dnd.sim.domain.content.equipment.WeaponInfo;
import org.omnomnom.dnd.sim.domain.content.monster.MonsterTemplate;
import org.omnomnom.dnd.sim.domain.core.Coded;
import org.omnomnom.dnd.sim.domain.opt.genome.BuildClass;
import org.omnomnom.dnd.sim.domain.opt.genome.MartialCatalog;
import org.omnomnom.dnd.sim.domain.opt.report.Reports;
import org.omnomnom.dnd.sim.domain.opt.report.RolePresets;
import org.omnomnom.dnd.sim.domain.scenario.MapLayout;
import org.omnomnom.dnd.sim.domain.scenario.Maps;
import org.omnomnom.dnd.sim.domain.scenario.PartyScenarios;
import org.omnomnom.dnd.sim.domain.scenario.PartyTemplate;
import org.omnomnom.dnd.sim.domain.scenario.Scenario;

/** Read-only reference data: the CLI {@code browse} mode, plus monsters, weapons, armors, maps and party templates. */
public final class ContentService {

    public record ClassView(BuildClass slug, String subclass, String kind) {}

    public record RoleView(String name, Map<String, Double> weights, boolean partyOnly) {}

    public record EnemyView(String monsterSlug, Integer count, Integer perMember) {}

    public record ScenarioView(String id, int level, String kind, String difficulty, String shape, String mapId, Integer xp, List<EnemyView> enemies) {}

    public record MonsterView(String slug, String name, double cr, int ac, int hp, boolean hasAttack, int legendaryActions) {}

    public record MonsterPage(List<MonsterView> items, String nextCursor) {}

    public record WeaponView(String name, String category, String range, String damage, String versatileDamage, String damageType,
            List<String> properties) {}

    public record ArmorView(String name, String category, int baseAc, boolean addsDex, Integer dexCap) {}

    public record MapView(String id, int width, int height, int partyCapacity, int enemyCapacity) {}

    public record PartyTemplateView(String id, List<String> roles, String flex, int weight) {}

    private final ContentCatalogs catalogs;

    public ContentService(ContentCatalogs catalogs) {
        this.catalogs = catalogs;
    }

    public List<ClassView> classes(int level) {
        MartialCatalog cat = catalogs.level(level).martial();
        return BuildClass.ALL_CLASSES.stream().map(c -> new ClassView(c, cat.subclassFor(c), c.isCaster() ? "caster" : "martial")).toList();
    }

    public List<RoleView> roles() {
        List<RoleView> out = new ArrayList<>();
        out.add(new RoleView("equal", Reports.equalWeights(), false));
        for (String name : RolePresets.names()) {
            out.add(new RoleView(name, RolePresets.weights(name), RolePresets.PARTY_ONLY.contains(name)));
        }
        return out;
    }

    public List<ScenarioView> scenarios(int level) {
        ContentCatalogs.LevelContent content = catalogs.level(level);
        List<ScenarioView> out = new ArrayList<>();
        for (Scenario s : content.martial().scenarios()) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            s.plan().forEach(t -> counts.merge(t.slug(), 1, Integer::sum));
            out.add(new ScenarioView(s.id(), level, "solo", s.difficulty().code(), s.shape().code(), s.mapId(), s.xp(),
                    counts.entrySet().stream().map(e -> new EnemyView(e.getKey(), e.getValue(), null)).toList()));
        }
        for (PartyScenarios.Description d : PartyScenarios.describe(level)) {
            out.add(new ScenarioView(d.id(), level, "party", null, null, Maps.PARTY_FIELD_ID, null,
                    d.enemies().stream()
                            .map(g -> new EnemyView(g.monsterSlug(), g.count() > 0 ? g.count() : null, g.perMember() > 0 ? g.perMember() : null))
                            .toList()));
        }
        return out;
    }

    /**
     * A page of monsters ordered by slug.
     *
     * @param q case-insensitive substring of the name or slug, or null
     * @param cursor the last slug of the previous page (exclusive), or null
     */
    public MonsterPage monsters(String q, Double minCr, Double maxCr, int limit, String cursor) {
        String needle = q == null ? null : q.toLowerCase(Locale.ROOT);
        List<MonsterTemplate> matches = catalogs.monsters().all().stream()
                .filter(t -> needle == null || t.slug().contains(needle) || t.name().toLowerCase(Locale.ROOT).contains(needle))
                .filter(t -> minCr == null || t.cr() >= minCr)
                .filter(t -> maxCr == null || t.cr() <= maxCr)
                .filter(t -> cursor == null || t.slug().compareTo(cursor) > 0)
                .sorted(Comparator.comparing(MonsterTemplate::slug))
                .toList();
        List<MonsterTemplate> page = matches.subList(0, Math.min(limit, matches.size()));
        String next = matches.size() > limit ? page.get(page.size() - 1).slug() : null;
        return new MonsterPage(page.stream()
                .map(t -> new MonsterView(t.slug(), t.name(), t.cr(), t.ac(), t.maxHp(), !t.attacks().isEmpty(), t.legendaryActions()))
                .toList(), next);
    }

    public List<WeaponView> weapons(int level) {
        return catalogs.level(level).martial().weapons().stream().map(ContentService::view).toList();
    }

    public List<ArmorView> armors(int level) {
        return catalogs.level(level).martial().armors().stream()
                .map((ArmorInfo a) -> new ArmorView(a.name(), a.category().name().toLowerCase(Locale.ROOT), a.baseAc(), a.addsDex(), a.dexCap()))
                .toList();
    }

    public List<MapView> maps() {
        return Maps.all().stream().map(ContentService::view).toList();
    }

    public List<PartyTemplateView> partyTemplates() {
        return PartyTemplate.ALL.stream()
                .map(t -> new PartyTemplateView(t.id(), t.roles().stream().map(Coded::code).toList(), t.flex().code(), t.weight()))
                .toList();
    }

    private static String notation(int count, int sides) {
        return count + "d" + sides;
    }

    private static WeaponView view(WeaponInfo w) {
        return new WeaponView(
                w.name(),
                w.category().name().toLowerCase(Locale.ROOT),
                w.range().name().toLowerCase(Locale.ROOT),
                notation(w.diceCount(), w.diceSides()),
                w.versatileDiceCount() == null ? null : notation(w.versatileDiceCount(), w.versatileDiceSides()),
                w.damageType().code(),
                w.properties().stream().map(p -> p.name().toLowerCase(Locale.ROOT).replace('_', '-')).sorted().toList());
    }

    private static MapView view(MapLayout m) {
        return new MapView(m.id(), m.grid().width(), m.grid().height(), m.partyStarts().size(), m.enemyStarts().size());
    }
}
