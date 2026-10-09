package org.omnomnom.dnd.sim.domain.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.omnomnom.dnd.sim.domain.combat.spell.BuffSpec;
import org.omnomnom.dnd.sim.domain.combat.spell.Spell;
import org.omnomnom.dnd.sim.domain.combat.spell.SpellcastingSpec;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.AbilityScores;
import org.omnomnom.dnd.sim.domain.core.Condition;
import org.omnomnom.dnd.sim.domain.core.CoreRules;
import org.omnomnom.dnd.sim.domain.core.DamageResponse;
import org.omnomnom.dnd.sim.domain.core.DamageType;
import org.omnomnom.dnd.sim.domain.core.Side;
import org.omnomnom.dnd.sim.domain.core.Size;
import org.omnomnom.dnd.sim.domain.grid.Cell;
import org.omnomnom.dnd.sim.domain.rng.Rng;

/**
 * The mutable state of one creature in a fight, plus the SRD rules for taking damage, healing, temporary Hit
 * Points and the 0-HP / death-save pipeline. Attack and save rolls live in {@link AttackResolver}; this class owns
 * what happens to a creature's HP and life state as a result.
 *
 * <p>A combatant is created fresh for each run and is <strong>not thread-safe</strong>. It builds its own
 * {@link Feature} instances from the spec's factories, so no mutable state is shared between fights.
 */
public final class Combatant {

    private final String id;
    private final String name;
    private final Side side;
    private final int level;
    private final Size size;
    private final AbilityScores abilities;
    private final int ac;
    private final int maxHp;
    private final int speedFt;
    private final int proficiencyBonus;
    private final Set<Ability> saveProf;
    private final Map<Ability, Integer> saveOverride;
    private final Map<DamageType, DamageResponse> damageResponses;
    private final List<AttackProfile> attacks;
    private final List<Feature> features;
    private final int extraAttacks;
    private final ResourcePools pools;

    // Spellcasting (null / empty for non-casters).
    private final Ability spellAbility;
    private final List<Spell> cantrips;
    private final List<Spell> spells;

    private String concentratingOn;
    private String markedTarget;
    private ActiveForm activeForm;

    private final Vitals vitals;
    private final StatusEffects effects = new StatusEffects();
    private Cell position;

    public Combatant(CombatantSpec spec) {
        this.id = spec.id();
        this.name = spec.name();
        this.side = spec.side();
        this.level = spec.level();
        this.size = spec.size();
        this.abilities = spec.abilities();
        this.ac = spec.ac();
        this.maxHp = spec.maxHp();
        this.vitals = new Vitals(spec.maxHp());
        this.speedFt = spec.speedFt();
        this.proficiencyBonus = CoreRules.proficiencyBonus(spec.level());
        this.saveProf = spec.saveProficiencies();
        this.saveOverride = spec.saveBonuses();
        this.damageResponses = spec.damageResponses();
        this.position = spec.position();
        this.attacks = spec.attacks();
        this.features = spec.features().stream().map(FeatureFactory::create).toList();
        this.extraAttacks = spec.extraAttacks();
        SpellcastingSpec sc = spec.spellcasting();
        if (sc != null) {
            this.spellAbility = sc.ability();
            this.cantrips = sc.cantrips();
            this.spells = sc.spells();
        } else {
            this.spellAbility = null;
            this.cantrips = List.of();
            this.spells = List.of();
        }
        this.pools = new ResourcePools(spec.resources(), sc, spec.legendaryActions());
    }

    // ---- identity and static stats -------------------------------------------------------------

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Side side() {
        return side;
    }

    public int level() {
        return level;
    }

    public Size size() {
        return size;
    }

    public AbilityScores abilities() {
        return abilities;
    }

    public int ac() {
        return ac;
    }

    public int maxHp() {
        return maxHp;
    }

    public int speedFt() {
        return speedFt;
    }

    public int proficiencyBonus() {
        return proficiencyBonus;
    }

    public Map<DamageType, DamageResponse> damageResponses() {
        return damageResponses;
    }

