package org.omnomnom.dnd.sim.adapter.out.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.domain.combat.AttackKind;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.combat.Side;
import org.omnomnom.dnd.sim.domain.content.ArmorInfo;
import org.omnomnom.dnd.sim.domain.content.BuildProgression;
import org.omnomnom.dnd.sim.domain.content.ClassInfo;
import org.omnomnom.dnd.sim.domain.content.MonsterCompiler;
import org.omnomnom.dnd.sim.domain.content.MonsterMultiattack;
import org.omnomnom.dnd.sim.domain.content.MonsterSource;
import org.omnomnom.dnd.sim.domain.content.MonsterTemplate;
import org.omnomnom.dnd.sim.domain.content.WeaponInfo;
import org.omnomnom.dnd.sim.domain.content.WeaponProperty;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.DamageType;
import org.omnomnom.dnd.sim.domain.grid.Cell;
import org.omnomnom.dnd.sim.testsupport.ContentJson;
import org.omnomnom.dnd.sim.testsupport.TestJson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Checks the seed-backed content source against the real data (a port of {@code srd-monsters.spec.ts} and the
 * loader assertions in {@code walking-skeleton.spec.ts}) and against what the TypeScript loaders produce from the same
 * seeds ({@code tools/reference/gen-content.mts}): all 341 compiled monsters by hash, every weapon and armor, the
 * 12 classes, and class progression and spell slots at levels 1-19.
 */
class SqliteContentSourceTest {

    static SqliteContentSource source;
    static List<MonsterSource> sources;
    static List<MonsterTemplate> templates;
    static JsonNode ref;

    @BeforeAll
    static void load() throws Exception {
        source = SqliteContentSource.open();
        sources = source.monsterSources();
        templates = sources.stream()
                .map(s -> MonsterCompiler.compile(s, MonsterMultiattack.overridesFor(s.monster().monsterSlug())))
                .toList();
        ref = TestJson.load("/reference/content.json");
    }

    @AfterAll
    static void close() {
        source.close();
    }

    // ---- port of srd-monsters.spec.ts ----------------------------------------------------------

    @Test
    void monstersAreOrderedBySlug() {
        List<String> slugs = sources.stream().map(s -> s.monster().monsterSlug()).toList();
        assertThat(slugs).isSorted();
        assertThat(slugs).doesNotHaveDuplicates();
    }

    @Test
    void compilesEveryMonsterWithoutThrowing() {
        assertThat(templates).hasSize(341);
    }

