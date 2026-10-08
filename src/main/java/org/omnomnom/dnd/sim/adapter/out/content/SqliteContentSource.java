package org.omnomnom.dnd.sim.adapter.out.content;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.omnomnom.dnd.sim.domain.combat.AttackKind;
import org.omnomnom.dnd.sim.domain.combat.SpellcastingSpec;
import org.omnomnom.dnd.sim.domain.content.ArmorInfo;
import org.omnomnom.dnd.sim.domain.content.BuildProgression;
import org.omnomnom.dnd.sim.domain.content.ClassInfo;
import org.omnomnom.dnd.sim.domain.content.ContentIds;
import org.omnomnom.dnd.sim.domain.content.ContentSource;
import org.omnomnom.dnd.sim.domain.content.MonsterSource;
import org.omnomnom.dnd.sim.domain.content.WeaponInfo;
import org.omnomnom.dnd.sim.domain.content.WeaponProperty;
import org.omnomnom.dnd.sim.domain.core.Ability;

/**
 * A {@link ContentSource} backed by the SRD seed database, built in memory from the SQL bundled with the service. The
 * queries are ported from {@code sim/src/content/load-db.ts}. Access is serialized (one JDBC connection), which is
 * fine because catalogs are built once at startup; callers should {@link #close()} the source afterwards to release
 * the database.
 */
public final class SqliteContentSource implements ContentSource, AutoCloseable {

    private static final Map<String, WeaponProperty> PROPERTY_BY_NAME = Map.of(
            "Finesse", WeaponProperty.FINESSE,
            "Heavy", WeaponProperty.HEAVY,
            "Light", WeaponProperty.LIGHT,
            "Two-Handed", WeaponProperty.TWO_HANDED,
            "Versatile", WeaponProperty.VERSATILE,
            "Thrown", WeaponProperty.THROWN,
            "Ammunition", WeaponProperty.AMMUNITION,
            "Loading", WeaponProperty.LOADING,
            "Reach", WeaponProperty.REACH,
            "Range", WeaponProperty.RANGE);

    private final Connection conn;

    private SqliteContentSource(Connection conn) {
        this.conn = conn;
    }

    /** Builds the seed database (a few hundred milliseconds) and returns a source reading from it. */
    public static SqliteContentSource open() {
        return new SqliteContentSource(SeedDatabase.open());
    }

