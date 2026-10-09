package org.omnomnom.dnd.sim.domain.content.monster;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.omnomnom.dnd.sim.domain.combat.AttackKind;
import org.omnomnom.dnd.sim.domain.combat.AttackProfile;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.combat.CombatantSpec;
import org.omnomnom.dnd.sim.domain.combat.ExtraDamage;
import org.omnomnom.dnd.sim.domain.content.ContentIds;
import org.omnomnom.dnd.sim.domain.content.MonsterSource;
import org.omnomnom.dnd.sim.domain.content.MonsterSource.ActionRow;
import org.omnomnom.dnd.sim.domain.content.MonsterSource.DamageRow;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.AbilityScores;
import org.omnomnom.dnd.sim.domain.core.DamageResponse;
import org.omnomnom.dnd.sim.domain.core.DamageType;
import org.omnomnom.dnd.sim.domain.core.Side;
import org.omnomnom.dnd.sim.domain.dice.Dice;
import org.omnomnom.dnd.sim.domain.grid.Cell;

/**
 * The monster content compiler: pure translation of seed rows into an engine-ready {@link MonsterTemplate}, which
 * {@link #spawn} turns into a {@link Combatant} on a side and a cell. It compiles the structured part of a stat block
 * (AC, HP, abilities, saves, damage defenses, speed, attack actions with multi-type damage); traits, recharge effects,
 * legendary actions and Multiattack counts are hand-authored overrides, not parsed from prose.
 */
public final class MonsterCompiler {

    private MonsterCompiler() {}

    public static MonsterTemplate compile(MonsterSource src) {
        return compile(src, MonsterOverrides.NONE);
    }

    public static MonsterTemplate compile(MonsterSource src, MonsterOverrides overrides) {
        MonsterSource.MonsterRow m = src.monster();
        AbilityScores abilities = AbilityScores.of(
                m.monsterStr(), m.monsterDex(), m.monsterCon(), m.monsterInt(), m.monsterWis(), m.monsterCha());

        Map<Ability, Integer> saveBonuses = new EnumMap<>(Ability.class);
        for (MonsterSource.SaveRow s : src.saves()) {
            saveBonuses.put(ContentIds.abilityById(s.abilityID()), s.saveBonus());
        }

        Map<DamageType, DamageResponse> responses = new EnumMap<>(DamageType.class);
        for (MonsterSource.DefenseRow d : src.defenses()) {
            DamageResponse response = switch (d.defenseKind()) {
                case "resistance" -> DamageResponse.RESISTANT;
                case "vulnerability" -> DamageResponse.VULNERABLE;
                case "immunity" -> DamageResponse.IMMUNE;
                default -> null;
            };
            if (response != null && d.damageTypeID() != null) {
                responses.put(ContentIds.damageTypeById(d.damageTypeID()), response);
            }
            // Condition immunities (d.conditionID) are recorded by the condition system later.
        }

        List<AttackProfile> attacks = new ArrayList<>();
        for (ActionRow a : src.actions()) {
            if (!a.actionSection().equals("action")) {
                continue;
            }
            AttackProfile profile = compileAttack(a, src.damage());
            if (profile != null) {
                attacks.add(profile);
            }
        }

        int walk = 30;
        for (MonsterSource.SpeedRow s : src.speeds()) {
            if (s.speedMode().equals("walk")) {
                walk = s.speedFt();
                break;
            }
        }

        return new MonsterTemplate(
                m.monsterSlug(), m.monsterName(), m.crValue(), m.monsterAc(), m.monsterHpAvg(), abilities, walk,
                saveBonuses, responses, attacks, overrides.multiattack(), overrides.legendaryActions());
    }

    /** A damage row to a Dice term: the dice if present, else a flat average carried in the bonus. */
    private static Dice damageDice(DamageRow row) {
        if (row.damageDiceCount() != null && row.damageDiceCount() != 0
                && row.damageDiceSides() != null && row.damageDiceSides() != 0) {
            return Dice.of(row.damageDiceCount(), row.damageDiceSides(), row.damageBonus() != null ? row.damageBonus() : 0);
        }
        return Dice.of(0, 1, row.damageAvg()); // flat damage via bonus
    }

    private static AttackProfile compileAttack(ActionRow action, List<DamageRow> damageRows) {
        if (action.attackKind() == null || action.attackBonus() == null) {
            return null;
        }
        List<DamageRow> rows = damageRows.stream()
                .filter(d -> d.monsterActionID() == action.monsterActionID())
                .sorted(Comparator.comparingInt(DamageRow::damageIndex))
                .toList();
        if (rows.isEmpty()) {
            return null;
        }

        // melee_or_ranged is modeled as melee (its in-melee use); the range is still recorded.
        AttackKind kind = action.attackKind().equals("ranged") ? AttackKind.RANGED : AttackKind.MELEE;
        List<ExtraDamage> extra = rows.subList(1, rows.size()).stream()
                .map(r -> new ExtraDamage(damageDice(r), ContentIds.damageTypeById(r.damageTypeID())))
                .toList();
        Integer reach = reachFt(action, kind);
        return new AttackProfile(
                action.actionName(), kind, reach, action.attackRangeFt(), action.attackRangeLongFt(),
                action.attackBonus(), damageDice(rows.get(0)), ContentIds.damageTypeById(rows.get(0).damageTypeID()),
                extra, null, false);
    }

    /** The stated reach, else the standard 5 ft for a melee attack; ranged attacks have none. */
    private static Integer reachFt(ActionRow action, AttackKind kind) {
        if (action.attackReachFt() != null) {
            return action.attackReachFt();
        }
        return kind == AttackKind.MELEE ? 5 : null;
    }

    /** Where a spawned monster goes. */
    public record Placement(String id, Side side, Cell position) {}

    /**
     * Place a compiled monster on the board as a fresh {@link Combatant}. Multiattack is modeled as extra attacks: a
     * monster making N attacks a turn gets N-1 extra attacks, which the shared AI resolves with its best attack (a
     * documented fidelity-tier simplification for mixed Multiattack).
     */
    public static Combatant spawn(MonsterTemplate template, Placement placement) {
        int totalAttacks = template.multiattack().stream().mapToInt(MonsterTemplate.MultiattackEntry::count).sum();
        int extraAttacks = Math.max(0, totalAttacks - 1);
        return new Combatant(CombatantSpec.builder(
                        placement.id(), template.name(), placement.side(),
                        1, // monsters have no character level; proficiency comes from save overrides
                        template.abilities(), template.ac(), template.maxHp())
                .speedFt(template.speedFt())
                .saveBonuses(template.saveBonuses())
                .damageResponses(template.damageResponses())
                .attacks(template.attacks())
                .extraAttacks(extraAttacks)
                .legendaryActions(template.legendaryActions())
                .position(placement.position())
                .build());
    }
}
