package org.omnomnom.dnd.sim.application.encounter;

import java.util.HashMap;
import java.util.Map;
import org.omnomnom.dnd.sim.domain.combat.event.CombatEvent;
import org.omnomnom.dnd.sim.domain.combat.event.EventSink;

/**
 * Attributes each combatant's contribution to one fight as events arrive, so a many-run simulation allocates no event
 * log. Not thread-safe; use one per run.
 */
final class CombatTally implements EventSink {

    /** One combatant's running totals. */
    static final class Counts {
        double damage;
        double healing;
        int buffAssists;
        int denied;
    }

    private final Map<String, Counts> byId = new HashMap<>();

    private Counts of(String id) {
        return byId.computeIfAbsent(id, k -> new Counts());
    }

    Counts counts(String id) {
        return byId.getOrDefault(id, new Counts());
    }

    @Override
    public void accept(CombatEvent event) {
        switch (event) {
            case CombatEvent.Attack a -> of(a.attacker()).damage += a.damage();
            case CombatEvent.Opportunity o -> of(o.attacker()).damage += o.damage();
            case CombatEvent.Legendary l -> of(l.source()).damage += l.damage();
            case CombatEvent.SpellCast s -> {
                Counts c = of(s.caster());
                c.damage += s.damage();
                c.healing += s.healing();
            }
            case CombatEvent.Heal h -> of(h.source()).healing += h.amount(); // Lay on Hands
            case CombatEvent.ControlDenied d -> of(d.source()).denied++;
            case CombatEvent.BuffBoost b -> of(b.source()).buffAssists++;
            default -> {
                // every other event carries nothing to tally
            }
        }
    }
}
