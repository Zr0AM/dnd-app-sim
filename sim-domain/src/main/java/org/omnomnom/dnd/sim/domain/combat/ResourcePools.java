package org.omnomnom.dnd.sim.domain.combat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.omnomnom.dnd.sim.domain.combat.spell.SpellcastingSpec;

/**
 * A combatant's expendable resources: feature pools, spell slots and legendary actions, with the rest rules that
 * refill them. Per-fight markers (concentration, marks) stay with {@link Combatant}.
 */
final class ResourcePools {

    private final Map<String, ResourcePool> pools = new LinkedHashMap<>();
    private final Map<Integer, SlotPool> slots = new LinkedHashMap<>();
    private final boolean shortRestSlots;
    private final int legendaryMax;
    private int legendaryRemaining;

    ResourcePools(List<ResourceSpec> resources, SpellcastingSpec spellcasting, int legendaryActions) {
        this.shortRestSlots = spellcasting != null && spellcasting.shortRestSlots();
        if (spellcasting != null) {
            for (SpellcastingSpec.Slot s : spellcasting.slots()) {
                slots.put(s.level(), new SlotPool(s.count(), s.count()));
            }
        }
        for (ResourceSpec r : resources) {
            pools.put(r.id(), new ResourcePool(r.max(), r.max(), r.rechargeShort(), r.rechargeLong()));
        }
        this.legendaryMax = legendaryActions;
        this.legendaryRemaining = legendaryActions;
    }

    /** How many uses of a resource remain (0 if the pool is undefined). */
    int resourceCount(String id) {
        ResourcePool pool = pools.get(id);
        return pool == null ? 0 : pool.current;
    }

    boolean spendResource(String id) {
        return spendResource(id, 1);
    }

    /** Spend {@code n} of a resource if available; returns whether it was spent. */
    boolean spendResource(String id, int n) {
        ResourcePool pool = pools.get(id);
        if (pool == null || pool.current < n) {
            return false;
        }
        pool.current -= n;
        return true;
    }

    /** Restore short-rest resources (and Pact Magic slots, for a Warlock). */
    void shortRest() {
        for (ResourcePool pool : pools.values()) {
            pool.current = pool.rechargeShort.applyTo(pool.current, pool.max);
        }
        if (shortRestSlots) {
            restoreSlots();
        }
    }

    /** Restore long-rest (and short-rest) resources and all spell slots. */
    void longRest() {
        for (ResourcePool pool : pools.values()) {
            pool.current = pool.rechargeShort.applyTo(pool.current, pool.max);
        }
        for (ResourcePool pool : pools.values()) {
            pool.current = pool.rechargeLong.applyTo(pool.current, pool.max);
        }
        restoreSlots();
    }

    int legendaryMax() {
        return legendaryMax;
    }

    int legendaryRemaining() {
        return legendaryRemaining;
    }

    /** Refresh legendary actions (at the start of the boss's turn). */
    void refreshLegendary() {
        legendaryRemaining = legendaryMax;
    }

    /** Spend one legendary action if available. */
    boolean spendLegendary() {
        if (legendaryRemaining <= 0) {
            return false;
        }
        legendaryRemaining -= 1;
        return true;
    }

    /** Remaining slots of a given spell level. */
    int slotCount(int level) {
        SlotPool pool = slots.get(level);
        return pool == null ? 0 : pool.current;
    }

    /** The spell levels (ascending) that currently have at least one slot. */
    List<Integer> availableSlotLevels() {
        return slots.entrySet().stream()
                .filter(e -> e.getValue().current > 0)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    /** Spend one slot of the given level; returns whether a slot was available. */
    boolean spendSlot(int level) {
        SlotPool pool = slots.get(level);
        if (pool == null || pool.current <= 0) {
            return false;
        }
        pool.current -= 1;
        return true;
    }

    /** Restore all spell slots (a long rest). */
    void restoreSlots() {
        for (SlotPool pool : slots.values()) {
            pool.current = pool.max;
        }
    }

    private static final class ResourcePool {
        int current;
        final int max;
        final Recharge rechargeShort;
        final Recharge rechargeLong;

        ResourcePool(int current, int max, Recharge rechargeShort, Recharge rechargeLong) {
            this.current = current;
            this.max = max;
            this.rechargeShort = rechargeShort;
            this.rechargeLong = rechargeLong;
        }
    }

    private static final class SlotPool {
        int current;
        final int max;

        SlotPool(int current, int max) {
            this.current = current;
            this.max = max;
        }
    }
}
