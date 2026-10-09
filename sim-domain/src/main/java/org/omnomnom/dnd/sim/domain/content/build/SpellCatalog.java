package org.omnomnom.dnd.sim.domain.content.build;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.omnomnom.dnd.sim.domain.combat.spell.Spell;
import org.omnomnom.dnd.sim.domain.combat.spell.Spell.CastingTime;
import org.omnomnom.dnd.sim.domain.combat.spell.SpellKind;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.Condition;
import org.omnomnom.dnd.sim.domain.core.DamageType;
import org.omnomnom.dnd.sim.domain.dice.Dice;

/**
 * Authored spells for levels 3-5 and up, verified against the SRD 5.2.1 text. The numbers are hand-entered (never
 * parsed), per the effect-format spec: cantrips scale by caster level, leveled spells by the slot used. Covers the
 * damage, healing, control and buff families the caster roles exercise. Spells are immutable and shared by all fights.
 */
public final class SpellCatalog {

    // ---- Cantrips ------------------------------------------------------------------------------

    public static final Spell FIRE_BOLT = new Spell("fire-bolt", "Fire Bolt", 0, CastingTime.ACTION, 120, false,
            SpellKind.AttackDamage.of(Spell.cantripDice(1, 10), DamageType.FIRE));

    public static final Spell RAY_OF_FROST = new Spell("ray-of-frost", "Ray of Frost", 0, CastingTime.ACTION, 60, false,
            SpellKind.AttackDamage.of(Spell.cantripDice(1, 8), DamageType.COLD));

    /**
     * Eldritch Blast with the Agonizing Blast invocation: a ranged spell attack firing 1d10 force per beam, with the
     * warlock's spellcasting modifier added to each beam. The beam count rises with character level (1 / 2 / 3 / 4 at
     * levels 1 / 5 / 11 / 17), not slot level, so it is modeled with {@code beams} rather than the upcast-ray path.
     */
    public static final Spell ELDRITCH_BLAST = new Spell("eldritch-blast", "Eldritch Blast", 0, CastingTime.ACTION, 120, false,
            SpellKind.AttackDamage.of((slot, level) -> Dice.of(1, 10), DamageType.FORCE)
                    .withBeams(level -> level >= 17 ? 4 : level >= 11 ? 3 : level >= 5 ? 2 : 1)
                    .withAddSpellMod(true));

    public static final Spell SACRED_FLAME = new Spell("sacred-flame", "Sacred Flame", 0, CastingTime.ACTION, 60, false,
            SpellKind.SaveDamage.of(Ability.DEX, Spell.cantripDice(1, 8), DamageType.RADIANT, SpellKind.OnSuccess.NONE));

    /** Produce Flame (Druid cantrip): a ranged spell attack for 1d8 fire, scaling by level. */
    public static final Spell PRODUCE_FLAME = new Spell("produce-flame", "Produce Flame", 0, CastingTime.ACTION, 60, false,
            SpellKind.AttackDamage.of(Spell.cantripDice(1, 8), DamageType.FIRE));

    // ---- Level 1 -------------------------------------------------------------------------------

    public static final Spell GUIDING_BOLT = new Spell("guiding-bolt", "Guiding Bolt", 1, CastingTime.ACTION, 120, false,
            SpellKind.AttackDamage.of(Spell.upcastDice(1, 4, 6), DamageType.RADIANT));

    public static final Spell BURNING_HANDS = new Spell("burning-hands", "Burning Hands", 1, CastingTime.ACTION, 15, false,
            SpellKind.SaveDamage.of(Ability.DEX, Spell.upcastDice(1, 3, 6), DamageType.FIRE, SpellKind.OnSuccess.HALF)
                    .withAoe(15, true));

    // ---- Level 2 -------------------------------------------------------------------------------

    public static final Spell SCORCHING_RAY = new Spell("scorching-ray", "Scorching Ray", 2, CastingTime.ACTION, 120, false,
            SpellKind.AttackDamage.of((slot, level) -> Dice.of(2, 6), DamageType.FIRE).withRays(3).withRaysPerUpcast(1));

    /**
     * Moonbeam (Druid): a concentration beam dealing 2d10 radiant (+1d10 per slot above 2nd) on a failed Con save, half
     * on success. The 2024 spell is a persistent zone; modeled as a single Con-save area hit (no re-trigger on later
     * turns), a documented simplification.
     */
    public static final Spell MOONBEAM = new Spell("moonbeam", "Moonbeam", 2, CastingTime.ACTION, 120, true,
            SpellKind.SaveDamage.of(Ability.CON, Spell.upcastDice(2, 2, 10), DamageType.RADIANT, SpellKind.OnSuccess.HALF)
                    .withAoe(5, false));

