package org.omnomnom.dnd.sim.domain.content.build;

import java.util.List;
import org.omnomnom.dnd.sim.domain.combat.FeatureFactory;
import org.omnomnom.dnd.sim.domain.combat.ResourceSpec;
import org.omnomnom.dnd.sim.domain.combat.spell.Spell;
import org.omnomnom.dnd.sim.domain.combat.spell.SpellcastingSpec;
import org.omnomnom.dnd.sim.domain.content.ClassInfo;
import org.omnomnom.dnd.sim.domain.content.equipment.ArmorInfo;
import org.omnomnom.dnd.sim.domain.content.equipment.WeaponInfo;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.AbilityScores;
import org.omnomnom.dnd.sim.domain.core.Side;
import org.omnomnom.dnd.sim.domain.grid.Cell;

/**
 * A resolved caster build, ready for {@link CasterCompiler}. Create one with {@link #builder}.
 *
 * @param armor null when unarmored
 * @param resources resource pools (for example a Sorcerer's Sorcery Points)
 * @param features class features as factories (for example a Warlock's Dark One's Blessing)
 * @param shortRestSlots Pact Magic: slots recharge on a Short Rest (Warlock)
 * @param extraHp extra HP added to the computed maximum (Draconic Resilience: +1 per level)
 * @param unarmoredAcAbility when unarmored, AC is 10 + Dex + this ability's modifier (Draconic Resilience); may be null
 */
public record CasterBuildSpec(
        String id,
        String name,
        Side side,
        ClassInfo classInfo,
        String subclass,
        int level,
        AbilityScores abilities,
        WeaponInfo weapon,
        ArmorInfo armor,
        boolean shield,
        Ability spellAbility,
        List<Spell> cantrips,
        List<Spell> spells,
        List<SpellcastingSpec.Slot> slots,
        Cell position,
        List<ResourceSpec> resources,
        List<FeatureFactory> features,
        boolean shortRestSlots,
        int extraHp,
        Ability unarmoredAcAbility) {

    public static Builder builder(
            String name, ClassInfo classInfo, int level, AbilityScores abilities, WeaponInfo weapon, Ability spellAbility) {
        return new Builder(name, classInfo, level, abilities, weapon, spellAbility);
    }

    public static final class Builder {
        private final String name;
        private final ClassInfo classInfo;
        private final int level;
        private final AbilityScores abilities;
        private final WeaponInfo weapon;
        private final Ability spellAbility;
        private String id;
        private Side side = Side.PARTY;
        private String subclass;
        private ArmorInfo armor;
        private boolean shield;
        private List<Spell> cantrips = List.of();
        private List<Spell> spells = List.of();
        private List<SpellcastingSpec.Slot> slots = List.of();
        private Cell position = new Cell(0, 0);
        private List<ResourceSpec> resources = List.of();
        private List<FeatureFactory> features = List.of();
        private boolean shortRestSlots;
        private int extraHp;
        private Ability unarmoredAcAbility;

        private Builder(String name, ClassInfo classInfo, int level, AbilityScores abilities, WeaponInfo weapon, Ability spellAbility) {
            this.name = name;
            this.classInfo = classInfo;
            this.level = level;
            this.abilities = abilities;
            this.weapon = weapon;
            this.spellAbility = spellAbility;
        }

        public Builder id(String v) { this.id = v; return this; }
        public Builder side(Side v) { this.side = v; return this; }
        public Builder subclass(String v) { this.subclass = v; return this; }
        public Builder armor(ArmorInfo v) { this.armor = v; return this; }
        public Builder shield(boolean v) { this.shield = v; return this; }
        public Builder cantrips(List<Spell> v) { this.cantrips = v; return this; }
        public Builder spells(List<Spell> v) { this.spells = v; return this; }
        public Builder slots(List<SpellcastingSpec.Slot> v) { this.slots = v; return this; }
        public Builder position(Cell v) { this.position = v; return this; }
        public Builder resources(List<ResourceSpec> v) { this.resources = v; return this; }
        public Builder features(List<FeatureFactory> v) { this.features = v; return this; }
        public Builder shortRestSlots(boolean v) { this.shortRestSlots = v; return this; }
        public Builder extraHp(int v) { this.extraHp = v; return this; }
        public Builder unarmoredAcAbility(Ability v) { this.unarmoredAcAbility = v; return this; }

        public CasterBuildSpec build() {
            return new CasterBuildSpec(id, name, side, classInfo, subclass, level, abilities, weapon, armor, shield,
                    spellAbility, cantrips, spells, slots, position, resources, features, shortRestSlots, extraHp,
                    unarmoredAcAbility);
        }
    }
}
