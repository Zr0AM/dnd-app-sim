package org.omnomnom.dnd.sim.domain.scenario;

import java.util.List;
import org.omnomnom.dnd.sim.domain.content.build.Role;

/**
 * A reference-party template: the roles present, in a fixed order.
 *
 * @param flex the role a hero without a matching slot replaces instead
 * @param weight report weight for aggregation (R6, R4 and R3 are weighted 2:2:1)
 */
public record PartyTemplate(String id, List<Role> roles, Role flex, int weight) {

    public PartyTemplate {
        roles = List.copyOf(roles);
    }

    /** The full six-role party. */
    public static final PartyTemplate R6 = new PartyTemplate(
            "R6", List.of(Role.TANK, Role.SUSTAINED_DPS, Role.BURST, Role.HEALER, Role.CONTROLLER, Role.BUFFER), Role.BURST, 2);

    public static final PartyTemplate R4 =
            new PartyTemplate("R4", List.of(Role.TANK, Role.BURST, Role.HEALER, Role.CONTROLLER), Role.BURST, 2);

    public static final PartyTemplate R3 =
            new PartyTemplate("R3", List.of(Role.TANK, Role.HEALER, Role.CONTROLLER), Role.CONTROLLER, 1);

    public static final List<PartyTemplate> ALL = List.of(R6, R4, R3);
}