    @Test
    void everyTemplateHasValidScalars() {
        for (MonsterTemplate t : templates) {
            assertThat(t.ac()).isPositive();
            assertThat(t.maxHp()).isPositive();
            assertThat(t.speedFt()).isGreaterThanOrEqualTo(0);
            assertThat(t.cr()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void mostMonstersHaveAtLeastOneAttack() {
        long withAttacks = templates.stream().filter(t -> !t.attacks().isEmpty()).count();
        assertThat(withAttacks).isEqualTo(321); // the rest are swarms, non-combatants and save-only attackers
        assertThat(withAttacks).isGreaterThan((long) (templates.size() * 0.7));
    }

    @Test
    void theGoblinMinionCompilesToItsKnownStatBlock() {
        MonsterTemplate goblin = templates.stream().filter(t -> t.slug().equals("goblin-minion")).findFirst().orElseThrow();
        assertThat(goblin.ac()).isEqualTo(12);
        assertThat(goblin.maxHp()).isEqualTo(7);
        assertThat(goblin.attacks().get(0).name()).isEqualTo("Dagger");
    }

    @Test
    void aSpawnedSrdMonsterCanBePlacedOnTheBoard() {
        MonsterTemplate any = templates.stream().filter(t -> !t.attacks().isEmpty()).findFirst().orElseThrow();
        Combatant c = MonsterCompiler.spawn(any, new MonsterCompiler.Placement("m", Side.ENEMY, new Cell(2, 2)));
        assertThat(c.isConscious()).isTrue();
        assertThat(c.attacks()).isNotEmpty();
    }

    // ---- loader assertions from walking-skeleton.spec.ts ---------------------------------------

    @Test
    void loadsRealClassWeaponAndArmorData() {
        ClassInfo fighter = source.classInfo("fighter");
        assertThat(fighter.hitDieSides()).isEqualTo(10);
        assertThat(fighter.saveProficiencies()).containsExactlyInAnyOrder(Ability.CON, Ability.STR);
        WeaponInfo longsword = source.weapon("Longsword");
        assertThat(longsword.name()).isEqualTo("Longsword");
        assertThat(longsword.has(WeaponProperty.VERSATILE)).isTrue();
        assertThat(source.armor("Chain Mail").baseAc()).isEqualTo(16);
    }

    @Test
    void barbarianProgressionAtLevelThreeComesFromTheSeeds() {
        BuildProgression p = source.progression("barbarian", 3);
        assertThat(p.rageUses()).isEqualTo(3);
        assertThat(p.rageDamageBonus()).isEqualTo(2);
        assertThat(p.extraAttacks()).isZero(); // no Extra Attack until level 5
    }

    @Test
    void unknownNamesAreRejectedWithAHelpfulMessage() {
        assertThatThrownBy(() -> source.weapon("Vorpal Banana")).hasMessageContaining("weapon not found");
        assertThatThrownBy(() -> source.armor("Vorpal Banana")).hasMessageContaining("armor not found");
        assertThatThrownBy(() -> source.classInfo("artificer-of-doom")).hasMessageContaining("class not found");
    }

    // ---- parity with the TypeScript loaders ----------------------------------------------------

    @Test
    void everyMonsterTemplateMatchesTheTypeScriptCompilerByHash() {
        JsonNode want = ref.get("monsterHashes");
        assertThat(want.size()).isEqualTo(341);
        List<String> mismatches = new ArrayList<>();
        for (MonsterTemplate t : templates) {
            String got = TestJson.sha256(TestJson.canonical(ContentJson.toJson(t)));
            if (!got.equals(want.get(t.slug()).asString())) {
                mismatches.add(t.slug());
            }
        }
        assertThat(mismatches).as("monsters whose compiled template differs from TypeScript").isEmpty();
    }

    @Test
    void readableExampleTemplatesMatchTheTypeScriptOutputExactly() {
        JsonNode examples = ref.get("examples");
        for (MonsterTemplate t : templates) {
            if (examples.has(t.slug())) {
                assertSameJson(ContentJson.toJson(t), examples.get(t.slug()), t.slug());
            }
        }
        assertThat(examples.size()).isEqualTo(3);
    }

    @Test
    void allWeaponsMatchTheTypeScriptLoader() {
        JsonNode want = ref.get("weapons");
        assertThat(want.size()).isGreaterThan(30);
        for (JsonNode w : want) {
            WeaponInfo got = source.weapon(w.get("name").asString());
            assertSameJson(ContentJson.toJson(got), normalizeWeapon(w), w.get("name").asString());
        }
    }

    @Test
    void allArmorsMatchTheTypeScriptLoader() {
        JsonNode want = ref.get("armors");
        assertThat(want.size()).isGreaterThan(10);
        for (JsonNode a : want) {
            ArmorInfo got = source.armor(a.get("name").asString());
            assertSameJson(ContentJson.toJson(got), a, a.get("name").asString());
        }
    }

    @Test
    void allClassesMatchTheTypeScriptLoader() {
        for (JsonNode c : ref.get("classes")) {
            ClassInfo got = source.classInfo(c.get("slug").asString());
            assertSameJson(ContentJson.toJson(got), c, c.get("slug").asString());
        }
        assertThat(ref.get("classes").size()).isEqualTo(12);
    }

    @Test
    void progressionAndSpellSlotsMatchForEveryClassAndLevel() {
        int checked = 0;
        for (var e : ref.get("progression").properties()) {
            String[] key = e.getKey().split("@");
            BuildProgression got = source.progression(key[0], Integer.parseInt(key[1]));
            JsonNode want = e.getValue();
            // TypeScript omits absent fields; the Java record uses zero for absent.
            assertThat(got.rageUses()).as(e.getKey() + " rageUses").isEqualTo(want.path("rageUses").asInt(0));
            assertThat(got.rageDamageBonus()).as(e.getKey() + " rageDamageBonus").isEqualTo(want.path("rageDamageBonus").asInt(0));
            assertThat(got.sneakAttackDice()).as(e.getKey() + " sneakAttackDice").isEqualTo(want.path("sneakAttackDice").asInt(0));
            assertThat(got.extraAttacks()).as(e.getKey() + " extraAttacks").isEqualTo(want.path("extraAttacks").asInt(0));
            checked++;
        }
        for (var e : ref.get("slots").properties()) {
            String[] key = e.getKey().split("@");
            ArrayNode got = TestJson.MAPPER.createArrayNode();
            source.spellSlots(key[0], Integer.parseInt(key[1])).forEach(s -> {
                ObjectNode n = got.addObject();
                n.put("level", s.level());
                n.put("count", s.count());
            });
            assertSameJson(got, e.getValue(), e.getKey() + " slots");
            checked++;
        }
        assertThat(checked).isEqualTo(12 * 19 * 2);
    }

    @Test
    void experiencePointsByChallengeRatingMatch() {
        var got = source.xpByChallengeRating();
        JsonNode want = ref.get("xp");
        assertThat(got).hasSize(want.size());
        want.properties().forEach(e -> assertThat(got.get(Double.parseDouble(e.getKey()))).as("CR " + e.getKey()).isEqualTo(e.getValue().asInt()));
    }

    /** Compares parsed JSON by canonical text: Jackson's equals distinguishes an int 5 from a long 5. */
    private static void assertSameJson(JsonNode actual, JsonNode expected, String what) {
        assertThat(TestJson.canonical(actual)).as(what).isEqualTo(TestJson.canonical(expected));
    }

    /** The TypeScript loader lists properties in query order and as lowercase names; compare them as sorted sets. */
    private static JsonNode normalizeWeapon(JsonNode w) {
        ObjectNode copy = (ObjectNode) w.deepCopy();
        List<String> props = new ArrayList<>();
        w.get("properties").forEach(p -> props.add(p.asString()));
        props.sort(Comparator.naturalOrder());
        ArrayNode sorted = copy.putArray("properties");
        props.forEach(sorted::add);
        // A flat-damage weapon (Blowgun) has no dice in the seeds: TypeScript carries null, Java uses 0 dice. Both roll
        // nothing (rolling zero dice draws nothing), so they behave identically.
        if (copy.get("diceCount").isNull()) copy.put("diceCount", 0);
        if (copy.get("diceSides").isNull()) copy.put("diceSides", 0);
        return copy;
    }

    @Test
    void weaponsExposeTypedPropertiesAndRanges() {
        WeaponInfo longbow = source.weapon("Longbow");
        assertThat(longbow.range()).isEqualTo(AttackKind.RANGED);
        assertThat(longbow.rangeNormalFt()).isEqualTo(150);
        assertThat(longbow.rangeLongFt()).isEqualTo(600);
        assertThat(longbow.has(WeaponProperty.TWO_HANDED)).isTrue();
        assertThat(longbow.damageType()).isEqualTo(DamageType.PIERCING);
    }
}
