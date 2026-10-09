package org.omnomnom.dnd.sim.domain.content.feature;

import org.omnomnom.dnd.sim.domain.combat.AttackKind;
import org.omnomnom.dnd.sim.domain.combat.AttackProfile;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.combat.Feature;
import org.omnomnom.dnd.sim.domain.combat.OutgoingAttackMods;

/**
 * Reckless Attack: advantage on the owner's melee attacks, and advantage to attackers until the owner's next turn.
 * Taken whenever the owner makes a melee attack (the AI does not decide), as upstream.
 */
public final class RecklessAttackFeature implements Feature {

    private boolean activeUntilNextTurn;

    @Override
    public String id() {
        return "reckless-attack";
    }

    @Override
    public void onTurnStart(Combatant self) {
        // The window ("until the start of your next turn") closes as the turn begins.
        activeUntilNextTurn = false;
    }

    @Override
    public OutgoingAttackMods outgoingAttack(Combatant self, Combatant target, AttackProfile weapon) {
        if (weapon.kind() != AttackKind.MELEE) {
            return OutgoingAttackMods.NONE;
        }
        activeUntilNextTurn = true; // attacking recklessly
        return new OutgoingAttackMods(true, false, 0);
    }

    @Override
    public boolean grantsAttackersAdvantage(Combatant self) {
        return activeUntilNextTurn;
    }
}
