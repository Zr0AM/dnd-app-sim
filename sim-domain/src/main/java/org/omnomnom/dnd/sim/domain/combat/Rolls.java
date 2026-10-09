package org.omnomnom.dnd.sim.domain.combat;

import org.omnomnom.dnd.sim.domain.combat.AttackResolver.SaveParams;
import org.omnomnom.dnd.sim.domain.combat.AttackResolver.SaveResult;
import org.omnomnom.dnd.sim.domain.combat.event.CombatEvent;
import org.omnomnom.dnd.sim.domain.core.Ability;

/**
 * The rolls that several parts of a fight make the same way: the dice buffs add to attack rolls and saving throws, and
 * a saving throw itself (ability modifier, buff dice and Aura of Protection). Every draw comes from a stream named by
 * its caller, so results do not depend on the order things are rolled in.
 */
final class Rolls {

    /** Ends the stream label of a saving throw, keeping each save's draws distinct and reproducible. */
    static final String SAVE_SUFFIX = ":save";

    private final FightContext ctx;

    Rolls(FightContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Roll the attacker's buff bonus to an attack roll (Bless's +1d4), logging the assist against the buff's caster.
     * {@code tag} keeps the stream distinct per attack.
     */
    int buffAttackBonus(Combatant self, String tag) {
        int bonus = 0;
        for (BuffBonus b : self.buffAttackBonuses()) {
            int rolled = b.dice().roll(ctx.rng().stream(self.id() + ":buff-atk:" + b.id() + ":" + tag));
            bonus += rolled;
            ctx.log(new CombatEvent.BuffBoost(b.source(), b.id(), self.id(), rolled));
        }
        return bonus;
    }

    /** Roll the defender's buff bonus to a saving throw (Bless's +1d4). */
    private int buffSaveBonus(Combatant self, String tag) {
        int bonus = 0;
        for (BuffBonus b : self.buffSaveBonuses()) {
            bonus += b.dice().roll(ctx.rng().stream(self.id() + ":buff-save:" + b.id() + ":" + tag));
        }
        return bonus;
    }

    /**
     * {@code saver} rolls a saving throw against {@code dc}: d20 from the stream {@code streamLabel}, plus its modifier
     * for {@code ability}, its buff dice (drawn from streams keyed by {@code buffTag}) and any Aura of Protection.
     */
    SaveResult save(String streamLabel, Combatant saver, Ability ability, String buffTag, int dc) {
        var stream = ctx.rng().stream(streamLabel);
        int bonus = saver.saveBonus(ability) + buffSaveBonus(saver, buffTag) + ctx.roster().auraSaveBonus(saver);
        return AttackResolver.resolveSave(stream, SaveParams.of(bonus, dc));
    }
}
