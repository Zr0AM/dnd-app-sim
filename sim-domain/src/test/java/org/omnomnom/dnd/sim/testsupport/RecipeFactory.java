package org.omnomnom.dnd.sim.testsupport;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.omnomnom.dnd.sim.domain.combat.AttackKind;
import org.omnomnom.dnd.sim.domain.combat.AttackProfile;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.combat.CombatantTestAccess;
import org.omnomnom.dnd.sim.domain.combat.FeatureFactory;
import org.omnomnom.dnd.sim.domain.combat.Recharge;
import org.omnomnom.dnd.sim.domain.combat.ResourceSpec;
import org.omnomnom.dnd.sim.domain.combat.spell.Spell;
import org.omnomnom.dnd.sim.domain.combat.spell.SpellcastingSpec;
import org.omnomnom.dnd.sim.domain.content.ContentSource;
import org.omnomnom.dnd.sim.domain.content.build.BuildSpec;
import org.omnomnom.dnd.sim.domain.content.build.CasterBuildSpec;
import org.omnomnom.dnd.sim.domain.content.build.CasterCompiler;
import org.omnomnom.dnd.sim.domain.content.build.CharacterCompiler;
import org.omnomnom.dnd.sim.domain.content.build.FightingStyle;
import org.omnomnom.dnd.sim.domain.content.build.Fillers;
import org.omnomnom.dnd.sim.domain.content.build.Role;
import org.omnomnom.dnd.sim.domain.content.build.SpellCatalog;
import org.omnomnom.dnd.sim.domain.content.build.UnarmoredDefense;
import org.omnomnom.dnd.sim.domain.content.equipment.WeaponInfo;
import org.omnomnom.dnd.sim.domain.content.equipment.WeaponProperty;
import org.omnomnom.dnd.sim.domain.content.feature.DarkOnesBlessingFeature;
import org.omnomnom.dnd.sim.domain.content.feature.WildShapeFeature;
import org.omnomnom.dnd.sim.domain.content.monster.MonsterCompiler;
import org.omnomnom.dnd.sim.domain.content.monster.MonsterMultiattack;
import org.omnomnom.dnd.sim.domain.content.monster.MonsterTemplate;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.AbilityScores;
import org.omnomnom.dnd.sim.domain.core.Condition;
import org.omnomnom.dnd.sim.domain.core.CoreRules;
import org.omnomnom.dnd.sim.domain.core.DamageType;
import org.omnomnom.dnd.sim.domain.core.Side;
import org.omnomnom.dnd.sim.domain.dice.Dice;
import org.omnomnom.dnd.sim.domain.grid.Cell;
import tools.jackson.databind.JsonNode;

/**
 * Builds combatants from the build-sweep "recipes" through the real content layer (the seed data, the compilers, the
 * fillers and the class features). Mirrors {@code makeFromRecipe} in {@code tools/reference/gen-encounters.mts}.
 */
public final class RecipeFactory {

    private final ContentSource source;
    private final Map<String, MonsterTemplate> monsters = new HashMap<>();
    private final Map<Integer, Map<Role, Fillers.Filler>> fillersByLevel = new HashMap<>();

    public RecipeFactory(ContentSource source) {
        this.source = source;
        source.monsterSources().forEach(
                s -> monsters.put(s.monster().monsterSlug(), MonsterCompiler.compile(s, MonsterMultiattack.overridesFor(s.monster().monsterSlug()))));
    }

    public static boolean isRecipe(JsonNode c) {
        return c.has("martial") || c.has("caster") || c.has("filler") || c.has("monster");
    }

    private static AbilityScores scores(JsonNode a) {
        return AbilityScores.of(a.get(0).asInt(), a.get(1).asInt(), a.get(2).asInt(), a.get(3).asInt(), a.get(4).asInt(), a.get(5).asInt());
    }

    private static Side side(JsonNode c) {
        return c.get("side").asString().equals("party") ? Side.PARTY : Side.ENEMY;
    }

    private static Cell position(JsonNode c) {
        return new Cell(c.get("position").get(0).asInt(), c.get("position").get(1).asInt());
    }

    private static String optString(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asString() : null;
    }

    private WeaponInfo weapon(JsonNode w) {
        if (w.isString()) {
            return source.weapon(w.asString());
        }
        Set<WeaponProperty> props = EnumSet.noneOf(WeaponProperty.class);
        w.get("properties").forEach(p -> props.add(WeaponProperty.valueOf(p.asString().toUpperCase(Locale.ROOT).replace('-', '_'))));
        return new WeaponInfo(
                w.get("name").asString(),
                WeaponInfo.Category.valueOf(w.get("category").asString().toUpperCase(Locale.ROOT)),
                w.get("range").asString().equals("melee") ? AttackKind.MELEE : AttackKind.RANGED,
                w.get("diceCount").asInt(),
                w.get("diceSides").asInt(),
                DamageType.fromCode(w.get("damageType").asString()),
                props, null, null, null, null);
    }

