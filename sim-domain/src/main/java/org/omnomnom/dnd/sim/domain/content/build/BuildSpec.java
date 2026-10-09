package org.omnomnom.dnd.sim.domain.content.build;

import org.omnomnom.dnd.sim.domain.combat.spell.SpellcastingSpec;
import org.omnomnom.dnd.sim.domain.content.BuildProgression;
import org.omnomnom.dnd.sim.domain.content.ClassInfo;
import org.omnomnom.dnd.sim.domain.content.equipment.ArmorInfo;
import org.omnomnom.dnd.sim.domain.content.equipment.WeaponInfo;
import org.omnomnom.dnd.sim.domain.core.AbilityScores;
import org.omnomnom.dnd.sim.domain.core.Side;
import org.omnomnom.dnd.sim.domain.grid.Cell;

/**
 * A resolved single-class martial build, ready for {@link CharacterCompiler}. Create one with {@link #builder}.
 *
 * @param id combatant id; defaults to the class slug when null
 * @param side defaults to the party
 * @param subclass subclass slug (for example {@code champion}); may be null
 * @param twoHanded wield a versatile weapon in two hands (uses the versatile dice)
 * @param armor null when unarmored
 * @param fightingStyle may be null
 * @param unarmoredDefense may be null
 * @param weaponProficient proficient with the chosen weapon (default true for these martial classes)
 * @param position defaults to the origin
 * @param progression level-dependent feature values resolved from the class tables
 * @param spellcasting optional slots and spell list for a gish (Paladin, Ranger); may be null
 */
public record BuildSpec(
        String id,
        String name,
        Side side,
        ClassInfo classInfo,
        String subclass,
        int level,
        AbilityScores abilities,
        WeaponInfo weapon,
        boolean twoHanded,
        ArmorInfo armor,
        boolean shield,
        FightingStyle fightingStyle,
        UnarmoredDefense unarmoredDefense,
        boolean weaponProficient,
        Cell position,
        BuildProgression progression,
        SpellcastingSpec spellcasting) {

    public static Builder builder(String name, ClassInfo classInfo, int level, AbilityScores abilities, WeaponInfo weapon) {
        return new Builder(name, classInfo, level, abilities, weapon);
    }

    public static final class Builder {
        private final String name;
        private final ClassInfo classInfo;
        private final int level;
        private final AbilityScores abilities;
        private final WeaponInfo weapon;
        private String id;
        private Side side = Side.PARTY;
        private String subclass;
        private boolean twoHanded;
        private ArmorInfo armor;
        private boolean shield;
        private FightingStyle fightingStyle;
        private UnarmoredDefense unarmoredDefense;
        private boolean weaponProficient = true;
        private Cell position = new Cell(0, 0);
        private BuildProgression progression = BuildProgression.NONE;
        private SpellcastingSpec spellcasting;

        private Builder(String name, ClassInfo classInfo, int level, AbilityScores abilities, WeaponInfo weapon) {
            this.name = name;
            this.classInfo = classInfo;
            this.level = level;
            this.abilities = abilities;
            this.weapon = weapon;
        }

        public Builder id(String v) { this.id = v; return this; }
        public Builder side(Side v) { this.side = v; return this; }
        public Builder subclass(String v) { this.subclass = v; return this; }
        public Builder twoHanded(boolean v) { this.twoHanded = v; return this; }
        public Builder armor(ArmorInfo v) { this.armor = v; return this; }
        public Builder shield(boolean v) { this.shield = v; return this; }
        public Builder fightingStyle(FightingStyle v) { this.fightingStyle = v; return this; }
        public Builder unarmoredDefense(UnarmoredDefense v) { this.unarmoredDefense = v; return this; }
        public Builder weaponProficient(boolean v) { this.weaponProficient = v; return this; }
        public Builder position(Cell v) { this.position = v; return this; }
        public Builder progression(BuildProgression v) { this.progression = v; return this; }
        public Builder spellcasting(SpellcastingSpec v) { this.spellcasting = v; return this; }

        public BuildSpec build() {
            return new BuildSpec(id, name, side, classInfo, subclass, level, abilities, weapon, twoHanded, armor, shield,
                    fightingStyle, unarmoredDefense, weaponProficient, position, progression, spellcasting);
        }
    }
}
