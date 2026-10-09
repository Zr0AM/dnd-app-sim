package org.omnomnom.dnd.sim.domain.content.build;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.combat.spell.SpellcastingSpec;
import org.omnomnom.dnd.sim.domain.content.BuildProgression;
import org.omnomnom.dnd.sim.domain.content.ClassInfo;
import org.omnomnom.dnd.sim.domain.content.ContentSource;
import org.omnomnom.dnd.sim.domain.content.equipment.ArmorInfo;
import org.omnomnom.dnd.sim.domain.content.equipment.Gear;
import org.omnomnom.dnd.sim.domain.content.equipment.WeaponInfo;
import org.omnomnom.dnd.sim.domain.core.Ability;
import org.omnomnom.dnd.sim.domain.core.AbilityScores;
import org.omnomnom.dnd.sim.domain.core.Side;
import org.omnomnom.dnd.sim.domain.grid.Cell;

/**
 * Reference-party filler builds: the fixed benchmark characters that fill the roles the hero is measured against. They
 * are frozen recipes (Human, standard array, the SRD subclass), reproducible and reviewable, so the hero's results are
 * not skewed by evolving teammates.
 *
 * <p>The full role set is buildable: Tank (Fighter/Champion), Sustained-DPS (Hunter Ranger archer), Burst (Rogue/
 * Thief), Healer (Cleric/Life), Controller (Wizard/Evoker) and Buffer (Bard).
 */
public final class Fillers {

    private Fillers() {}

    /** A filler: a frozen build that can be placed into a party slot as a fresh combatant. */
    public interface Filler {
        Role role();

        Combatant make(String id, Side side, Cell position);
    }

    private static AbilityScores scores(int str, int dex, int con, int intel, int wis, int cha) {
        return AbilityScores.of(str, dex, con, intel, wis, cha);
    }

    /**
     * Build the filler set available at this level. All database-dependent values are resolved now, so the returned
     * fillers hold no reference to the source and can be used (concurrently) after it is closed.
     */
    public static Map<Role, Filler> load(ContentSource source, int level) {
        ClassInfo fighter = source.classInfo("fighter");
        ClassInfo ranger = source.classInfo("ranger");
        ClassInfo rogue = source.classInfo("rogue");
        ClassInfo cleric = source.classInfo("cleric");
        ClassInfo wizard = source.classInfo("wizard");
        ClassInfo bard = source.classInfo("bard");

        WeaponInfo longsword = source.weapon(Gear.LONGSWORD);
        WeaponInfo longbow = source.weapon(Gear.LONGBOW);
        WeaponInfo rapier = source.weapon(Gear.RAPIER);
        WeaponInfo mace = source.weapon(Gear.MACE);
        WeaponInfo dagger = source.weapon(Gear.DAGGER);
        ArmorInfo chainMail = source.armor(Gear.CHAIN_MAIL);
        ArmorInfo studded = source.armor(Gear.STUDDED_LEATHER_ARMOR);
        ArmorInfo scaleMail = source.armor(Gear.SCALE_MAIL);
        ArmorInfo leather = source.armor(Gear.LEATHER_ARMOR);

        BuildProgression rogueProgression = source.progression("rogue", level);
        BuildProgression rangerProgression = source.progression("ranger", level);
        List<SpellcastingSpec.Slot> clericSlots = source.spellSlots("cleric", level);
        List<SpellcastingSpec.Slot> wizardSlots = source.spellSlots("wizard", level);
        List<SpellcastingSpec.Slot> bardSlots = source.spellSlots("bard", level);

        Map<Role, Filler> out = new EnumMap<>(Role.class);
        out.put(Role.TANK, filler(Role.TANK, (id, side, pos) -> CharacterCompiler.compile(
                BuildSpec.builder("Fighter (Tank)", fighter, level, scores(15, 13, 14, 10, 12, 8), longsword)
                        .id(id).side(side).subclass("champion").armor(chainMail).shield(true)
                        .fightingStyle(FightingStyle.DEFENSE).position(pos).build())));
        out.put(Role.SUSTAINED_DPS, filler(Role.SUSTAINED_DPS, (id, side, pos) -> CharacterCompiler.compile(
                BuildSpec.builder("Ranger (Sustained DPS)", ranger, level, scores(10, 15, 14, 8, 13, 12), longbow)
                        .id(id).side(side).subclass("hunter").armor(studded).fightingStyle(FightingStyle.ARCHERY)
                        .progression(rangerProgression).position(pos).build())));
        out.put(Role.BURST, filler(Role.BURST, (id, side, pos) -> CharacterCompiler.compile(
                BuildSpec.builder("Rogue (Burst)", rogue, level, scores(10, 15, 13, 12, 14, 8), rapier)
                        .id(id).side(side).subclass("thief").armor(studded)
                        .progression(rogueProgression).position(pos).build())));
        out.put(Role.HEALER, filler(Role.HEALER, (id, side, pos) -> CasterCompiler.compile(
                CasterBuildSpec.builder("Cleric (Healer)", cleric, level, scores(13, 10, 14, 8, 15, 12), mace, Ability.WIS)
                        .id(id).side(side).subclass("life-domain").armor(scaleMail).shield(true)
                        .cantrips(List.of(SpellCatalog.SACRED_FLAME))
                        .spells(List.of(SpellCatalog.CURE_WOUNDS, SpellCatalog.HEALING_WORD, SpellCatalog.GUIDING_BOLT))
                        .slots(clericSlots).position(pos).build())));
        out.put(Role.CONTROLLER, filler(Role.CONTROLLER, (id, side, pos) -> CasterCompiler.compile(
                CasterBuildSpec.builder("Wizard (Controller)", wizard, level, scores(8, 14, 14, 15, 12, 10), dagger, Ability.INT)
                        .id(id).side(side).subclass("evoker")
                        .cantrips(List.of(SpellCatalog.FIRE_BOLT))
                        .spells(List.of(SpellCatalog.HYPNOTIC_PATTERN, SpellCatalog.HOLD_PERSON, SpellCatalog.FIREBALL,
                                SpellCatalog.SCORCHING_RAY))
                        .slots(wizardSlots).position(pos).build())));
        out.put(Role.BUFFER, filler(Role.BUFFER, (id, side, pos) -> CasterCompiler.compile(
                CasterBuildSpec.builder("Bard (Buffer)", bard, level, scores(8, 14, 13, 10, 12, 15), rapier, Ability.CHA)
                        .id(id).side(side).armor(leather)
                        .spells(List.of(SpellCatalog.BLESS, SpellCatalog.HASTE))
                        .slots(bardSlots).position(pos).build())));
        return out;
    }

    @FunctionalInterface
    private interface Maker {
        Combatant make(String id, Side side, Cell position);
    }

    private static Filler filler(Role role, Maker maker) {
        return new Filler() {
            @Override
            public Role role() {
                return role;
            }

            @Override
            public Combatant make(String id, Side side, Cell position) {
                return maker.make(id, side, position);
            }
        };
    }
}
