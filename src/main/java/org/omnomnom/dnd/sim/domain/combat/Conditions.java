package org.omnomnom.dnd.sim.domain.combat;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.Condition;
import org.omnomnom.dnd.sim.domain.dice.Advantage;

/**
 * Mechanical effects of the 15 conditions (SRD Rules Glossary), reduced to the queries the engine needs: how a
 * condition changes attack rolls, saves, speed, the ability to act, and critical hits.
 *
 * <p>The 2024 advantage rule: advantage and disadvantage do not stack, and any single source of each cancels the
 * other to a normal roll. Helpers collect whether any source grants each, then net them.
 */
public final class Conditions {

    /** Conditions that imply other conditions (Paralyzed is Incapacitated, and so on). */
    private static final Map<Condition, List<Condition>> IMPLIES = new EnumMap<>(Map.of(
            Condition.PARALYZED, List.of(Condition.INCAPACITATED),
            Condition.PETRIFIED, List.of(Condition.INCAPACITATED),
            Condition.STUNNED, List.of(Condition.INCAPACITATED),
            Condition.UNCONSCIOUS, List.of(Condition.INCAPACITATED, Condition.PRONE)));

    private Conditions() {}

    /** A creature's conditions, expanded to include everything they imply. */
    public static Set<Condition> effectiveConditions(Combatant c) {
        Set<Condition> out = EnumSet.noneOf(Condition.class);
        out.addAll(c.conditionList());
        // One pass suffices: no implied condition itself implies another not already present.
        for (Condition cond : EnumSet.copyOf(out)) {
            List<Condition> implied = IMPLIES.get(cond);
            if (implied != null) {
                out.addAll(implied);
            }
        }
        return out;
    }

    public static boolean isIncapacitated(Combatant c) {
        return effectiveConditions(c).contains(Condition.INCAPACITATED);
    }

    /** Can the creature take actions and bonus actions? (Not while Incapacitated.) */
    public static boolean canAct(Combatant c) {
        return c.isConscious() && !isIncapacitated(c);
    }

    /** Can the creature take a Reaction? (Not while Incapacitated.) */
    public static boolean canReact(Combatant c) {
        return c.isConscious() && !isIncapacitated(c);
    }

    private static Advantage net(boolean advantage, boolean disadvantage) {
        if (advantage == disadvantage) {
            return Advantage.NORMAL;
        }
        return advantage ? Advantage.ADVANTAGE : Advantage.DISADVANTAGE;
    }

    /**
     * The advantage state of an attack roll from {@code attacker} against {@code defender}, from conditions alone.
     * {@code attackerWithin5} affects Prone (melee versus ranged). Other sources are layered in by the caller.
     */
    public static Advantage attackAdvantage(Combatant attacker, Combatant defender, boolean attackerWithin5) {
        Set<Condition> atk = effectiveConditions(attacker);
        Set<Condition> def = effectiveConditions(defender);
        boolean adv = false;
        boolean dis = false;

        // The attacker's own condition penalizes its attacks.
        if (atk.contains(Condition.BLINDED) || atk.contains(Condition.FRIGHTENED) || atk.contains(Condition.POISONED)
                || atk.contains(Condition.RESTRAINED)) {
            dis = true;
        }
        if (atk.contains(Condition.PRONE)) {
            dis = true; // a prone attacker has disadvantage on attacks
        }
        if (atk.contains(Condition.INVISIBLE)) {
            adv = true;
        }

        // The defender's condition exposes it to attackers.
        if (def.contains(Condition.BLINDED) || def.contains(Condition.PARALYZED) || def.contains(Condition.PETRIFIED)
                || def.contains(Condition.RESTRAINED) || def.contains(Condition.STUNNED)
                || def.contains(Condition.UNCONSCIOUS)) {
            adv = true;
        }
        if (def.contains(Condition.INVISIBLE)) {
            dis = true;
        }
        if (def.contains(Condition.PRONE)) {
            if (attackerWithin5) {
                adv = true;
            } else {
                dis = true;
            }
        }
        return net(adv, dis);
    }

    /**
     * Any attack that hits is a Critical Hit when the defender is Paralyzed or Unconscious and the attacker is within
     * 5 feet. Petrified and Stunned grant advantage but not auto-crits.
     */
    public static boolean isAutoCritTarget(Combatant defender, boolean attackerWithin5) {
        if (!attackerWithin5) {
            return false;
        }
        Set<Condition> def = effectiveConditions(defender);
        return def.contains(Condition.PARALYZED) || def.contains(Condition.UNCONSCIOUS);
    }

    /** Does the creature automatically fail a save of this ability? (Str/Dex while inert.) */
    public static boolean autoFailsSave(Combatant c, Ability ability) {
        if (ability != Ability.STR && ability != Ability.DEX) {
            return false;
        }
        Set<Condition> cond = effectiveConditions(c);
        return cond.contains(Condition.PARALYZED) || cond.contains(Condition.PETRIFIED)
                || cond.contains(Condition.STUNNED) || cond.contains(Condition.UNCONSCIOUS);
    }

    /** Advantage state of a saving throw from conditions (Restrained gives Dex disadvantage). */
    public static Advantage saveAdvantage(Combatant c, Ability ability) {
        boolean dis = ability == Ability.DEX && effectiveConditions(c).contains(Condition.RESTRAINED);
        return net(false, dis);
    }

    /** The flat penalty Exhaustion applies to every D20 Test: -2 per level. */
    public static int exhaustionD20Penalty(Combatant c) {
        return -2 * c.exhaustionLevel();
    }

    /**
     * The creature's effective Speed in feet after conditions: 0 while movement is locked (Grappled, Restrained and
     * the inert conditions), reduced by 5 per Exhaustion level otherwise. Prone does not zero Speed.
     */
    public static int effectiveSpeedFt(Combatant c) {
        Set<Condition> cond = effectiveConditions(c);
        if (cond.contains(Condition.GRAPPLED) || cond.contains(Condition.RESTRAINED)
                || cond.contains(Condition.PARALYZED) || cond.contains(Condition.PETRIFIED)
                || cond.contains(Condition.STUNNED) || cond.contains(Condition.UNCONSCIOUS)) {
            return 0;
        }
        return Math.max(0, c.speedFt() - 5 * c.exhaustionLevel());
    }
}
