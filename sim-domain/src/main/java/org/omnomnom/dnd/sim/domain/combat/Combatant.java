package org.omnomnom.dnd.sim.domain.combat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
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
import org.omnomnom.dnd.sim.domain.dice.Dice;
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
    private final Map<String, ResourcePool> pools = new LinkedHashMap<>();

    // Spellcasting (null / empty for non-casters).
    private final Ability spellAbility;
    private final List<Spell> cantrips;
    private final List<Spell> spells;
    private final Map<Integer, SlotPool> slots = new LinkedHashMap<>();
    private final boolean shortRestSlots;

    private String concentratingOn;
    private String markedTarget;
    private ActiveForm activeForm;
    private final int legendaryMax;
    private int legendaryRemaining;

    private int hp;
    private int tempHp;
    private Cell position;
    private final Set<Condition> conditions = EnumSet.noneOf(Condition.class);
    private final List<ActiveCondition> timed = new ArrayList<>();
    private final List<ActiveBuff> buffs = new ArrayList<>();

    private int exhaustionLevel;
    private int deathSuccesses;
    private int deathFailures;
    private boolean stable;
    private boolean dead;

    public Combatant(CombatantSpec spec) {
        this.id = spec.id();
        this.name = spec.name();
        this.side = spec.side();
        this.level = spec.level();
        this.size = spec.size();
        this.abilities = spec.abilities();
        this.ac = spec.ac();
        this.maxHp = spec.maxHp();
        this.hp = spec.maxHp();
        this.speedFt = spec.speedFt();
        this.proficiencyBonus = CoreRules.proficiencyBonus(spec.level());
        this.saveProf = spec.saveProficiencies();
        this.saveOverride = spec.saveBonuses();
        this.damageResponses = spec.damageResponses();
        this.position = spec.position();
        this.attacks = spec.attacks();
        List<Feature> owned = new ArrayList<>(spec.features().size());
        for (FeatureFactory f : spec.features()) {
            owned.add(f.create());
        }
        this.features = Collections.unmodifiableList(owned);
        this.extraAttacks = spec.extraAttacks();
        this.legendaryMax = spec.legendaryActions();
        this.legendaryRemaining = this.legendaryMax;
        SpellcastingSpec sc = spec.spellcasting();
        this.shortRestSlots = sc != null && sc.shortRestSlots();
        if (sc != null) {
            this.spellAbility = sc.ability();
            this.cantrips = sc.cantrips();
            this.spells = sc.spells();
            for (SpellcastingSpec.Slot s : sc.slots()) {
                slots.put(s.level(), new SlotPool(s.count(), s.count()));
            }
        } else {
            this.spellAbility = null;
            this.cantrips = List.of();
            this.spells = List.of();
        }
        for (ResourceSpec r : spec.resources()) {
            pools.put(r.id(), new ResourcePool(r.max(), r.max(), r.rechargeShort(), r.rechargeLong()));
        }
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
        return hp;
    }

    public int tempHp() {
        return tempHp;
    }

    public boolean dead() {
        return dead;
    }

    public boolean stable() {
        return stable;
    }

    public int deathSuccesses() {
        return deathSuccesses;
    }

    public int deathFailures() {
        return deathFailures;
    }

    public int exhaustionLevel() {
        return exhaustionLevel;
    }

    /** Alive and above 0 HP (not unconscious). */
    public boolean isConscious() {
        return !dead && hp > 0;
    }

    /** Not dead (may be unconscious at 0 HP). */
    public boolean isAlive() {
        return !dead;
    }

    /** At 0 HP, not dead: unconscious and dying (or stable). */
    public boolean isDying() {
        return !dead && hp == 0;
    }

    // Package-private mutators for the engine and tests.
    void setHp(int v) {
        this.hp = v;
    }

    void setDead(boolean v) {
        this.dead = v;
    }

    void setDeathFailures(int v) {
        this.deathFailures = v;
    }

    /** Grant temporary HP. Temp HP does not stack; the larger pool wins. */
    public void grantTempHp(int amount) {
        if (amount > tempHp) {
            tempHp = amount;
        }
    }

    public DamageOutcome takeDamage(int amount) {
        return takeDamage(amount, false);
    }

    /**
     * Apply {@code amount} damage (already mitigated for type). {@code critical} matters only when the creature is
     * at 0 HP, where a crit inflicts two death-save failures.
     */
    public DamageOutcome takeDamage(int amount, boolean critical) {
        if (dead || amount <= 0) {
            return DamageOutcome.NONE;
        }

        // Damage taken while already at 0 HP causes death-save failures, not HP loss.
        if (hp == 0) {
            int fails = critical ? 2 : 1;
            stable = false;
            if (amount >= maxHp) {
                dead = true;
                return new DamageOutcome(0, 0, false, true, fails);
            }
            deathFailures += fails;
            boolean died = deathFailures >= 3;
            if (died) {
                dead = true;
            }
            return new DamageOutcome(0, 0, false, died, fails);
        }

        int absorbedByTemp = Math.min(tempHp, amount);
        tempHp -= absorbedByTemp;
        // A Wild Shape form ends when its (temporary) Hit Points are used up.
        if (activeForm != null && tempHp == 0) {
            activeForm = null;
        }
        int toHp = amount - absorbedByTemp;
        int newHp = hp - toHp;

        if (newHp > 0) {
            hp = newHp;
            return new DamageOutcome(toHp, absorbedByTemp, false, false, 0);
        }

        // Reduced to 0. Massive damage: if the overflow equals or exceeds max HP, die.
        int overflow = -newHp;
        hp = 0;
        if (overflow >= maxHp) {
            dead = true;
            return new DamageOutcome(maxHp, absorbedByTemp, true, true, 0);
        }
        // Drop to 0: unconscious, death saves reset.
        deathSuccesses = 0;
        deathFailures = 0;
        stable = false;
        conditions.remove(Condition.UNCONSCIOUS); // represented by isDying()
        return new DamageOutcome(toHp, absorbedByTemp, true, false, 0);
    }

    /** Restore HP. Healing from 0 revives: clears dying/stable and resets death saves. Returns HP restored. */
    public int heal(int amount) {
        if (dead || amount <= 0) {
            return 0;
        }
        int before = hp;
        hp = Math.min(maxHp, hp + amount);
        int healed = hp - before;
        if (before == 0 && hp > 0) {
            deathSuccesses = 0;
            deathFailures = 0;
            stable = false;
            conditions.remove(Condition.UNCONSCIOUS);
        }
        return healed;
    }

    /** Stabilize a dying creature (for example a successful Medicine check). */
    public void stabilize() {
        if (isDying()) {
            stable = true;
        }
    }

    /**
     * Roll a death saving throw (made at the start of a turn spent at 0 HP). 10+ succeeds; a natural 20 revives at
     * 1 HP; a natural 1 is two failures; three successes stabilize; three failures kill.
     */
    public DeathSaveOutcome rollDeathSave(Rng rng) {
        if (!isDying() || stable) {
            return new DeathSaveOutcome(0, false, stable, dead, false);
        }
        int d20 = Dice.rollD20(rng);
        if (d20 == 20) {
            heal(1);
            return new DeathSaveOutcome(d20, true, false, false, true);
        }
        if (d20 == 1) {
            deathFailures += 2;
        } else if (d20 >= 10) {
            deathSuccesses += 1;
        } else {
            deathFailures += 1;
        }
        if (deathFailures >= 3) {
            dead = true;
            return new DeathSaveOutcome(d20, false, false, true, false);
        }
        if (deathSuccesses >= 3) {
            stable = true;
            return new DeathSaveOutcome(d20, d20 >= 10, true, false, false);
        }
        return new DeathSaveOutcome(d20, d20 >= 10, false, false, false);
    }

    /** Raise exhaustion by {@code n} levels (0-6); at level 6 the creature dies. */
    public void gainExhaustion(int n) {
        exhaustionLevel = Math.max(0, Math.min(6, exhaustionLevel + n));
        if (exhaustionLevel >= 6) {
            dead = true;
        }
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
        ResourcePool pool = pools.get(id);
        return pool == null ? 0 : pool.current;
    }

    public boolean spendResource(String id) {
        return spendResource(id, 1);
    }

    /** Spend {@code n} of a resource if available; returns whether it was spent. */
    public boolean spendResource(String id, int n) {
        ResourcePool pool = pools.get(id);
        if (pool == null || pool.current < n) {
            return false;
        }
        pool.current -= n;
        return true;
    }

    /** Restore short-rest resources (and Pact Magic slots, for a Warlock). */
    public void shortRest() {
        for (ResourcePool pool : pools.values()) {
            pool.current = pool.rechargeShort.applyTo(pool.current, pool.max);
        }
        if (shortRestSlots) {
            restoreSlots();
        }
    }

    /** Restore long-rest (and short-rest) resources and all spell slots. */
    public void longRest() {
        for (ResourcePool pool : pools.values()) {
            pool.current = pool.rechargeShort.applyTo(pool.current, pool.max);
        }
        for (ResourcePool pool : pools.values()) {
            pool.current = pool.rechargeLong.applyTo(pool.current, pool.max);
        }
        restoreSlots();
        concentratingOn = null;
        markedTarget = null;
    }

    // ---- legendary actions ---------------------------------------------------------------------

    public int legendaryMax() {
        return legendaryMax;
    }

    public int legendaryRemaining() {
        return legendaryRemaining;
    }

    /** Refresh legendary actions (at the start of the boss's turn). */
    public void refreshLegendary() {
        legendaryRemaining = legendaryMax;
    }

    /** Spend one legendary action if available. */
    public boolean spendLegendary() {
        if (legendaryRemaining <= 0) {
            return false;
        }
        legendaryRemaining -= 1;
        return true;
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
        SlotPool pool = slots.get(level);
        return pool == null ? 0 : pool.current;
    }

    /** The spell levels (ascending) that currently have at least one slot. */
    public List<Integer> availableSlotLevels() {
        return slots.entrySet().stream()
                .filter(e -> e.getValue().current > 0)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    /** Spend one slot of the given level; returns whether a slot was available. */
    public boolean spendSlot(int level) {
        SlotPool pool = slots.get(level);
        if (pool == null || pool.current <= 0) {
            return false;
        }
        pool.current -= 1;
        return true;
    }

    /** Restore all spell slots (a long rest). */
    public void restoreSlots() {
        for (SlotPool pool : slots.values()) {
            pool.current = pool.max;
        }
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
            return isDying() || conditions.contains(Condition.UNCONSCIOUS);
        }
        if (c == Condition.EXHAUSTION) {
            return exhaustionLevel > 0;
        }
        return conditions.contains(c);
    }

    public void addCondition(Condition c) {
        conditions.add(c);
    }

    public void removeCondition(Condition c) {
        conditions.remove(c);
    }

    /** The conditions currently affecting this creature, including the implicit unconscious and exhaustion. */
    public List<Condition> conditionList() {
        List<Condition> list = new ArrayList<>(conditions);
        if (isDying() && !conditions.contains(Condition.UNCONSCIOUS)) {
            list.add(Condition.UNCONSCIOUS);
        }
        if (exhaustionLevel > 0 && !conditions.contains(Condition.EXHAUSTION)) {
            list.add(Condition.EXHAUSTION);
        }
        return list;
    }

    /** Apply a condition for a duration, with optional repeat save and concentration link. */
    public void applyTimedCondition(TimedConditionSpec spec) {
        conditions.add(spec.condition());
        timed.add(new ActiveCondition(
                spec.condition(), spec.source(), spec.rounds(), spec.repeatSave(), spec.concentrationOwner()));
    }

    private static final Set<Condition> DISABLING =
            EnumSet.of(Condition.PARALYZED, Condition.STUNNED, Condition.INCAPACITATED, Condition.UNCONSCIOUS);

    /** The source ids of any active timed conditions that stop this creature acting (may repeat). */
    public List<String> controlSources() {
        List<String> out = new ArrayList<>();
        for (ActiveCondition t : timed) {
            if (DISABLING.contains(t.condition)) {
                out.add(t.source);
            }
        }
        return out;
    }

    /** Remove the base flag for a condition if no remaining timed entry grants it. */
    private void syncConditionFlag(Condition c) {
        for (ActiveCondition t : timed) {
            if (t.condition == c) {
                return;
            }
        }
        conditions.remove(c);
    }

    /**
     * End-of-turn processing for timed conditions: roll any repeat saves and decrement durations, removing effects
     * that end. Returns the conditions that ended this turn.
     */
    public List<Condition> tickTimedConditions(Rng rng) {
        List<Condition> ended = new ArrayList<>();
        for (ActiveCondition t : new ArrayList<>(timed)) {
            boolean remove = false;
            if (t.repeatSave != null) {
                int total = Dice.rollD20(rng) + saveBonus(t.repeatSave.ability());
                if (t.repeatSave.endsOnSuccess() && total >= t.repeatSave.dc()) {
                    remove = true;
                }
            }
            t.roundsLeft -= 1;
            if (t.roundsLeft <= 0) {
                remove = true;
            }
            if (remove) {
                timed.remove(t);
                syncConditionFlag(t.condition);
                ended.add(t.condition);
            }
        }
        return ended;
    }

    /** End all timed conditions sustained by {@code casterId}'s concentration. */
    public void endConcentrationConditions(String casterId) {
        for (ActiveCondition t : new ArrayList<>(timed)) {
            if (casterId.equals(t.concentrationOwner)) {
                timed.remove(t);
                syncConditionFlag(t.condition);
            }
        }
    }

    // ---- buffs ---------------------------------------------------------------------------------

    /** Apply (or refresh) a beneficial buff for a duration. Re-applying the same buff refreshes it (2024 rule). */
    public void applyBuff(BuffSpec spec) {
        ActiveBuff buff = new ActiveBuff(
                spec.id(), spec.source(), spec.rounds(), spec.attackBonusDice(), spec.saveBonusDice(),
                spec.acBonus(), spec.extraAttackAction(), spec.concentrationOwner());
        for (int i = 0; i < buffs.size(); i++) {
            if (buffs.get(i).id.equals(spec.id())) {
                buffs.set(i, buff);
                return;
            }
        }
        buffs.add(buff);
    }

    public boolean hasBuff(String id) {
        for (ActiveBuff b : buffs) {
            if (b.id.equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** Dice (with their source) to add to each attack roll, from active buffs. */
    public List<BuffBonus> buffAttackBonuses() {
        List<BuffBonus> out = new ArrayList<>();
        for (ActiveBuff b : buffs) {
            if (b.attackBonusDice != null) {
                out.add(new BuffBonus(b.attackBonusDice, b.id, b.source));
            }
        }
        return out;
    }

    /** Dice (with their source) to add to each saving throw, from active buffs. */
    public List<BuffBonus> buffSaveBonuses() {
        List<BuffBonus> out = new ArrayList<>();
        for (ActiveBuff b : buffs) {
            if (b.saveBonusDice != null) {
                out.add(new BuffBonus(b.saveBonusDice, b.id, b.source));
            }
        }
        return out;
    }

    /** Net AC bonus from active buffs. */
    public int buffAcBonus() {
        int sum = 0;
        for (ActiveBuff b : buffs) {
            sum += b.acBonus;
        }
        return sum;
    }

    /** Armor Class including active buffs (Haste's +2) and any Wild Shape form. */
    public int effectiveAc() {
        int base = activeForm != null ? activeForm.ac() : ac;
        return base + buffAcBonus();
    }

    /** Whether a buff grants an extra action usable for a single weapon attack. */
    public boolean hasExtraAttackAction() {
        for (ActiveBuff b : buffs) {
            if (b.extraAttackAction) {
                return true;
            }
        }
        return false;
    }

    /** The caster ids of buffs currently active on this creature (for attribution). */
    public List<String> buffSources() {
        List<String> out = new ArrayList<>();
        for (ActiveBuff b : buffs) {
            out.add(b.source);
        }
        return out;
    }

    /** The caster id that granted a specific active buff, or null if not present. */
    public String buffSourceFor(String id) {
        for (ActiveBuff b : buffs) {
            if (b.id.equals(id)) {
                return b.source;
            }
        }
        return null;
    }

    /** End-of-turn decrement of buff durations; returns the ids that ended. */
    public List<String> tickBuffs() {
        List<String> ended = new ArrayList<>();
        for (ActiveBuff b : new ArrayList<>(buffs)) {
            b.roundsLeft -= 1;
            if (b.roundsLeft <= 0) {
                buffs.remove(b);
                ended.add(b.id);
            }
        }
        return ended;
    }

    /** End all buffs sustained by {@code casterId}'s concentration. */
    public void endConcentrationBuffs(String casterId) {
        buffs.removeIf(b -> casterId.equals(b.concentrationOwner));
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

    // ---- internal state holders ----------------------------------------------------------------

    private static final class ResourcePool {
        int current;
        final int max;
        final Recharge rechargeShort;
        final Recharge rechargeLong;

        ResourcePool(int current, int max, Recharge rechargeShort, Recharge rechargeLong) {
            this.current = current;
            this.max = max;
            this.rechargeShort = rechargeShort;
            this.rechargeLong = rechargeLong;
        }
    }

    private static final class SlotPool {
        int current;
        final int max;

        SlotPool(int current, int max) {
            this.current = current;
            this.max = max;
        }
    }

    private static final class ActiveCondition {
        final Condition condition;
        final String source;
        int roundsLeft;
        final RepeatSave repeatSave;
        final String concentrationOwner;

        ActiveCondition(Condition condition, String source, int roundsLeft, RepeatSave repeatSave, String concentrationOwner) {
            this.condition = condition;
            this.source = source;
            this.roundsLeft = roundsLeft;
            this.repeatSave = repeatSave;
            this.concentrationOwner = concentrationOwner;
        }
    }

    private static final class ActiveBuff {
        final String id;
        final String source;
        int roundsLeft;
        final Dice attackBonusDice;
        final Dice saveBonusDice;
        final int acBonus;
        final boolean extraAttackAction;
        final String concentrationOwner;

        ActiveBuff(
                String id, String source, int roundsLeft, Dice attackBonusDice, Dice saveBonusDice, int acBonus,
                boolean extraAttackAction, String concentrationOwner) {
            this.id = id;
            this.source = source;
            this.roundsLeft = roundsLeft;
            this.attackBonusDice = attackBonusDice;
            this.saveBonusDice = saveBonusDice;
            this.acBonus = acBonus;
            this.extraAttackAction = extraAttackAction;
            this.concentrationOwner = concentrationOwner;
        }
    }
}
