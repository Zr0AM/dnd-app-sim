package org.omnomnom.dnd.sim.domain.combat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;
import org.omnomnom.dnd.sim.domain.combat.spell.BuffSpec;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.Condition;
import org.omnomnom.dnd.sim.domain.dice.Dice;
import org.omnomnom.dnd.sim.domain.rng.Rng;

/**
 * A combatant's condition flags, timed conditions and buffs, with their end-of-turn ticking and concentration
 * links. Implicit conditions (unconscious at 0 HP, exhaustion) stay with {@link Combatant}.
 */
final class StatusEffects {

    private final Set<Condition> conditions = EnumSet.noneOf(Condition.class);
    private final List<ActiveCondition> timed = new ArrayList<>();
    private final List<ActiveBuff> buffs = new ArrayList<>();

    private static final Set<Condition> DISABLING =
            EnumSet.of(Condition.PARALYZED, Condition.STUNNED, Condition.INCAPACITATED, Condition.UNCONSCIOUS);

    boolean has(Condition c) {
        return conditions.contains(c);
    }

    void add(Condition c) {
        conditions.add(c);
    }

    void remove(Condition c) {
        conditions.remove(c);
    }

    /** The explicit condition flags, without the implicit unconscious / exhaustion the facade adds. */
    Set<Condition> baseConditions() {
        return Collections.unmodifiableSet(conditions);
    }

    /** Apply a condition for a duration, with optional repeat save and concentration link. */
    void applyTimedCondition(TimedConditionSpec spec) {
        conditions.add(spec.condition());
        timed.add(new ActiveCondition(
                spec.condition(), spec.source(), spec.rounds(), spec.repeatSave(), spec.concentrationOwner()));
    }

    /** The source ids of any active timed conditions that stop this creature acting (may repeat). */
    List<String> controlSources() {
        return timed.stream().filter(t -> DISABLING.contains(t.condition)).map(t -> t.source).toList();
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
    List<Condition> tickTimedConditions(Rng rng, ToIntFunction<Ability> saveBonus) {
        List<Condition> ended = new ArrayList<>();
        for (ActiveCondition t : new ArrayList<>(timed)) {
            boolean remove = false;
            if (t.repeatSave != null) {
                int total = Dice.rollD20(rng) + saveBonus.applyAsInt(t.repeatSave.ability());
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
    void endConcentrationConditions(String casterId) {
        for (ActiveCondition t : new ArrayList<>(timed)) {
            if (casterId.equals(t.concentrationOwner)) {
                timed.remove(t);
                syncConditionFlag(t.condition);
            }
        }
    }

    /** Apply (or refresh) a beneficial buff for a duration. Re-applying the same buff refreshes it (2024 rule). */
    void applyBuff(BuffSpec spec) {
        ActiveBuff buff = new ActiveBuff(spec);
        for (int i = 0; i < buffs.size(); i++) {
            if (buffs.get(i).id.equals(spec.id())) {
                buffs.set(i, buff);
                return;
            }
        }
        buffs.add(buff);
    }

    boolean hasBuff(String id) {
        for (ActiveBuff b : buffs) {
            if (b.id.equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** Dice (with their source) to add to each attack roll, from active buffs. */
    List<BuffBonus> buffAttackBonuses() {
        return buffs.stream()
                .filter(b -> b.attackBonusDice != null)
                .map(b -> new BuffBonus(b.attackBonusDice, b.id, b.source))
                .toList();
    }

    /** Dice (with their source) to add to each saving throw, from active buffs. */
    List<BuffBonus> buffSaveBonuses() {
        return buffs.stream()
                .filter(b -> b.saveBonusDice != null)
                .map(b -> new BuffBonus(b.saveBonusDice, b.id, b.source))
                .toList();
    }

    /** Net AC bonus from active buffs. */
    int buffAcBonus() {
        int sum = 0;
        for (ActiveBuff b : buffs) {
            sum += b.acBonus;
        }
        return sum;
    }

    /** Whether a buff grants an extra action usable for a single weapon attack. */
    boolean hasExtraAttackAction() {
        for (ActiveBuff b : buffs) {
            if (b.extraAttackAction) {
                return true;
            }
        }
        return false;
    }

    /** The caster ids of buffs currently active on this creature (for attribution). */
    List<String> buffSources() {
        return buffs.stream().map(b -> b.source).toList();
    }

    /** The caster id that granted a specific active buff, or null if not present. */
    String buffSourceFor(String id) {
        for (ActiveBuff b : buffs) {
            if (b.id.equals(id)) {
                return b.source;
            }
        }
        return null;
    }

    /** End-of-turn decrement of buff durations; returns the ids that ended. */
    List<String> tickBuffs() {
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
    void endConcentrationBuffs(String casterId) {
        buffs.removeIf(b -> casterId.equals(b.concentrationOwner));
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

        ActiveBuff(BuffSpec spec) {
            this.id = spec.id();
            this.source = spec.source();
            this.roundsLeft = spec.rounds();
            this.attackBonusDice = spec.attackBonusDice();
            this.saveBonusDice = spec.saveBonusDice();
            this.acBonus = spec.acBonus();
            this.extraAttackAction = spec.extraAttackAction();
            this.concentrationOwner = spec.concentrationOwner();
        }
    }
}
