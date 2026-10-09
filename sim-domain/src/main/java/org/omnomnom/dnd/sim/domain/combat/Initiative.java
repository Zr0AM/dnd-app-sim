package org.omnomnom.dnd.sim.domain.combat;

import java.util.Comparator;
import java.util.List;
import org.omnomnom.dnd.sim.domain.combat.event.CombatEvent;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.Condition;
import org.omnomnom.dnd.sim.domain.core.Side;
import org.omnomnom.dnd.sim.domain.dice.Advantage;
import org.omnomnom.dnd.sim.domain.dice.Dice;
import org.omnomnom.dnd.sim.domain.rng.LabeledRandom;

/**
 * Rolling initiative: d20 + Dex modifier, advantage if Invisible, disadvantage if Incapacitated when rolling (the
 * surprise rule). Ordered high to low; ties break by Dex modifier, then party before enemy, then id.
 */
final class Initiative {

    /** The turn order and the event that records it. */
    record Result(List<Combatant> order, CombatEvent.Initiative event) {}

    private record Scored(Combatant combatant, int total) {}

    private static final Comparator<Scored> TURN_ORDER = Comparator.<Scored>comparingInt(s -> -s.total())
            .thenComparingInt(s -> -s.combatant().abilityMod(Ability.DEX))
            .thenComparingInt(s -> s.combatant().side() == Side.PARTY ? 0 : 1)
            .thenComparing(s -> s.combatant().id());

    private Initiative() {}

    static Result roll(List<Combatant> combatants, LabeledRandom rng) {
        List<Scored> scored = combatants.stream()
                .map(c -> new Scored(c, Dice.rollD20(rng.stream("initiative:" + c.id()), advantageFor(c)) + c.abilityMod(Ability.DEX)))
                .sorted(TURN_ORDER)
                .toList();
        List<CombatEvent.Initiative.Entry> entries =
                scored.stream().map(s -> new CombatEvent.Initiative.Entry(s.combatant().id(), s.total())).toList();
        return new Result(scored.stream().map(Scored::combatant).toList(), new CombatEvent.Initiative(entries));
    }

    /** Incapacitated outranks Invisible: a creature that is both rolls with disadvantage. */
    private static Advantage advantageFor(Combatant c) {
        if (c.hasCondition(Condition.INCAPACITATED)) {
            return Advantage.DISADVANTAGE;
        }
        return c.hasCondition(Condition.INVISIBLE) ? Advantage.ADVANTAGE : Advantage.NORMAL;
    }
}
