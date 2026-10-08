package org.omnomnom.dnd.sim.domain.combat;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.AbilityScores;
import org.omnomnom.dnd.sim.domain.core.DamageResponse;
import org.omnomnom.dnd.sim.domain.core.DamageType;
import org.omnomnom.dnd.sim.domain.core.Size;
import org.omnomnom.dnd.sim.domain.grid.Cell;

/**
 * Everything needed to create a {@link Combatant}. Immutable and shareable across runs: features appear as
 * {@link FeatureFactory}s so each combatant builds its own. Create one with {@link #builder}.
 *
 * @param saveBonuses explicit save bonuses (monster stat-block saves), overriding the computed value
 * @param extraAttacks extra weapon attacks granted by Extra Attack
 * @param spellcasting null for non-casters
 * @param legendaryActions legendary actions per round (a boss acting between other creatures' turns)
 */
public record CombatantSpec(
        String id,
        String name,
        Side side,
        int level,
        Size size,
        AbilityScores abilities,
        int ac,
        int maxHp,
        int speedFt,
        Set<Ability> saveProficiencies,
        Map<Ability, Integer> saveBonuses,
        Map<DamageType, DamageResponse> damageResponses,
        Cell position,
        List<AttackProfile> attacks,
        List<FeatureFactory> features,
        int extraAttacks,
        List<ResourceSpec> resources,
        SpellcastingSpec spellcasting,
        int legendaryActions) {

    public CombatantSpec {
        saveProficiencies = saveProficiencies.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(saveProficiencies));
        saveBonuses = saveBonuses.isEmpty() ? Map.of() : Map.copyOf(new EnumMap<>(saveBonuses));
        damageResponses = damageResponses.isEmpty() ? Map.of() : Map.copyOf(new EnumMap<>(damageResponses));
        attacks = List.copyOf(attacks);
        features = List.copyOf(features);
        resources = List.copyOf(resources);
    }

    public static Builder builder(String id, String name, Side side, int level, AbilityScores abilities, int ac, int maxHp) {
        return new Builder(id, name, side, level, abilities, ac, maxHp);
    }

    public Builder toBuilder() {
        Builder b = new Builder(id, name, side, level, abilities, ac, maxHp);
        b.size = size;
        b.speedFt = speedFt;
        b.saveProficiencies = saveProficiencies;
        b.saveBonuses = saveBonuses;
        b.damageResponses = damageResponses;
        b.position = position;
        b.attacks = attacks;
        b.features = features;
        b.extraAttacks = extraAttacks;
        b.resources = resources;
        b.spellcasting = spellcasting;
        b.legendaryActions = legendaryActions;
        return b;
    }

    public static final class Builder {
        private String id;
        private String name;
        private Side side;
        private int level;
        private AbilityScores abilities;
        private int ac;
        private int maxHp;
        private Size size = Size.MEDIUM;
        private int speedFt = 30;
        private Set<Ability> saveProficiencies = Set.of();
        private Map<Ability, Integer> saveBonuses = Map.of();
        private Map<DamageType, DamageResponse> damageResponses = Map.of();
        private Cell position = new Cell(0, 0);
        private List<AttackProfile> attacks = List.of();
        private List<FeatureFactory> features = List.of();
        private int extraAttacks;
        private List<ResourceSpec> resources = List.of();
        private SpellcastingSpec spellcasting;
        private int legendaryActions;

        private Builder(String id, String name, Side side, int level, AbilityScores abilities, int ac, int maxHp) {
            this.id = id;
            this.name = name;
            this.side = side;
            this.level = level;
            this.abilities = abilities;
            this.ac = ac;
            this.maxHp = maxHp;
        }

        public Builder name(String v) {
            this.name = v;
            return this;
        }

        public Builder side(Side v) {
            this.side = v;
            return this;
        }

        public Builder level(int v) {
            this.level = v;
            return this;
        }

        public Builder abilities(AbilityScores v) {
            this.abilities = v;
            return this;
        }

        public Builder ac(int v) {
            this.ac = v;
            return this;
        }

        public Builder maxHp(int v) {
            this.maxHp = v;
            return this;
        }

        public Builder size(Size v) {
            this.size = v;
            return this;
        }

        public Builder speedFt(int v) {
            this.speedFt = v;
            return this;
        }

        public Builder saveProficiencies(Set<Ability> v) {
            this.saveProficiencies = v;
            return this;
        }

        public Builder saveBonuses(Map<Ability, Integer> v) {
            this.saveBonuses = v;
            return this;
        }

        public Builder damageResponses(Map<DamageType, DamageResponse> v) {
            this.damageResponses = v;
            return this;
        }

        public Builder position(Cell v) {
            this.position = v;
            return this;
        }

        public Builder attacks(List<AttackProfile> v) {
            this.attacks = v;
            return this;
        }

        public Builder features(List<FeatureFactory> v) {
            this.features = v;
            return this;
        }

        public Builder extraAttacks(int v) {
            this.extraAttacks = v;
            return this;
        }

        public Builder resources(List<ResourceSpec> v) {
            this.resources = v;
            return this;
        }

        public Builder spellcasting(SpellcastingSpec v) {
            this.spellcasting = v;
            return this;
        }

        public Builder legendaryActions(int v) {
            this.legendaryActions = v;
            return this;
        }

        public CombatantSpec build() {
            return new CombatantSpec(
                    id, name, side, level, size, abilities, ac, maxHp, speedFt, saveProficiencies, saveBonuses,
                    damageResponses, position, attacks, features, extraAttacks, resources, spellcasting,
                    legendaryActions);
        }
    }
}
