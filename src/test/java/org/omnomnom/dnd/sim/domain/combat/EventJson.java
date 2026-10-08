package org.omnomnom.dnd.sim.domain.combat;

import org.omnomnom.dnd.sim.domain.grid.Cell;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The JSON shape of each {@link CombatEvent}, matching the TypeScript log and {@code docs/api/openapi.yaml}. Test-only
 * for now; the web adapter will produce the same shape.
 */
final class EventJson {

    private EventJson() {}

    private static ObjectNode cell(ObjectMapper m, Cell c) {
        ObjectNode n = m.createObjectNode();
        n.put("x", c.x());
        n.put("y", c.y());
        return n;
    }

    static ObjectNode toJson(ObjectMapper m, CombatEvent ev) {
        ObjectNode n = m.createObjectNode();
        switch (ev) {
            case CombatEvent.Initiative e -> {
                n.put("kind", "initiative");
                var arr = n.putArray("order");
                for (var o : e.order()) {
                    ObjectNode on = arr.addObject();
                    on.put("id", o.id());
                    on.put("total", o.total());
                }
            }
            case CombatEvent.Round e -> {
                n.put("kind", "round");
                n.put("round", e.round());
            }
            case CombatEvent.Turn e -> {
                n.put("kind", "turn");
                n.put("id", e.id());
                n.put("round", e.round());
            }
            case CombatEvent.Move e -> {
                n.put("kind", "move");
                n.put("id", e.id());
                n.set("from", cell(m, e.from()));
                n.set("to", cell(m, e.to()));
                n.put("costFt", e.costFt());
            }
            case CombatEvent.Attack e -> {
                n.put("kind", "attack");
                n.put("attacker", e.attacker());
                n.put("target", e.target());
                n.put("weapon", e.weapon());
                n.put("d20", e.d20());
                n.put("hit", e.hit());
                n.put("crit", e.crit());
                n.put("damage", e.damage());
            }
            case CombatEvent.Opportunity e -> {
                n.put("kind", "opportunity");
                n.put("attacker", e.attacker());
                n.put("target", e.target());
                n.put("hit", e.hit());
                n.put("damage", e.damage());
            }
            case CombatEvent.SpellCast e -> {
                n.put("kind", "spell");
                n.put("caster", e.caster());
                n.put("spell", e.spell());
                n.put("slotLevel", e.slotLevel());
                n.put("targets", e.targets());
                n.put("damage", e.damage());
                n.put("healing", e.healing());
            }
            case CombatEvent.Down e -> {
                n.put("kind", "down");
                n.put("id", e.id());
            }
            case CombatEvent.Death e -> {
                n.put("kind", "death");
                n.put("id", e.id());
            }
            case CombatEvent.DeathSave e -> {
                n.put("kind", "deathSave");
                n.put("id", e.id());
                n.put("d20", e.d20());
                n.put("success", e.success());
            }
            case CombatEvent.ControlDenied e -> {
                n.put("kind", "controlDenied");
                n.put("victim", e.victim());
                n.put("source", e.source());
            }
            case CombatEvent.ConcentrationBroken e -> {
                n.put("kind", "concentrationBroken");
                n.put("id", e.id());
            }
            case CombatEvent.Heal e -> {
                n.put("kind", "heal");
                n.put("source", e.source());
                n.put("target", e.target());
                n.put("amount", e.amount());
            }
            case CombatEvent.Marked e -> {
                n.put("kind", "marked");
                n.put("source", e.source());
                n.put("target", e.target());
            }
            case CombatEvent.Legendary e -> {
                n.put("kind", "legendary");
                n.put("source", e.source());
                n.put("target", e.target());
                n.put("damage", e.damage());
            }
            case CombatEvent.BuffApplied e -> {
                n.put("kind", "buffApplied");
                n.put("source", e.source());
                n.put("buff", e.buff());
                n.put("target", e.target());
            }
            case CombatEvent.BuffBoost e -> {
                n.put("kind", "buffBoost");
                n.put("source", e.source());
                n.put("buff", e.buff());
                n.put("beneficiary", e.beneficiary());
                n.put("amount", e.amount());
            }
            case CombatEvent.End e -> {
                n.put("kind", "end");
                n.put("round", e.round());
                if (e.winner() == null) {
                    n.putNull("winner");
                } else {
                    n.put("winner", e.winner().code());
                }
            }
        }
        return n;
    }
}