    @Override
    public synchronized void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapper.map(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("seed query failed: " + sql, e);
        }
    }

    private <T> T queryOne(String sql, String what, RowMapper<T> mapper, Object... params) {
        List<T> rows = query(sql, mapper, params);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException(what);
        }
        return rows.get(0);
    }

    // ---- monsters ------------------------------------------------------------------------------

    @Override
    public synchronized List<MonsterSource> monsterSources() {
        record Head(int id, MonsterSource.MonsterRow row) {}
        List<Head> heads = query(
                """
                SELECT monsterID, monsterSlug, monsterName, monsterAc, monsterHpAvg,
                       monsterStr, monsterDex, monsterCon, monsterInt, monsterWis, monsterCha, crValue
                FROM Monster WHERE active = 1 ORDER BY monsterSlug""",
                rs -> new Head(
                        rs.getInt("monsterID"),
                        new MonsterSource.MonsterRow(
                                rs.getString("monsterSlug"), rs.getString("monsterName"), rs.getInt("monsterAc"),
                                rs.getInt("monsterHpAvg"), rs.getInt("monsterStr"), rs.getInt("monsterDex"),
                                rs.getInt("monsterCon"), rs.getInt("monsterInt"), rs.getInt("monsterWis"),
                                rs.getInt("monsterCha"), rs.getDouble("crValue"))));

        List<MonsterSource> out = new ArrayList<>(heads.size());
        for (Head h : heads) {
            var actions = query(
                    """
                    SELECT monsterActionID, actionSection, actionName, attackKind, attackBonus,
                           attackReachFt, attackRangeFt, attackRangeLongFt
                    FROM MonsterAction WHERE monsterID = ? ORDER BY sortOrder""",
                    rs -> new MonsterSource.ActionRow(
                            rs.getInt("monsterActionID"), rs.getString("actionSection"), rs.getString("actionName"),
                            rs.getString("attackKind"), nullableInt(rs, "attackBonus"), nullableInt(rs, "attackReachFt"),
                            nullableInt(rs, "attackRangeFt"), nullableInt(rs, "attackRangeLongFt")),
                    h.id());
            var damage = query(
                    """
                    SELECT d.monsterActionID, d.damageIndex, d.damageDiceCount, d.damageDiceSides,
                           d.damageBonus, d.damageAvg, d.damageTypeID
                    FROM MonsterActionDamage d JOIN MonsterAction a USING (monsterActionID)
                    WHERE a.monsterID = ?""",
                    rs -> new MonsterSource.DamageRow(
                            rs.getInt("monsterActionID"), rs.getInt("damageIndex"), nullableInt(rs, "damageDiceCount"),
                            nullableInt(rs, "damageDiceSides"), nullableInt(rs, "damageBonus"), rs.getInt("damageAvg"),
                            rs.getInt("damageTypeID")),
                    h.id());
            var saves = query("SELECT abilityID, saveBonus FROM MonsterSave WHERE monsterID = ?",
                    rs -> new MonsterSource.SaveRow(rs.getInt("abilityID"), rs.getInt("saveBonus")), h.id());
            var defenses = query(
                    "SELECT defenseKind, damageTypeID, conditionID FROM MonsterDefense WHERE monsterID = ?",
                    rs -> new MonsterSource.DefenseRow(
                            rs.getString("defenseKind"), nullableInt(rs, "damageTypeID"), nullableInt(rs, "conditionID")),
                    h.id());
            var speeds = query("SELECT speedMode, speedFt FROM MonsterSpeed WHERE monsterID = ?",
                    rs -> new MonsterSource.SpeedRow(rs.getString("speedMode"), rs.getInt("speedFt")), h.id());
            out.add(new MonsterSource(h.row(), actions, damage, saves, defenses, speeds));
        }
        return out;
    }

    // ---- equipment and classes -----------------------------------------------------------------

    @Override
    public synchronized WeaponInfo weapon(String name) {
        record Row(String name, String category, String range, int dc, int ds, int dt, Integer vc, Integer vs, Integer rn, Integer rl) {}
        Row w = queryOne(
                """
                SELECT e.equipmentName n, w.weaponCategory c, w.weaponRange r, w.damageDiceCount dc,
                       w.damageDiceSides ds, w.damageTypeID dt, w.versatileDiceCount vc,
                       w.versatileDiceSides vs, w.rangeNormalFt rn, w.rangeLongFt rl
                FROM Weapon w JOIN Equipment e USING (equipmentID) WHERE e.equipmentName = ?""",
                "weapon not found: " + name,
                rs -> new Row(rs.getString("n"), rs.getString("c"), rs.getString("r"), rs.getInt("dc"), rs.getInt("ds"),
                        rs.getInt("dt"), nullableInt(rs, "vc"), nullableInt(rs, "vs"), nullableInt(rs, "rn"),
                        nullableInt(rs, "rl")),
                name);
        List<String> propNames = query(
                """
                SELECT p.weaponPropertyName n FROM WeaponPropertyLink l
                JOIN WeaponProperty p USING (weaponPropertyID)
                JOIN Equipment e ON e.equipmentID = l.equipmentID WHERE e.equipmentName = ?""",
                rs -> rs.getString("n"), name);
        Set<WeaponProperty> props = EnumSet.noneOf(WeaponProperty.class);
        for (String p : propNames) {
            WeaponProperty mapped = PROPERTY_BY_NAME.get(p);
            if (mapped != null) {
                props.add(mapped);
            }
        }
        return new WeaponInfo(
                w.name(),
                w.category().equals("simple") ? WeaponInfo.Category.SIMPLE : WeaponInfo.Category.MARTIAL,
                w.range().equals("melee") ? AttackKind.MELEE : AttackKind.RANGED,
                w.dc(), w.ds(), ContentIds.damageTypeById(w.dt()), props, w.vc(), w.vs(), w.rn(), w.rl());
    }

    @Override
    public synchronized ArmorInfo armor(String name) {
        return queryOne(
                """
                SELECT e.equipmentName n, ar.armorCategory c, ar.armorBaseAc b, ar.armorAddsDex ad, ar.armorDexCap dc
                FROM Armor ar JOIN Equipment e USING (equipmentID) WHERE e.equipmentName = ?""",
                "armor not found: " + name,
                rs -> new ArmorInfo(
                        rs.getString("n"),
                        ArmorInfo.Category.valueOf(rs.getString("c").toUpperCase(java.util.Locale.ROOT)),
                        rs.getInt("b"), rs.getInt("ad") == 1, nullableInt(rs, "dc")),
                name);
    }

    @Override
    public synchronized ClassInfo classInfo(String slug) {
        record Head(int id, int hitDie) {}
        Head c = queryOne("SELECT classID, classHitDieSides h FROM Class WHERE classSlug = ?", "class not found: " + slug,
                rs -> new Head(rs.getInt("classID"), rs.getInt("h")), slug);
        List<Ability> saves = query("SELECT abilityID FROM ClassSavingThrow WHERE classID = ?",
                rs -> ContentIds.abilityById(rs.getInt("abilityID")), c.id());
        return new ClassInfo(slug, c.hitDie(), saves);
    }

    /** One {@code ClassLevelValue} cell as text, or null. */
    private String classValue(String slug, int level, String key) {
        List<String> v = query(
                """
                SELECT columnValue v FROM ClassLevelValue
                WHERE classID = (SELECT classID FROM Class WHERE classSlug = ?) AND level = ? AND columnKey = ?""",
                rs -> rs.getString("v"), slug, level, key);
        return v.isEmpty() ? null : v.get(0);
    }

    /** A table cell as a number; a non-numeric cell counts as zero (upstream's Number() would yield NaN). */
    private static int numberOrZero(String cell) {
        if (cell == null || cell.isEmpty()) {
            return 0;
        }
        try {
            return (int) Double.parseDouble(cell);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Extra Attack count by class and level (SRD): martials get one at 5, Fighter more. */
    private static int extraAttacksFor(String slug, int level) {
        if (!List.of("fighter", "barbarian", "monk", "paladin", "ranger").contains(slug)) {
            return 0;
        }
        if (slug.equals("fighter")) {
            if (level >= 20) return 3;
            if (level >= 11) return 2;
            if (level >= 5) return 1;
            return 0;
        }
        return level >= 5 ? 1 : 0;
    }

    @Override
    public synchronized BuildProgression progression(String slug, int level) {
        int rageUses = 0;
        int rageDamage = 0;
        int sneakDice = 0;
        if (slug.equals("barbarian")) {
            String uses = classValue(slug, level, "rageCount");
            String dmg = classValue(slug, level, "rageDamageBonus");
            rageUses = numberOrZero(uses);
            rageDamage = numberOrZero(dmg);
        }
        if (slug.equals("rogue")) {
            String sa = classValue(slug, level, "sneakAttack"); // for example "2d6"
            if (sa != null && !sa.isEmpty()) {
                try {
                    sneakDice = Integer.parseInt(sa.split("d")[0]);
                } catch (NumberFormatException ignored) {
                    // not a dice expression: leave at zero, as upstream does for NaN
                }
            }
        }
        return new BuildProgression(rageUses, rageDamage, sneakDice, extraAttacksFor(slug, level));
    }

    @Override
    public synchronized List<SpellcastingSpec.Slot> spellSlots(String slug, int level) {
        return query(
                """
                SELECT spellLevel, slots FROM ClassSpellSlot
                WHERE classID = (SELECT classID FROM Class WHERE classSlug = ?) AND level = ? AND slots > 0
                ORDER BY spellLevel""",
                rs -> new SpellcastingSpec.Slot(rs.getInt("spellLevel"), rs.getInt("slots")), slug, level);
    }

    @Override
    public synchronized Map<Double, Integer> xpByChallengeRating() {
        Map<Double, Integer> out = new LinkedHashMap<>();
        query("SELECT crValue, xp FROM ChallengeRating", rs -> out.put(rs.getDouble("crValue"), rs.getInt("xp")));
        return out;
    }
}