    public List<AttackProfile> attacks() {
        return attacks;
    }

    /** This combatant's own feature instances. */
    public List<Feature> features() {
        return features;
    }

    public int extraAttacks() {
        return extraAttacks;
    }

    public int abilityMod(Ability ability) {
        return abilities.modifier(ability);
    }

    public int saveBonus(Ability ability) {
        Integer override = saveOverride.get(ability);
        if (override != null) {
            return override;
        }
        return abilityMod(ability) + (saveProf.contains(ability) ? proficiencyBonus : 0);
    }

    // ---- hit points and life state -------------------------------------------------------------

    public int hp() {
        return vitals.hp();
    }

    public int tempHp() {
        return vitals.tempHp();
    }

    public boolean dead() {
        return vitals.dead();
    }

    public boolean stable() {
        return vitals.stable();
    }

    public int deathSuccesses() {
        return vitals.deathSuccesses();
    }

    public int deathFailures() {
        return vitals.deathFailures();
    }

    public int exhaustionLevel() {
        return vitals.exhaustionLevel();
    }

    /** Alive and above 0 HP (not unconscious). */
    public boolean isConscious() {
        return vitals.isConscious();
    }

    /** Not dead (may be unconscious at 0 HP). */
    public boolean isAlive() {
        return vitals.isAlive();
    }

    /** At 0 HP, not dead: unconscious and dying (or stable). */
    public boolean isDying() {
        return vitals.isDying();
    }

    // Package-private mutators for the engine and tests.
    void setHp(int v) {
        vitals.setHp(v);
    }

    void setDead(boolean v) {
        vitals.setDead(v);
    }

    void setDeathFailures(int v) {
        vitals.setDeathFailures(v);
    }

    /** Grant temporary HP. Temp HP does not stack; the larger pool wins. */
    public void grantTempHp(int amount) {
        vitals.grantTempHp(amount);
    }

    public DamageOutcome takeDamage(int amount) {
        return takeDamage(amount, false);
    }

    /**
     * Apply {@code amount} damage (already mitigated for type). {@code critical} matters only when the creature is
     * at 0 HP, where a crit inflicts two death-save failures.
     */
    public DamageOutcome takeDamage(int amount, boolean critical) {
        boolean bodyHit = isConscious() && amount > 0;
        DamageOutcome outcome = vitals.takeDamage(amount, critical);
        // A Wild Shape form ends when its (temporary) Hit Points are used up.
        if (bodyHit && activeForm != null && vitals.tempHp() == 0) {
            activeForm = null;
        }
        if (outcome.dropped() && !outcome.died()) {
            effects.remove(Condition.UNCONSCIOUS); // represented by isDying()
        }
        return outcome;
    }

    /** Restore HP. Healing from 0 revives: clears dying/stable and resets death saves. Returns HP restored. */
    public int heal(int amount) {
        int before = vitals.hp();
        int healed = vitals.heal(amount);
        if (before == 0 && vitals.hp() > 0) {
            effects.remove(Condition.UNCONSCIOUS);
        }
        return healed;
    }

    /** Stabilize a dying creature (for example a successful Medicine check). */
    public void stabilize() {
        vitals.stabilize();
    }

    /**
     * Roll a death saving throw (made at the start of a turn spent at 0 HP). 10+ succeeds; a natural 20 revives at
     * 1 HP; a natural 1 is two failures; three successes stabilize; three failures kill.
     */
    public DeathSaveOutcome rollDeathSave(Rng rng) {
        DeathSaveOutcome outcome = vitals.rollDeathSave(rng);
        if (outcome.revived()) {
            effects.remove(Condition.UNCONSCIOUS);
        }
        return outcome;
    }

    /** Raise exhaustion by {@code n} levels (0-6); at level 6 the creature dies. */
    public void gainExhaustion(int n) {
        vitals.gainExhaustion(n);
    }

    // ---- position, concentration and per-fight markers -----------------------------------------

    public Cell position() {
        return position;
    }

