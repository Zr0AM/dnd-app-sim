package org.omnomnom.dnd.sim.testsupport;

import java.util.Comparator;
import java.util.Locale;
import org.omnomnom.dnd.sim.domain.combat.AttackKind;
import org.omnomnom.dnd.sim.domain.combat.AttackProfile;
import org.omnomnom.dnd.sim.domain.combat.ExtraDamage;
import org.omnomnom.dnd.sim.domain.content.ClassInfo;
import org.omnomnom.dnd.sim.domain.content.equipment.ArmorInfo;
import org.omnomnom.dnd.sim.domain.content.equipment.WeaponInfo;
import org.omnomnom.dnd.sim.domain.content.monster.MonsterTemplate;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.AbilityScores;
import org.omnomnom.dnd.sim.domain.dice.Dice;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Renders Java content objects in the JSON shape of the TypeScript objects they correspond to, for the parity tests.
 * Optional TypeScript properties (those that are {@code undefined}) are omitted; SQL NULLs stay explicit nulls.
 */
public final class ContentJson {

    private ContentJson() {}

    private static ObjectNode obj() {
        return TestJson.MAPPER.createObjectNode();
    }

    /** Whole-number doubles print as integers, as JavaScript does ({@code 2}, not {@code 2.0}). */
    private static void putNumber(ObjectNode n, String key, double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            n.put(key, (long) v);
        } else {
            n.put(key, v);
        }
    }

    private static void putNullable(ObjectNode n, String key, Integer v) {
        if (v == null) {
            n.putNull(key);
        } else {
            n.put(key, v);
        }
    }

    private static void putIfPresent(ObjectNode n, String key, Integer v) {
        if (v != null) {
            n.put(key, v);
        }
    }

    private static ObjectNode dice(Dice d) {
        ObjectNode n = obj();
        n.put("count", d.count());
        n.put("sides", d.sides());
        n.put("bonus", d.bonus());
        return n;
    }

    private static ObjectNode abilities(AbilityScores a) {
        ObjectNode n = obj();
        for (Ability ab : Ability.values()) {
            n.put(ab.code(), a.get(ab));
        }
        return n;
    }

    private static ObjectNode attack(AttackProfile a) {
        ObjectNode n = obj();
        n.put("name", a.name());
        n.put("kind", a.kind() == AttackKind.MELEE ? "melee" : "ranged");
        putIfPresent(n, "reachFt", a.reachFt());
        putIfPresent(n, "rangeFt", a.rangeFt());
        putIfPresent(n, "rangeLongFt", a.rangeLongFt());
        n.put("attackBonus", a.attackBonus());
        n.set("damage", dice(a.damage()));
        n.put("damageType", a.damageType().code());
        if (!a.extraDamage().isEmpty()) {
            ArrayNode extras = n.putArray("extraDamage");
            for (ExtraDamage e : a.extraDamage()) {
                ObjectNode en = extras.addObject();
                en.set("damage", dice(e.damage()));
                en.put("type", e.type().code());
            }
        }
        return n;
    }

    public static ObjectNode toJson(MonsterTemplate t) {
        ObjectNode n = obj();
        n.put("slug", t.slug());
        n.put("name", t.name());
        putNumber(n, "cr", t.cr());
        n.put("ac", t.ac());
        n.put("maxHp", t.maxHp());
        n.set("abilities", abilities(t.abilities()));
        n.put("speedFt", t.speedFt());
        ObjectNode saves = n.putObject("saveBonuses");
        t.saveBonuses().forEach((k, v) -> saves.put(k.code(), v));
        ObjectNode responses = n.putObject("damageResponses");
        t.damageResponses().forEach((k, v) -> responses.put(k.code(), v.name().toLowerCase(Locale.ROOT)));
        ArrayNode attacks = n.putArray("attacks");
        t.attacks().forEach(a -> attacks.add(attack(a)));
        ArrayNode multi = n.putArray("multiattack");
        t.multiattack().forEach(m -> {
            ObjectNode mn = multi.addObject();
            mn.put("action", m.action());
            mn.put("count", m.count());
        });
        n.put("legendaryActions", t.legendaryActions());
        return n;
    }

    public static ObjectNode toJson(WeaponInfo w) {
        ObjectNode n = obj();
        n.put("name", w.name());
        n.put("category", w.category().name().toLowerCase(Locale.ROOT));
        n.put("range", w.range() == AttackKind.MELEE ? "melee" : "ranged");
        n.put("diceCount", w.diceCount());
        n.put("diceSides", w.diceSides());
        n.put("damageType", w.damageType().code());
        ArrayNode props = n.putArray("properties");
        w.properties().stream()
                .map(p -> p.name().toLowerCase(Locale.ROOT).replace('_', '-'))
                .sorted(Comparator.naturalOrder())
                .forEach(props::add);
        putNullable(n, "versatileDiceCount", w.versatileDiceCount());
        putNullable(n, "versatileDiceSides", w.versatileDiceSides());
        putNullable(n, "rangeNormalFt", w.rangeNormalFt());
        putNullable(n, "rangeLongFt", w.rangeLongFt());
        return n;
    }

    public static ObjectNode toJson(ArmorInfo a) {
        ObjectNode n = obj();
        n.put("name", a.name());
        n.put("category", a.category().name().toLowerCase(Locale.ROOT));
        n.put("baseAc", a.baseAc());
        n.put("addsDex", a.addsDex());
        putNullable(n, "dexCap", a.dexCap());
        return n;
    }

    public static ObjectNode toJson(ClassInfo c) {
        ObjectNode n = obj();
        n.put("slug", c.slug());
        n.put("hitDieSides", c.hitDieSides());
        ArrayNode saves = n.putArray("saveProficiencies");
        c.saveProficiencies().forEach(a -> saves.add(a.code()));
        return n;
    }
}
