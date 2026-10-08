package org.omnomnom.dnd.sim.domain.combat;

import java.util.List;
import org.omnomnom.dnd.sim.domain.grid.Cell;

/**
 * One logged combat event, for replay and metrics. The 18 variants mirror the TypeScript {@code CombatEvent} union
 * in {@code sim/src/combat/encounter.ts}; the web adapter adds the {@code kind} discriminator when serializing
 * (see {@code docs/api/openapi.yaml}).
 */
public sealed interface CombatEvent {

    record Initiative(List<Entry> order) implements CombatEvent {
        public Initiative {
            order = List.copyOf(order);
        }

        public record Entry(String id, int total) {}
    }

    record Round(int round) implements CombatEvent {}

    record Turn(String id, int round) implements CombatEvent {}

    record Move(String id, Cell from, Cell to, int costFt) implements CombatEvent {}

    record Attack(String attacker, String target, String weapon, int d20, boolean hit, boolean crit, int damage)
            implements CombatEvent {}

    record Opportunity(String attacker, String target, boolean hit, int damage) implements CombatEvent {}

    record SpellCast(String caster, String spell, int slotLevel, int targets, int damage, int healing)
            implements CombatEvent {}

    record Down(String id) implements CombatEvent {}

    record Death(String id) implements CombatEvent {}

    record DeathSave(String id, int d20, boolean success) implements CombatEvent {}

    record ControlDenied(String victim, String source) implements CombatEvent {}

    record ConcentrationBroken(String id) implements CombatEvent {}

    /** Non-spell healing (Paladin Lay on Hands). */
    record Heal(String source, String target, int amount) implements CombatEvent {}

    /** The Ranger placed Hunter's Mark on a target. */
    record Marked(String source, String target) implements CombatEvent {}

    /** A legendary action: a boss attacked between other creatures' turns. */
    record Legendary(String source, String target, int damage) implements CombatEvent {}

    /** A buff was placed on an ally. {@code source} is the caster, {@code target} the recipient. */
    record BuffApplied(String source, String buff, String target) implements CombatEvent {}

    /** A buff materially helped the recipient (boosted roll or extra attack). */
    record BuffBoost(String source, String buff, String beneficiary, int amount) implements CombatEvent {}

    /** The fight ended; {@code winner} is null for a draw or when the round cap was hit. */
    record End(int round, Side winner) implements CombatEvent {}
}
