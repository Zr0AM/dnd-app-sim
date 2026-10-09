package org.omnomnom.dnd.sim.domain.combat;

/**
 * A starting resource pool, for example Rage uses. {@code rechargeShort} defaults to none and
 * {@code rechargeLong} to everything when omitted.
 */
public record ResourceSpec(String id, int max, Recharge rechargeShort, Recharge rechargeLong) {

    public ResourceSpec {
        rechargeShort = rechargeShort == null ? Recharge.NONE : rechargeShort;
        rechargeLong = rechargeLong == null ? Recharge.FULL : rechargeLong;
    }

    public static ResourceSpec longRest(String id, int max) {
        return new ResourceSpec(id, max, null, null);
    }
}