    // ---- Level 3 -------------------------------------------------------------------------------

    public static final Spell FIREBALL = new Spell("fireball", "Fireball", 3, CastingTime.ACTION, 150, false,
            SpellKind.SaveDamage.of(Ability.DEX, Spell.upcastDice(3, 8, 6), DamageType.FIRE, SpellKind.OnSuccess.HALF)
                    .withAoe(20, false));

    // ---- Healing -------------------------------------------------------------------------------

    public static final Spell CURE_WOUNDS = new Spell("cure-wounds", "Cure Wounds", 1, CastingTime.ACTION, 5, false, // touch
            new SpellKind.Heal(Spell.upcastDice(1, 2, 8, 2), true));

    public static final Spell HEALING_WORD = new Spell("healing-word", "Healing Word", 1, CastingTime.BONUS, 60, false,
            new SpellKind.Heal((slot, level) -> Dice.of(2, 4), true));

    // ---- Control (save-or-condition) -----------------------------------------------------------

    public static final Spell HOLD_PERSON = new Spell("hold-person", "Hold Person", 2, CastingTime.ACTION, 60, true,
            new SpellKind.Control(Ability.WIS, Condition.PARALYZED, 10, true, null, "humanoid"));

    /**
     * Hypnotic Pattern, approximated as Incapacitated (the charmed + incapacitated effect): an under-estimate, since a
     * hit does not end it here, only the repeat save does. A 30-foot cube is modeled as a 15-foot radius.
     */
    public static final Spell HYPNOTIC_PATTERN = new Spell("hypnotic-pattern", "Hypnotic Pattern", 3, CastingTime.ACTION, 120, true,
            new SpellKind.Control(Ability.WIS, Condition.INCAPACITATED, 10, true, 15, null));

    // ---- Buffs ---------------------------------------------------------------------------------

    public static final Spell BLESS = new Spell("bless", "Bless", 1, CastingTime.ACTION, 30, true,
            new SpellKind.Buff("bless", 3, 10, Dice.of(1, 4), Dice.of(1, 4), 0, false));

    /** Haste: +2 AC and an extra attack action. Doubled speed and Dex-save advantage are omitted (little effect here). */
    public static final Spell HASTE = new Spell("haste", "Haste", 3, CastingTime.ACTION, 30, true,
            new SpellKind.Buff("haste", 1, 10, null, null, 2, true));

    // ---- Lookup and groupings ------------------------------------------------------------------

    /** Damage cantrips available to author-driven caster builds. */
    public static final List<Spell> DAMAGE_CANTRIPS = List.of(FIRE_BOLT, RAY_OF_FROST, SACRED_FLAME);
    public static final List<Spell> DAMAGE_SPELLS = List.of(GUIDING_BOLT, BURNING_HANDS, SCORCHING_RAY, FIREBALL);
    public static final List<Spell> HEALING_SPELLS = List.of(CURE_WOUNDS, HEALING_WORD);
    public static final List<Spell> CONTROL_SPELLS = List.of(HOLD_PERSON, HYPNOTIC_PATTERN);
    public static final List<Spell> BUFF_SPELLS = List.of(BLESS, HASTE);

    private static final Map<String, Spell> BY_ID = new LinkedHashMap<>();

    static {
        for (Spell s : List.of(FIRE_BOLT, RAY_OF_FROST, ELDRITCH_BLAST, SACRED_FLAME, PRODUCE_FLAME, GUIDING_BOLT,
                BURNING_HANDS, SCORCHING_RAY, MOONBEAM, FIREBALL, CURE_WOUNDS, HEALING_WORD, HOLD_PERSON,
                HYPNOTIC_PATTERN, BLESS, HASTE)) {
            BY_ID.put(s.id(), s);
        }
    }

    private SpellCatalog() {}

    /** Every catalog spell, keyed by id, in declaration order. */
    public static Map<String, Spell> all() {
        return java.util.Collections.unmodifiableMap(BY_ID);
    }

    public static Spell byId(String id) {
        Spell s = BY_ID.get(id);
        if (s == null) {
            throw new IllegalArgumentException("unknown spell: " + id);
        }
        return s;
    }
}
