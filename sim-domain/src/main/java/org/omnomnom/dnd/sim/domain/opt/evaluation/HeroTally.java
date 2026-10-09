package org.omnomnom.dnd.sim.domain.opt.evaluation;

import org.omnomnom.dnd.sim.domain.combat.event.CombatEvent;
import org.omnomnom.dnd.sim.domain.combat.event.EventSink;

/**
 * Accumulates one combatant's contribution to a fight as events arrive, so the optimizer's hot path never allocates
 * an event log. Not thread-safe; use one per run.
 */
final class HeroTally implements EventSink {

    private final String id;
    private double damage;
    private double healing;
    private int denied;
    private int buffAssists;

    HeroTally(String id) {
        this.id = id;
    }

    @Override
    public void accept(CombatEvent event) {
        switch (event) {
            case CombatEvent.Attack a when a.attacker().equals(id) -> damage += a.damage();
            case CombatEvent.Opportunity o when o.attacker().equals(id) -> damage += o.damage();
            case CombatEvent.SpellCast s when s.caster().equals(id) -> {
                damage += s.damage();
                healing += s.healing();
            }
            case CombatEvent.Heal h when h.source().equals(id) -> healing += h.amount(); // Lay on Hands
            case CombatEvent.ControlDenied c when c.source().equals(id) -> denied++;
            case CombatEvent.BuffBoost b when b.source().equals(id) -> buffAssists++;
            default -> {
                // every other event carries nothing to tally
            }
        }
    }

    /** Damage dealt by attacks, opportunity attacks and spells. */
    double damage() {
        return damage;
    }

    /** Healing from spells and Lay on Hands. */
    double healing() {
        return healing;
    }

    /** Enemy actions denied through control conditions. */
    int actionsDenied() {
        return denied;
    }

    /** Boosted ally attacks and Haste attacks the hero delivered. */
    int buffAssists() {
        return buffAssists;
    }
}
