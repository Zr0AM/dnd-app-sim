package org.omnomnom.dnd.sim.domain.opt.genome;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.omnomnom.dnd.sim.domain.combat.AttackKind;
import org.omnomnom.dnd.sim.domain.combat.AttackProfile;
import org.omnomnom.dnd.sim.domain.combat.FeatureFactory;
import org.omnomnom.dnd.sim.domain.combat.Recharge;
import org.omnomnom.dnd.sim.domain.combat.ResourceIds;
import org.omnomnom.dnd.sim.domain.combat.ResourceSpec;
import org.omnomnom.dnd.sim.domain.combat.spell.Spell;
import org.omnomnom.dnd.sim.domain.combat.spell.SpellcastingSpec;
import org.omnomnom.dnd.sim.domain.content.BuildProgression;
import org.omnomnom.dnd.sim.domain.content.ClassInfo;
import org.omnomnom.dnd.sim.domain.content.ContentSource;
import org.omnomnom.dnd.sim.domain.content.build.SpellCatalog;
import org.omnomnom.dnd.sim.domain.content.equipment.ArmorInfo;
import org.omnomnom.dnd.sim.domain.content.equipment.Gear;
import org.omnomnom.dnd.sim.domain.content.equipment.WeaponInfo;
import org.omnomnom.dnd.sim.domain.content.feature.DarkOnesBlessingFeature;
import org.omnomnom.dnd.sim.domain.content.feature.WildShapeFeature;
import org.omnomnom.dnd.sim.domain.content.monster.MonsterCatalog;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.CoreRules;
import org.omnomnom.dnd.sim.domain.core.DamageType;
import org.omnomnom.dnd.sim.domain.dice.Dice;
import org.omnomnom.dnd.sim.domain.scenario.Scenario;
import org.omnomnom.dnd.sim.domain.scenario.ScenarioLibrary;

/**
 * The options a {@link Genome} draws from, resolved from the content source once at a given level and immutable
 * afterwards (safe to share across threads): the eight weapons, four armors, class data, caster packages, the Paladin's
 * gish spells and the scenario library the builds are scored against.
 */
public final class MartialCatalog {

    private static final List<String> WEAPON_NAMES =
            List.of(Gear.GREATAXE, Gear.GREATSWORD, Gear.LONGSWORD, Gear.RAPIER, Gear.SHORTSWORD, Gear.HANDAXE, Gear.LONGBOW, Gear.SHORTBOW);
    private static final List<String> ARMOR_NAMES = List.of(Gear.STUDDED_LEATHER_ARMOR, Gear.CHAIN_SHIRT, Gear.BREASTPLATE, Gear.CHAIN_MAIL);

    /** The SRD subclass each class takes at level 3. */
    private static final Map<BuildClass, String> SUBCLASS = new EnumMap<>(BuildClass.class);

    static {
        SUBCLASS.put(BuildClass.FIGHTER, "champion");
        SUBCLASS.put(BuildClass.BARBARIAN, "path-of-the-berserker");
        SUBCLASS.put(BuildClass.ROGUE, "thief");
        SUBCLASS.put(BuildClass.RANGER, "hunter");
        SUBCLASS.put(BuildClass.PALADIN, "oath-of-devotion");
        SUBCLASS.put(BuildClass.MONK, "warrior-of-the-open-hand");
        SUBCLASS.put(BuildClass.WIZARD, "evoker");
        SUBCLASS.put(BuildClass.CLERIC, "life-domain");
        SUBCLASS.put(BuildClass.BARD, "college-of-lore");
        SUBCLASS.put(BuildClass.SORCERER, "draconic-sorcery");
        SUBCLASS.put(BuildClass.WARLOCK, "fiend-patron");
        SUBCLASS.put(BuildClass.DRUID, "circle-of-the-land");
    }

    /** A sensible default armor for a non-barbarian martial (barbarian and monk fight unarmored). */
    private static final Map<BuildClass, String> DEFAULT_ARMOR = new EnumMap<>(BuildClass.class);

    static {
        DEFAULT_ARMOR.put(BuildClass.FIGHTER, Gear.CHAIN_MAIL);
        DEFAULT_ARMOR.put(BuildClass.BARBARIAN, Gear.CHAIN_MAIL);
        DEFAULT_ARMOR.put(BuildClass.ROGUE, Gear.STUDDED_LEATHER_ARMOR);
        DEFAULT_ARMOR.put(BuildClass.RANGER, Gear.STUDDED_LEATHER_ARMOR);
        DEFAULT_ARMOR.put(BuildClass.PALADIN, Gear.CHAIN_MAIL);
        DEFAULT_ARMOR.put(BuildClass.MONK, Gear.STUDDED_LEATHER_ARMOR);
    }

