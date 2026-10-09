package org.omnomnom.dnd.sim.domain.combat;

import java.util.List;
import java.util.Optional;
import org.omnomnom.dnd.sim.domain.core.DamageType;

/**
 * The hooks a class feature, feat, or item effect uses to influence combat. A feature belongs to exactly one
 * combatant and may hold per-turn state; the encounter calls its hooks at the right moments. Every hook has a
 * no-op default, so implementations override only what they need.
 *
 * <p>Ownership is enforced by construction: a {@link CombatantSpec} carries {@link FeatureFactory}s, and each
 * {@link Combatant} creates its own instances. The upstream TypeScript catalog shared feature instances across
 * heroes, which is a data race under parallel evaluation.
 */
public interface Feature {

    String id();

    /** Start of the owner's turn: reset per-turn state, auto-activate, etc. */
    default void onTurnStart(Combatant self) {}

    /** Modify the owner's attack against {@code target} with {@code weapon}. */
    default OutgoingAttackMods outgoingAttack(Combatant self, Combatant target, AttackProfile weapon) {
        return OutgoingAttackMods.NONE;
    }

    /** Extra damage components applied when the owner hits. May consume once-per-turn state. */
    default List<ExtraDamage> onHit(OnHitContext ctx) {
        return List.of();
    }

    /**
     * A save-or-condition effect imposed when the owner hits (Stunning Strike). The feature decides whether it
     * triggers (spending resources or once-per-turn state); the encounter rolls the save and applies it.
     */
    default Optional<HitEffect> onHitEffect(OnHitContext ctx) {
        return Optional.empty();
    }

    /** Extra single-attack actions the feature grants for this turn (Monk Martial Arts). */
    default int bonusAttackActions(Combatant self) {
        return 0;
    }

    /** The owner reduced {@code victim} to 0 HP this attack or spell (Warlock Dark One's Blessing). */
    default void onKill(Combatant self, Combatant victim) {}

    /** Whether attacks against the owner currently have advantage (for example Reckless Attack). */
    default boolean grantsAttackersAdvantage(Combatant self) {
        return false;
    }

    /** Whether the owner currently resists this damage type (for example Rage). */
    default boolean resistsDamage(Combatant self, DamageType type) {
        return false;
    }
}