    public void setPosition(Cell position) {
        this.position = position;
    }

    /** The spell id this creature is concentrating on, or null. */
    public String concentratingOn() {
        return concentratingOn;
    }

    public void setConcentratingOn(String spellId) {
        this.concentratingOn = spellId;
    }

    /** The id of the creature this one has marked (Hunter's Mark), or null. */
    public String markedTarget() {
        return markedTarget;
    }

    public void setMarkedTarget(String targetId) {
        this.markedTarget = targetId;
    }

    // ---- resources -----------------------------------------------------------------------------

    /** How many uses of a resource remain (0 if the pool is undefined). */
    public int resourceCount(String id) {
        return pools.resourceCount(id);
    }

    public boolean spendResource(String id) {
        return pools.spendResource(id);
    }

    /** Spend {@code n} of a resource if available; returns whether it was spent. */
    public boolean spendResource(String id, int n) {
        return pools.spendResource(id, n);
    }

    /** Restore short-rest resources (and Pact Magic slots, for a Warlock). */
    public void shortRest() {
        pools.shortRest();
    }

    /** Restore long-rest (and short-rest) resources and all spell slots. */
    public void longRest() {
        pools.longRest();
        concentratingOn = null;
        markedTarget = null;
    }

    // ---- legendary actions ---------------------------------------------------------------------

    public int legendaryMax() {
        return pools.legendaryMax();
    }

    public int legendaryRemaining() {
        return pools.legendaryRemaining();
    }

    /** Refresh legendary actions (at the start of the boss's turn). */
    public void refreshLegendary() {
        pools.refreshLegendary();
    }

    /** Spend one legendary action if available. */
    public boolean spendLegendary() {
        return pools.spendLegendary();
    }

    // ---- spellcasting --------------------------------------------------------------------------

    /** The spellcasting ability, or null for non-casters. */
    public Ability spellAbility() {
        return spellAbility;
    }

    public List<Spell> cantrips() {
        return cantrips;
    }

    public List<Spell> spells() {
        return spells;
    }

    /** Spell save DC: 8 + proficiency + spellcasting modifier (0 for non-casters). */
    public int spellSaveDc() {
        return spellAbility == null ? 0 : 8 + proficiencyBonus + abilityMod(spellAbility);
    }

    /** Spell attack bonus: proficiency + spellcasting modifier (0 for non-casters). */
    public int spellAttackBonus() {
        return spellAbility == null ? 0 : proficiencyBonus + abilityMod(spellAbility);
    }

    /** Remaining slots of a given spell level. */
    public int slotCount(int level) {
        return pools.slotCount(level);
    }

    /** The spell levels (ascending) that currently have at least one slot. */
    public List<Integer> availableSlotLevels() {
        return pools.availableSlotLevels();
    }

    /** Spend one slot of the given level; returns whether a slot was available. */
    public boolean spendSlot(int level) {
        return pools.spendSlot(level);
    }

    /** Restore all spell slots (a long rest). */
    public void restoreSlots() {
        pools.restoreSlots();
    }

    // ---- damage response -----------------------------------------------------------------------

    /**
     * The effective response to a damage type, combining static defenses with any feature-granted resistance (for
     * example Rage). Static immunity or vulnerability wins; otherwise a feature resistance upgrades a normal
     * response to resistant.
     */
    public DamageResponse damageResponseFor(DamageType type) {
        DamageResponse base = damageResponses.getOrDefault(type, DamageResponse.NORMAL);
        if (base != DamageResponse.NORMAL) {
            return base;
        }
        for (Feature f : features) {
            if (f.resistsDamage(this, type)) {
                return DamageResponse.RESISTANT;
            }
        }
        return DamageResponse.NORMAL;
    }

    // ---- conditions ----------------------------------------------------------------------------

    public boolean hasCondition(Condition c) {
        if (c == Condition.UNCONSCIOUS) {
            return isDying() || effects.has(Condition.UNCONSCIOUS);
        }
        if (c == Condition.EXHAUSTION) {
            return exhaustionLevel() > 0;
        }
        return effects.has(c);
    }