    private final int level;
    private final List<WeaponInfo> weapons;
    private final List<ArmorInfo> armors;
    private final Map<BuildClass, ClassInfo> classes;
    private final Map<BuildClass, BuildProgression> progression;
    private final Map<BuildClass, CasterPackage> casterPackages;
    private final SpellcastingSpec paladinSpells;
    private final List<Scenario> scenarios;

    /** The weapons and armors a genome picks from. */
    private record Armory(List<WeaponInfo> weapons, List<ArmorInfo> armors) {}

    private MartialCatalog(int level, Armory armory, Map<BuildClass, ClassInfo> classes,
            Map<BuildClass, BuildProgression> progression, Map<BuildClass, CasterPackage> casterPackages,
            SpellcastingSpec paladinSpells, List<Scenario> scenarios) {
        this.level = level;
        this.weapons = List.copyOf(armory.weapons());
        this.armors = List.copyOf(armory.armors());
        this.classes = Map.copyOf(classes);
        this.progression = Map.copyOf(progression);
        this.casterPackages = Map.copyOf(casterPackages);
        this.paladinSpells = paladinSpells;
        this.scenarios = List.copyOf(scenarios);
    }

    /** Resolve the catalog for a hero level (3, 5, 11 or 17 are the supported checkpoints). */
    public static MartialCatalog load(ContentSource source, MonsterCatalog monsters, int level) {
        List<WeaponInfo> weapons = WEAPON_NAMES.stream().map(source::weapon).toList();
        List<ArmorInfo> armors = ARMOR_NAMES.stream().map(source::armor).toList();
        Map<BuildClass, ClassInfo> classes = new EnumMap<>(BuildClass.class);
        Map<BuildClass, BuildProgression> progression = new EnumMap<>(BuildClass.class);
        for (BuildClass c : BuildClass.MARTIAL_CLASSES) {
            classes.put(c, source.classInfo(c.code()));
            progression.put(c, source.progression(c.code(), level));
        }

        // The Paladin is a gish: slots plus a buff and a heal. Divine Smite is a feature, not a spell here.
        SpellcastingSpec paladinSpells = new SpellcastingSpec(Ability.CHA, source.spellSlots("paladin", level), List.of(),
                List.of(SpellCatalog.BLESS, SpellCatalog.CURE_WOUNDS), false);

        Map<BuildClass, CasterPackage> packages = new EnumMap<>(BuildClass.class);
        for (BuildClass c : BuildClass.CASTER_CLASSES) {
            classes.put(c, source.classInfo(c.code()));
            packages.put(c, casterPackage(source, c, level));
        }
        List<Scenario> scenarios = ScenarioLibrary.load(monsters, source.xpByChallengeRating(), level);
        return new MartialCatalog(level, new Armory(weapons, armors), classes, progression, packages, paladinSpells, scenarios);
    }