    private Combatant martial(JsonNode c) {
        JsonNode b = c.get("martial");
        String cls = b.get("class").asString();
        int level = b.get("level").asInt();
        BuildSpec.Builder spec = BuildSpec.builder(cls + " hero", source.classInfo(cls), level, scores(b.get("abilities")), weapon(b.get("weapon")))
                .id(c.get("id").asString())
                .side(side(c))
                .subclass(optString(b, "subclass"))
                .twoHanded(b.get("twoHanded").asBoolean())
                .shield(b.get("shield").asBoolean())
                .progression(source.progression(cls, level))
                .position(position(c));
        if (b.hasNonNull("armor")) spec.armor(source.armor(b.get("armor").asString()));
        if (b.hasNonNull("fightingStyle")) spec.fightingStyle(FightingStyle.valueOf(b.get("fightingStyle").asString().toUpperCase(Locale.ROOT).replace('-', '_')));
        if (b.hasNonNull("unarmoredDefense")) spec.unarmoredDefense(UnarmoredDefense.valueOf(b.get("unarmoredDefense").asString().toUpperCase(Locale.ROOT)));
        if (b.path("gish").asBoolean(false)) {
            spec.spellcasting(new SpellcastingSpec(Ability.CHA, source.spellSlots(cls, level), List.of(),
                    List.of(SpellCatalog.BLESS, SpellCatalog.CURE_WOUNDS), false));
        }
        return CharacterCompiler.compile(spec.build());
    }

    private static Recharge recharge(JsonNode r, String field) {
        if (!r.has(field)) {
            return null;
        }
        JsonNode v = r.get(field);
        return v.isString() ? Recharge.FULL : Recharge.of(v.asInt());
    }

    private Combatant caster(JsonNode c) {
        JsonNode b = c.get("caster");
        String cls = b.get("class").asString();
        int level = b.get("level").asInt();
        List<ResourceSpec> resources = new ArrayList<>();
        for (JsonNode r : b.path("resources")) {
            int max = r.get("max").isString() ? level : r.get("max").asInt();
            resources.add(new ResourceSpec(r.get("id").asString(), max, recharge(r, "rechargeShort"), recharge(r, "rechargeLong")));
        }
        List<FeatureFactory> features = new ArrayList<>();
        for (JsonNode f : b.path("features")) {
            if (!f.asString().equals("dark-ones-blessing")) {
                throw new IllegalArgumentException("caster feature " + f);
            }
            features.add(DarkOnesBlessingFeature::new);
        }
        if (b.path("wildShape").asBoolean(false)) {
            AttackProfile bite = AttackProfile.builder("Bite", AttackKind.MELEE, 2 + CoreRules.proficiencyBonus(level),
                    Dice.of(2, 6, 2), DamageType.PIERCING).reachFt(5).build();
            WildShapeFeature.BeastForm form = new WildShapeFeature.BeastForm(2 * level, 13, bite);
            features.add(() -> new WildShapeFeature(form));
        }
        CasterBuildSpec.Builder spec = CasterBuildSpec.builder(cls + " hero", source.classInfo(cls), level, scores(b.get("abilities")),
                        source.weapon(b.get("weapon").asString()), Ability.fromCode(b.get("spellAbility").asString()))
                .id(c.get("id").asString())
                .side(side(c))
                .subclass(optString(b, "subclass"))
                .shield(b.get("shield").asBoolean())
                .slots(source.spellSlots(cls, level))
                .position(position(c))
                .resources(resources)
                .features(features)
                .shortRestSlots(b.path("shortRestSlots").asBoolean(false))
                .extraHp(b.path("extraHpPerLevel").asInt(0) * level);
        if (b.hasNonNull("armor")) spec.armor(source.armor(b.get("armor").asString()));
        if (b.hasNonNull("unarmoredAcAbility")) spec.unarmoredAcAbility(Ability.fromCode(b.get("unarmoredAcAbility").asString()));
        List<Spell> cantrips = new ArrayList<>();
        b.get("cantrips").forEach(id -> cantrips.add(SpellCatalog.byId(id.asString())));
        List<Spell> spells = new ArrayList<>();
        b.get("spells").forEach(id -> spells.add(SpellCatalog.byId(id.asString())));
        return CasterCompiler.compile(spec.cantrips(cantrips).spells(spells).build());
    }

    public Combatant make(JsonNode c) {
        Combatant combatant;
        if (c.has("martial")) {
            combatant = martial(c);
        } else if (c.has("caster")) {
            combatant = caster(c);
        } else if (c.has("filler")) {
            Map<Role, Fillers.Filler> fillers = fillersByLevel.computeIfAbsent(c.get("level").asInt(), l -> Fillers.load(source, l));
            combatant = fillers.get(Role.fromCode(c.get("filler").asString())).make(c.get("id").asString(), side(c), position(c));
        } else {
            combatant = MonsterCompiler.spawn(monsters.get(c.get("monster").asString()),
                    new MonsterCompiler.Placement(c.get("id").asString(), side(c), position(c)));
        }
        if (c.has("startHp")) {
            CombatantTestAccess.setHp(combatant, c.get("startHp").asInt());
        }
        c.path("conditions").forEach(cond -> combatant.addCondition(Condition.fromCode(cond.asString())));
        return combatant;
    }
}
