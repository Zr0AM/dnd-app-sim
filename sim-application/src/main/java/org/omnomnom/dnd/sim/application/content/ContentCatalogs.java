package org.omnomnom.dnd.sim.application.content;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.omnomnom.dnd.sim.application.error.UnprocessableException;
import org.omnomnom.dnd.sim.domain.content.ContentSource;
import org.omnomnom.dnd.sim.domain.content.build.Fillers;
import org.omnomnom.dnd.sim.domain.content.build.Role;
import org.omnomnom.dnd.sim.domain.content.monster.MonsterCatalog;
import org.omnomnom.dnd.sim.domain.opt.evaluation.PartyEvaluator;
import org.omnomnom.dnd.sim.domain.opt.genome.MartialCatalog;

/**
 * Every immutable catalog the service reads, built once at startup from the content source: the compiled monsters and,
 * for each supported level, the genome catalog, the reference-party fillers and the party harness. Simulations never
 * touch the data source after this; the object is safe to share across threads.
 */
public final class ContentCatalogs {

    /** The checkpoint levels content is authored for. */
    public static final List<Integer> CHECKPOINT_LEVELS = List.of(3, 5, 11, 17);

    /** The catalogs for one hero level. */
    public record LevelContent(int level, MartialCatalog martial, Map<Role, Fillers.Filler> fillers, PartyEvaluator.Harness partyHarness) {}

    private final MonsterCatalog monsters;
    private final Map<Double, Integer> xpByChallengeRating;
    private final Map<Integer, LevelContent> levels;

    private ContentCatalogs(MonsterCatalog monsters, Map<Double, Integer> xp, Map<Integer, LevelContent> levels) {
        this.monsters = monsters;
        this.xpByChallengeRating = Map.copyOf(xp);
        this.levels = Map.copyOf(levels);
    }

    public static ContentCatalogs load(ContentSource source) {
        MonsterCatalog monsters = MonsterCatalog.load(source);
        Map<Double, Integer> xp = source.xpByChallengeRating();
        Map<Integer, LevelContent> levels = new LinkedHashMap<>();
        for (int level : CHECKPOINT_LEVELS) {
            Map<Role, Fillers.Filler> fillers = Fillers.load(source, level);
            levels.put(level, new LevelContent(level, MartialCatalog.load(source, monsters, level), fillers,
                    PartyEvaluator.Harness.load(fillers, monsters, level)));
        }
        return new ContentCatalogs(monsters, xp, levels);
    }

    public MonsterCatalog monsters() {
        return monsters;
    }

    public Map<Double, Integer> xpByChallengeRating() {
        return xpByChallengeRating;
    }

    /** The catalogs for a level; throws {@link UnprocessableException} for an unsupported level. */
    public LevelContent level(int level) {
        LevelContent c = levels.get(level);
        if (c == null) {
            throw new UnprocessableException("unsupported-level", "level must be one of " + CHECKPOINT_LEVELS, "level");
        }
        return c;
    }
}
