package org.omnomnom.dnd.sim.domain.scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.omnomnom.dnd.sim.domain.combat.Combatant;
import org.omnomnom.dnd.sim.domain.combat.Side;
import org.omnomnom.dnd.sim.domain.content.Fillers;
import org.omnomnom.dnd.sim.domain.content.MonsterCatalog;
import org.omnomnom.dnd.sim.domain.content.MonsterTemplate;
import org.omnomnom.dnd.sim.domain.content.Role;
import org.omnomnom.dnd.sim.domain.grid.Cell;
import org.omnomnom.dnd.sim.domain.grid.Grid;

/**
 * The reference-party harness: party-scaled encounters and party assembly with the hero substituted into its role
 * slot. Encounter sizing scales with the party and is tuned to be hard but winnable, not matched to the exact XP
 * budget (a party-harness v1 choice).
 */
public final class PartyScenarios {

    private PartyScenarios() {}

    /** Party deployment (left) and enemy (right) cells of the shared 22 x 14 party map. */
    private static final Grid GRID = Grid.open(22, 14);

    private static final List<Cell> PARTY_CELLS = List.of(
            Cell.of(1, 5), Cell.of(1, 7), Cell.of(1, 9), Cell.of(2, 6), Cell.of(2, 8), Cell.of(2, 4));

    private static final List<Cell> ENEMY_CELLS = enemyCells();

    private static List<Cell> enemyCells() {
        List<Cell> cells = new ArrayList<>();
        for (int x = 18; x <= 20; x++) {
            for (int y = 2; y <= 11; y++) {
                cells.add(Cell.of(x, y));
            }
        }
        return List.copyOf(cells);
    }

    /** Enemy group: {@code perMember} scales with party size, {@code count} is fixed (for example a lone boss). */
    private record Group(String slug, int perMember, int count) {}

    private record Spec(String id, List<Group> enemies) {}

    private static Group perMember(String slug, int n) {
        return new Group(slug, n, 0);
    }

    private static Group fixed(String slug, int n) {
        return new Group(slug, 0, n);
    }

    // Level 5: hordes of weak foes so allies take real damage and a healer has work to do.
    private static final List<Spec> LEVEL_5 = List.of(
            new Spec("horde", List.of(perMember("goblin-warrior", 5))),
            new Spec("mixed", List.of(perMember("bugbear-warrior", 2), perMember("goblin-warrior", 2))));

    // Level 11: a legendary dragon boss with fixed adds, and a pack of CR-5 brutes.
    private static final List<Spec> LEVEL_11 = List.of(
            new Spec("boss-young-dragon", List.of(fixed("young-red-dragon", 1), fixed("winter-wolf", 2))),
            new Spec("troll-pack", List.of(perMember("troll", 1))));

    // Level 17: an adult dragon boss and a fire-giant pack.
    private static final List<Spec> LEVEL_17 = List.of(
            new Spec("boss-adult-dragon", List.of(fixed("adult-red-dragon", 1), fixed("troll", 2))),
            new Spec("giant-pack", List.of(fixed("fire-giant", 1), perMember("troll", 1))));

    private static List<Spec> specsForLevel(int level) {
        if (level >= 17) {
            return LEVEL_17;
        }
        if (level >= 11) {
            return LEVEL_11;
        }
        return LEVEL_5;
    }

    /** Party scenarios for a party of {@code partySize} at {@code level} (nearest checkpoint at or below). */
    public static List<PartyScenario> load(MonsterCatalog monsters, int partySize, int level) {
        List<PartyScenario> out = new ArrayList<>();
        for (Spec spec : specsForLevel(level)) {
            List<MonsterTemplate> plan = new ArrayList<>();
            for (Group group : spec.enemies()) {
                MonsterTemplate template = monsters.find(group.slug());
                if (template == null) {
                    throw new IllegalArgumentException("party scenario " + spec.id() + ": monster not found: " + group.slug());
                }
                int n = group.perMember() * partySize + group.count();
                for (int i = 0; i < n; i++) {
                    plan.add(template);
                }
            }
            int size = Math.min(plan.size(), ENEMY_CELLS.size());
            out.add(new PartyScenario(spec.id(), partySize, GRID, PARTY_CELLS, plan.subList(0, size), ENEMY_CELLS.subList(0, size)));
        }
        return List.copyOf(out);
    }

    /**
     * Assemble a party: the hero fills the slot for {@code heroRole} (or the template's flex slot if the template
     * lacks that role), and fillers fill the rest with ids {@code ally-<role>-<index>}.
     */
    public static List<Combatant> assemble(
            Map<Role, Fillers.Filler> fillers, PartyTemplate template, Combatant hero, Role heroRole, List<Cell> partyCells) {
        Role heroSlot = template.roles().contains(heroRole) ? heroRole : template.flex();
        List<Combatant> party = new ArrayList<>();
        boolean heroPlaced = false;
        for (int i = 0; i < template.roles().size(); i++) {
            Role role = template.roles().get(i);
            Cell position = i < partyCells.size() ? partyCells.get(i) : partyCells.get(partyCells.size() - 1);
            // The hero takes the first slot matching its role; duplicate role slots are filled normally.
            if (role == heroSlot && !heroPlaced) {
                hero.setPosition(position);
                party.add(hero);
                heroPlaced = true;
            } else {
                Fillers.Filler filler = fillers.get(role);
                if (filler == null) {
                    throw new IllegalArgumentException("no filler for role " + role.code());
                }
                party.add(filler.make("ally-" + role.code() + "-" + i, Side.PARTY, position));
            }
        }
        return party;
    }
}
