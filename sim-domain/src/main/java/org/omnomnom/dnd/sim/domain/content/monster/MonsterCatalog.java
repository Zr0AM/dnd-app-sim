package org.omnomnom.dnd.sim.domain.content.monster;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.omnomnom.dnd.sim.domain.content.ContentSource;
import org.omnomnom.dnd.sim.domain.content.MonsterSource;

/**
 * Every SRD monster compiled once into an immutable {@link MonsterTemplate}, keyed by slug. Built at startup from a
 * {@link ContentSource}; afterwards simulations never touch the data source, and the catalog is safe to share across
 * threads.
 */
public final class MonsterCatalog {

    private final Map<String, MonsterTemplate> bySlug;

    private MonsterCatalog(Map<String, MonsterTemplate> bySlug) {
        this.bySlug = Map.copyOf(bySlug);
    }

    /** Compile all monsters, applying the Multiattack and legendary-action overrides. */
    public static MonsterCatalog load(ContentSource source) {
        Map<String, MonsterTemplate> out = new LinkedHashMap<>();
        for (MonsterSource src : source.monsterSources()) {
            String slug = src.monster().monsterSlug();
            out.put(slug, MonsterCompiler.compile(src, MonsterMultiattack.overridesFor(slug)));
        }
        return new MonsterCatalog(out);
    }

    /** The template for a slug, or null if unknown. */
    public MonsterTemplate find(String slug) {
        return bySlug.get(slug);
    }

    /** The template for a slug; throws if unknown. */
    public MonsterTemplate require(String slug) {
        MonsterTemplate t = bySlug.get(slug);
        if (t == null) {
            throw new IllegalArgumentException("monster not found: " + slug);
        }
        return t;
    }

    public Collection<MonsterTemplate> all() {
        return bySlug.values();
    }

    public int size() {
        return bySlug.size();
    }
}
