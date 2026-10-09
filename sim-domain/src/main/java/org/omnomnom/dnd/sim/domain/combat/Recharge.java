package org.omnomnom.dnd.sim.domain.combat;

/** How much of a resource pool refills on a rest: everything, nothing, or a fixed number of uses. */
public record Recharge(boolean all, int amount) {

    public static final Recharge ALL = new Recharge(true, 0);
    public static final Recharge NONE = new Recharge(false, 0);

    public static Recharge of(int amount) {
        return new Recharge(false, amount);
    }

    int applyTo(int current, int max) {
        return all ? max : Math.min(max, current + amount);
    }
}