    public void addCondition(Condition c) {
        effects.add(c);
    }

    public void removeCondition(Condition c) {
        effects.remove(c);
    }

    /** The conditions currently affecting this creature, including the implicit unconscious and exhaustion. */
    public List<Condition> conditionList() {
        List<Condition> list = new ArrayList<>(effects.baseConditions());
        if (isDying() && !effects.has(Condition.UNCONSCIOUS)) {
            list.add(Condition.UNCONSCIOUS);
        }
        if (exhaustionLevel() > 0 && !effects.has(Condition.EXHAUSTION)) {
            list.add(Condition.EXHAUSTION);
        }
        return list;
    }

    /** Apply a condition for a duration, with optional repeat save and concentration link. */
    public void applyTimedCondition(TimedConditionSpec spec) {
        effects.applyTimedCondition(spec);
    }

    /** The source ids of any active timed conditions that stop this creature acting (may repeat). */
    public List<String> controlSources() {
        return effects.controlSources();
    }

    /**
     * End-of-turn processing for timed conditions: roll any repeat saves and decrement durations, removing effects
     * that end. Returns the conditions that ended this turn.
     */
    public List<Condition> tickTimedConditions(Rng rng) {
        return effects.tickTimedConditions(rng, this::saveBonus);
    }

    /** End all timed conditions sustained by {@code casterId}'s concentration. */
    public void endConcentrationConditions(String casterId) {
        effects.endConcentrationConditions(casterId);
    }

    // ---- buffs ---------------------------------------------------------------------------------

    /** Apply (or refresh) a beneficial buff for a duration. Re-applying the same buff refreshes it (2024 rule). */
    public void applyBuff(BuffSpec spec) {
        effects.applyBuff(spec);
    }

    public boolean hasBuff(String id) {
        return effects.hasBuff(id);
    }

    /** Dice (with their source) to add to each attack roll, from active buffs. */
    public List<BuffBonus> buffAttackBonuses() {
        return effects.buffAttackBonuses();
    }

    /** Dice (with their source) to add to each saving throw, from active buffs. */
    public List<BuffBonus> buffSaveBonuses() {
        return effects.buffSaveBonuses();
    }

    /** Net AC bonus from active buffs. */
    public int buffAcBonus() {
        return effects.buffAcBonus();
    }

    /** Armor Class including active buffs (Haste's +2) and any Wild Shape form. */
    public int effectiveAc() {
        int base = activeForm != null ? activeForm.ac() : ac;
        return base + buffAcBonus();
    }

    /** Whether a buff grants an extra action usable for a single weapon attack. */
    public boolean hasExtraAttackAction() {
        return effects.hasExtraAttackAction();
    }

    /** The caster ids of buffs currently active on this creature (for attribution). */
    public List<String> buffSources() {
        return effects.buffSources();
    }

    /** The caster id that granted a specific active buff, or null if not present. */
    public String buffSourceFor(String id) {
        return effects.buffSourceFor(id);
    }

    /** End-of-turn decrement of buff durations; returns the ids that ended. */
    public List<String> tickBuffs() {
        return effects.tickBuffs();
    }

    /** End all buffs sustained by {@code casterId}'s concentration. */
    public void endConcentrationBuffs(String casterId) {
        effects.endConcentrationBuffs(casterId);
    }

    // ---- Wild Shape ----------------------------------------------------------------------------

    /** The active Wild Shape form, or null when not shaped. */
    public ActiveForm activeForm() {
        return activeForm;
    }

    /** Assume a Wild Shape beast form (overrides AC and attack until its HP is gone). */
    public void enterForm(ActiveForm form) {
        this.activeForm = form;
    }

    public void clearForm() {
        this.activeForm = null;
    }

    /** The attacks to use right now: the Wild Shape form's natural attack, or the base set. */
    public List<AttackProfile> activeAttacks() {
        return activeForm != null ? List.of(activeForm.attack()) : attacks;
    }

}
