package org.omnomnom.dnd.sim.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.adapter.out.content.SqliteContentSource;

class ContentServiceTest {

    static ContentService content;

    @BeforeAll
    static void load() {
        try (SqliteContentSource source = SqliteContentSource.open()) {
            content = new ContentService(ContentCatalogs.load(source));
        }
    }

    @Test
    void pagingByCursorNeverSkipsOrRepeatsAMonster() {
        List<String> all = content.monsters(null, null, null, 400, null).items().stream().map(ContentService.MonsterView::slug).toList();
        assertThat(all).hasSize(341).isSorted();
        // Following the cursor one monster at a time visits every slug exactly once, including neighbors that differ in one
        // character or by a trailing letter.
        List<String> walked = new ArrayList<>();
        String cursor = null;
        do {
            ContentService.MonsterPage page = content.monsters(null, null, null, 1, cursor);
            page.items().forEach(m -> walked.add(m.slug()));
            cursor = page.nextCursor();
        } while (cursor != null);
        assertThat(walked).isEqualTo(all);
        // Starting after each slug yields the next one first.
        for (int i = 0; i < all.size() - 1; i++) {
            assertThat(content.monsters(null, null, null, 1, all.get(i)).items().get(0).slug()).isEqualTo(all.get(i + 1));
        }
        assertThat(content.monsters(null, null, null, 1, all.get(all.size() - 1)).items()).isEmpty();
    }

    @Test
    void thereIsNoNextCursorWhenThePageHoldsEverythingLeft() {
        int wolves = content.monsters("wolf", null, null, 400, null).items().size();
        assertThat(wolves).isGreaterThan(1);
        assertThat(content.monsters("wolf", null, null, wolves, null).nextCursor()).isNull();
        assertThat(content.monsters("wolf", null, null, wolves - 1, null).nextCursor()).isNotNull();
    }

    @Test
    void challengeRatingBoundsAreInclusive() {
        List<ContentService.MonsterView> all = content.monsters(null, null, null, 400, null).items();
        double cr = all.stream().filter(m -> m.cr() == 1.0).findFirst().orElseThrow().cr();
        assertThat(content.monsters(null, cr, cr, 400, null).items()).isNotEmpty().allMatch(m -> m.cr() == cr);
        long expected = all.stream().filter(m -> m.cr() == cr).count();
        assertThat(content.monsters(null, cr, cr, 400, null).items()).hasSize((int) expected);
    }

    @Test
    void partyScenariosReportFixedAndPerMemberCounts() {
        ContentService.ScenarioView trolls = content.scenarios(11).stream().filter(s -> s.id().equals("troll-pack")).findFirst().orElseThrow();
        assertThat(trolls.enemies()).singleElement().satisfies(e -> {
            assertThat(e.perMember()).isEqualTo(1);
            assertThat(e.count()).isNull();
        });
        ContentService.ScenarioView boss = content.scenarios(11).stream().filter(s -> s.id().equals("boss-young-dragon")).findFirst().orElseThrow();
        assertThat(boss.enemies()).extracting(ContentService.EnemyView::count).containsExactly(1, 2);
        assertThat(boss.enemies()).extracting(ContentService.EnemyView::perMember).containsOnlyNulls();
    }

    @Test
    void weaponPropertiesUseTheSeedNames() {
        ContentService.WeaponView greatsword = content.weapons(3).stream().filter(w -> w.name().equals("Greatsword")).findFirst().orElseThrow();
        assertThat(greatsword.properties()).contains("two-handed", "heavy");
        assertThat(greatsword.properties()).isSorted();
    }
}
