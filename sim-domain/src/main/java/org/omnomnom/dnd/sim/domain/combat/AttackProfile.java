package org.omnomnom.dnd.sim.domain.combat;

import java.util.List;
import org.omnomnom.dnd.sim.domain.core.DamageType;
import org.omnomnom.dnd.sim.domain.dice.Dice;

/**
 * A weapon or natural attack a creature can make. Optional fields are {@code null} when absent, matching the
 * TypeScript optional properties; use the {@code *OrDefault} accessors where the engine applies a default.
 *
 * @param reachFt reach in feet for a melee attack (default 5)
 * @param rangeFt normal range in feet for a ranged attack
 * @param rangeLongFt long range in feet (beyond {@code rangeFt}, attacks have disadvantage)
 * @param extraDamage damage riders of other types applied on a hit
 * @param critRange lowest die face that crits (default 20)
 * @param finesse a Finesse weapon (matters for Sneak Attack eligibility)
 */
public record AttackProfile(
        String name,
        AttackKind kind,
        Integer reachFt,
        Integer rangeFt,
        Integer rangeLongFt,
        int attackBonus,
        Dice damage,
        DamageType damageType,
        List<ExtraDamage> extraDamage,
        Integer critRange,
        boolean finesse) {

    public static final int DEFAULT_REACH_FT = 5;
    public static final int DEFAULT_CRIT_RANGE = 20;

    public AttackProfile {
        extraDamage = extraDamage == null ? List.of() : List.copyOf(extraDamage);
    }

    public int reachFtOrDefault() {
        return reachFt != null ? reachFt : DEFAULT_REACH_FT;
    }

    public int critRangeOrDefault() {
        return critRange != null ? critRange : DEFAULT_CRIT_RANGE;
    }

    public static Builder builder(String name, AttackKind kind, int attackBonus, Dice damage, DamageType damageType) {
        return new Builder(name, kind, attackBonus, damage, damageType);
    }

    public static final class Builder {
        private final String name;
        private final AttackKind kind;
        private final int attackBonus;
        private final Dice damage;
        private final DamageType damageType;
        private Integer reachFt;
        private Integer rangeFt;
        private Integer rangeLongFt;
        private List<ExtraDamage> extraDamage = List.of();
        private Integer critRange;
        private boolean finesse;

        private Builder(String name, AttackKind kind, int attackBonus, Dice damage, DamageType damageType) {
            this.name = name;
            this.kind = kind;
            this.attackBonus = attackBonus;
            this.damage = damage;
            this.damageType = damageType;
        }

        public Builder reachFt(int v) {
            this.reachFt = v;
            return this;
        }

        public Builder rangeFt(int v) {
            this.rangeFt = v;
            return this;
        }

        public Builder rangeLongFt(int v) {
            this.rangeLongFt = v;
            return this;
        }

        public Builder extraDamage(List<ExtraDamage> v) {
            this.extraDamage = v;
            return this;
        }

        public Builder critRange(int v) {
            this.critRange = v;
            return this;
        }

        public Builder finesse(boolean v) {
            this.finesse = v;
            return this;
        }

        public AttackProfile build() {
            return new AttackProfile(
                    name, kind, reachFt, rangeFt, rangeLongFt, attackBonus, damage, damageType, extraDamage,
                    critRange, finesse);
        }
    }
}