    private static CasterPackage casterPackage(ContentSource source, BuildClass c, int level) {
        List<ResourceSpec> resources = new ArrayList<>();
        List<FeatureFactory> features = new ArrayList<>();
        Ability ability;
        List<Spell> cantrips;
        List<Spell> spells;
        String weaponName;
        String armorName;
        boolean shield = false;
        int extraHp = 0;
        Ability unarmoredAc = null;
        boolean shortRestSlots = false;
        switch (c) {
            case WIZARD -> {
                ability = Ability.INT;
                cantrips = List.of(SpellCatalog.FIRE_BOLT, SpellCatalog.RAY_OF_FROST);
                spells = List.of(SpellCatalog.BURNING_HANDS, SpellCatalog.SCORCHING_RAY, SpellCatalog.FIREBALL,
                        SpellCatalog.HOLD_PERSON, SpellCatalog.HYPNOTIC_PATTERN);
                weaponName = Gear.DAGGER;
                armorName = null; // no armor proficiency
            }
            case CLERIC -> {
                ability = Ability.WIS;
                cantrips = List.of(SpellCatalog.SACRED_FLAME);
                spells = List.of(SpellCatalog.CURE_WOUNDS, SpellCatalog.HEALING_WORD, SpellCatalog.GUIDING_BOLT);
                weaponName = Gear.MACE;
                armorName = Gear.SCALE_MAIL; // medium armor + shield
                shield = true;
            }
            case BARD -> {
                ability = Ability.CHA;
                cantrips = List.of();
                spells = List.of(SpellCatalog.BLESS, SpellCatalog.HASTE); // the buffer package
                weaponName = Gear.RAPIER;
                armorName = Gear.LEATHER_ARMOR;
            }
            case SORCERER -> {
                ability = Ability.CHA;
                cantrips = List.of(SpellCatalog.FIRE_BOLT);
                spells = List.of(SpellCatalog.BURNING_HANDS, SpellCatalog.SCORCHING_RAY, SpellCatalog.FIREBALL,
                        SpellCatalog.HOLD_PERSON); // a Draconic blaster/controller
                weaponName = Gear.DAGGER;
                armorName = null; // Draconic Resilience grants unarmored AC instead
                resources.add(new ResourceSpec(ResourceIds.SORCERY, level, null, Recharge.FULL)); // Sorcery Points = level
                extraHp = level; // Draconic Resilience: +1 per level
                unarmoredAc = Ability.CHA; // 10 + Dex + Cha when unarmored
            }
            case WARLOCK -> {
                ability = Ability.CHA;
                cantrips = List.of(SpellCatalog.ELDRITCH_BLAST); // the workhorse, with Agonizing Blast
                spells = List.of(SpellCatalog.HOLD_PERSON); // Pact Magic slots
                weaponName = Gear.DAGGER;
                armorName = Gear.LEATHER_ARMOR;
                shortRestSlots = true; // Pact Magic recharges on a short rest
                features.add(DarkOnesBlessingFeature::new);
            }
            case DRUID -> {
                ability = Ability.WIS;
                cantrips = List.of(SpellCatalog.PRODUCE_FLAME);
                spells = List.of(SpellCatalog.CURE_WOUNDS, SpellCatalog.MOONBEAM); // a versatile healer / area caster
                weaponName = Gear.MACE;
                armorName = Gear.LEATHER_ARMOR; // nonmetal light armor
                // A representative mid-tier beast form: its HP (as temp HP), AC and bite.
                AttackProfile bite = AttackProfile.builder("Bite", AttackKind.MELEE, 2 + CoreRules.proficiencyBonus(level),
                        Dice.of(2, 6, 2), DamageType.PIERCING).reachFt(5).build();
                WildShapeFeature.BeastForm form = new WildShapeFeature.BeastForm(2 * level, 13, bite);
                features.add(() -> new WildShapeFeature(form));
                resources.add(new ResourceSpec(ResourceIds.WILD_SHAPE, 2, Recharge.FULL, Recharge.FULL));
            }
            default -> throw new IllegalArgumentException("not a caster: " + c);
        }
        return new CasterPackage(
                ability, cantrips, spells, source.weapon(weaponName), armorName == null ? null : source.armor(armorName), shield,
                source.spellSlots(c.code(), level), resources, features, extraHp, unarmoredAc, shortRestSlots);
    }

    public int level() {
        return level;
    }

    public List<WeaponInfo> weapons() {
        return weapons;
    }

    public List<ArmorInfo> armors() {
        return armors;
    }

    public WeaponInfo weaponByName(String name) {
        return weapons.stream().filter(w -> w.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("weapon not in catalog: " + name));
    }

    public ArmorInfo armorByName(String name) {
        return armors.stream().filter(a -> a.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("armor not in catalog: " + name));
    }

    public ClassInfo classByName(BuildClass c) {
        ClassInfo info = classes.get(c);
        if (info == null) {
            throw new IllegalArgumentException("class not in catalog: " + c.code());
        }
        return info;
    }

    public String subclassFor(BuildClass c) {
        return SUBCLASS.get(c);
    }

    public String defaultArmorFor(BuildClass martial) {
        return DEFAULT_ARMOR.get(martial);
    }

    public BuildProgression progressionFor(BuildClass martial) {
        BuildProgression p = progression.get(martial);
        return p == null ? BuildProgression.NONE : p;
    }

    public CasterPackage casterPackageFor(BuildClass caster) {
        CasterPackage p = casterPackages.get(caster);
        if (p == null) {
            throw new IllegalArgumentException("caster package not in catalog: " + caster.code());
        }
        return p;
    }

    /** Slots plus a short spell list for a gish (the Paladin), else null. */
    public SpellcastingSpec gishSpellcastingFor(BuildClass c) {
        return c == BuildClass.PALADIN ? paladinSpells : null;
    }

    /** The scenario library a build is evaluated against. */
    public List<Scenario> scenarios() {
        return scenarios;
    }
}
