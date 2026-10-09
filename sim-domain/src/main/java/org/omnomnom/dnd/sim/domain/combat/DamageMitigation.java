package org.omnomnom.dnd.sim.domain.combat;

import java.util.Map;
import org.omnomnom.dnd.sim.domain.core.DamageResponse;
import org.omnomnom.dnd.sim.domain.core.DamageType;

/**
 * Damage mitigation math (SRD "Resistance and Vulnerability", "Immunity"). Order of operations: flat reductions
 * first, then halve for Resistance (round down), then double for Vulnerability. Immunity zeroes the damage.
 *
 * <p>A creature has one response per damage type, which enforces "multiple instances count once" by
 * construction. The contrived "resistant to all and vulnerable to one type" case is out of scope, as upstream.
 */
public final class DamageMitigation {

    private DamageMitigation() {}

    /** Final damage of one type after flat reductions and the resistance/vulnerability/immunity pipeline. */
    public static int mitigate(int amount, DamageType type, Map<DamageType, DamageResponse> responses, int flatReduction) {
        return applyResponse(amount, responses.getOrDefault(type, DamageResponse.NORMAL), flatReduction);
    }

    public static int mitigate(int amount, DamageType type, Map<DamageType, DamageResponse> responses) {
        return mitigate(amount, type, responses, 0);
    }

    /** As {@code mitigate}, but with the response already resolved for the type. */
    public static int applyResponse(int amount, DamageResponse response, int flatReduction) {
        if (amount <= 0 || response == DamageResponse.IMMUNE) {
            return 0;
        }
        int dmg = Math.max(0, amount - flatReduction);
        if (response == DamageResponse.RESISTANT) {
            dmg = dmg / 2;
        }
        if (response == DamageResponse.VULNERABLE) {
            dmg = dmg * 2;
        }
        return dmg;
    }

    public static int applyResponse(int amount, DamageResponse response) {
        return applyResponse(amount, response, 0);
    }
}
