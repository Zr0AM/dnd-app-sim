package org.omnomnom.dnd.sim.domain.opt.report;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.omnomnom.dnd.sim.domain.opt.evaluation.Objectives;

/**
 * Roles as weight presets over the objective axes. A role is a named weight vector; applying it to a report re-ranks
 * the Pareto front for that role without re-simulating ({@link Reports#rescore}). The control and support axes carry
 * signal only in the party harness (a solo martial has no allies to buff or heal and scores 0 there), but the presets
 * are defined over the same vector, so a party-context report ranks every role.
 */
public final class RolePresets {

    private RolePresets() {}

    private static final Map<String, Map<String, Double>> WEIGHTS = new LinkedHashMap<>();

    private static void role(String name, Object... pairs) {
        Map<String, Double> w = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            w.put((String) pairs[i], ((Number) pairs[i + 1]).doubleValue());
        }
        WEIGHTS.put(name, Map.copyOf(w));
    }

    static {
        // Steady damage that also survives the day.
        role("sustained-dps", Objectives.RELIABILITY, 1, Objectives.OFFENSE, 3, Objectives.SURVIVAL, 1, Objectives.EFFICIENCY, 2);
        // Drop targets fast; damage and pace over endurance.
        role("burst", Objectives.RELIABILITY, 1, Objectives.OFFENSE, 3, Objectives.SURVIVAL, 0, Objectives.EFFICIENCY, 3);
        // Absorb punishment and stay standing.
        role("tank", Objectives.RELIABILITY, 2, Objectives.OFFENSE, 1, Objectives.SURVIVAL, 3, Objectives.EFFICIENCY, 1);
        // A balanced all-rounder.
        role("generalist", Objectives.RELIABILITY, 1, Objectives.OFFENSE, 1, Objectives.SURVIVAL, 1, Objectives.EFFICIENCY, 1);
        // Lock down enemies: the control axis carries the weight.
        role("controller", Objectives.RELIABILITY, 1, Objectives.OFFENSE, 1, Objectives.SURVIVAL, 1, Objectives.CONTROL, 3, Objectives.EFFICIENCY, 1);
        // Keep the party standing: healing (and other support) over personal offense.
        role("healer", Objectives.RELIABILITY, 2, Objectives.OFFENSE, 0, Objectives.SURVIVAL, 1, Objectives.SUPPORT, 3, Objectives.EFFICIENCY, 1);
        // Make the party hit harder: buffs (support) plus a little of everything.
        role("buffer", Objectives.RELIABILITY, 1, Objectives.OFFENSE, 1, Objectives.SURVIVAL, 1, Objectives.CONTROL, 1, Objectives.SUPPORT, 3, Objectives.EFFICIENCY, 1);
    }

    /**
     * Roles whose signal lives only in the party harness (support and control axes), so a solo run can warn that
     * ranking them needs a party-context report.
     */
    public static final List<String> PARTY_ONLY = List.of("healer", "buffer", "controller");

    /** The role names, in declaration order. */
    public static List<String> names() {
        return List.copyOf(WEIGHTS.keySet());
    }

    public static Map<String, Double> weights(String role) {
        Map<String, Double> w = WEIGHTS.get(role);
        if (w == null) {
            throw new IllegalArgumentException("unknown role: " + role);
        }
        return w;
    }
}
