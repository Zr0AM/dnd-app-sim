package org.omnomnom.dnd.sim.domain.combat;

import org.omnomnom.dnd.sim.domain.combat.AttackResolver.SaveResult;
import org.omnomnom.dnd.sim.domain.combat.event.CombatEvent;
import org.omnomnom.dnd.sim.domain.core.Ability;

/**
 * What happens to a creature once damage lands, whatever dealt it: the Down and Death events, the killer's on-kill
 * features, and the target's concentration check.
 */
final class DamageApplier {

    private final FightContext ctx;
    private final Rolls rolls;

    DamageApplier(FightContext ctx, Rolls rolls) {
        this.ctx = ctx;
        this.rolls = rolls;
    }

    /** Apply spell damage to a target and log any down/death. */
    int applySpellDamage(Combatant source, Combatant target, int dealt) {
        boolean wasConscious = target.isConscious();
        DamageOutcome outcome = target.takeDamage(dealt);
        settle(source, target, wasConscious, outcome, dealt);
        return dealt;
    }

    /**
     * Follow up damage the caller has already applied: log Down and Death, notify the source's features of a kill, and
     * make the target's concentration check. {@code wasConscious} is the target's state before the damage.
     */
    void settle(Combatant source, Combatant target, boolean wasConscious, DamageOutcome outcome, int dealt) {
        if (wasConscious && outcome.dropped()) {
            ctx.log(new CombatEvent.Down(target.id()));
            fireOnKill(source, target);
        }
        if (outcome.died()) {
            ctx.log(new CombatEvent.Death(target.id()));
        }
        checkConcentration(target, dealt);
    }

    /** Notify the killer's features that it dropped {@code victim} (Warlock Dark One's Blessing). */
    private void fireOnKill(Combatant killer, Combatant victim) {
        for (Feature f : killer.features()) {
            f.onKill(killer, victim);
        }
    }

    /**
     * A creature that takes damage while concentrating makes a Constitution save (DC 10 or half the damage, whichever
     * is higher). On a failure its concentration ends and every effect it was sustaining is removed.
     */
    private void checkConcentration(Combatant target, int dealt) {
        if (dealt <= 0 || target.concentratingOn() == null || !target.isConscious()) {
            return;
        }
        int dc = Math.max(10, dealt / 2);
        // The stream label uses the counter before it is incremented; the buff tag sees the incremented value,
        // matching the TypeScript argument evaluation order.
        int seq = ctx.nextConcentrationSeq();
        SaveResult save = rolls.save(target.id() + ":conc:" + seq, target, Ability.CON, "conc:" + (seq + 1), dc);
        if (!save.success()) {
            target.setConcentratingOn(null);
            target.setMarkedTarget(null); // Hunter's Mark drops with concentration
            ctx.roster().forEach(c -> {
                c.endConcentrationConditions(target.id());
                c.endConcentrationBuffs(target.id());
            });
            ctx.log(new CombatEvent.ConcentrationBroken(target.id()));
        }
    }
}
