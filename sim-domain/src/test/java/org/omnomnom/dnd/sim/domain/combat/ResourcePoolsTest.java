package org.omnomnom.dnd.sim.domain.combat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.domain.combat.spell.SpellcastingSpec;
import org.omnomnom.dnd.sim.domain.core.Ability;

/** Unit tests for {@link ResourcePools}, the resource / slot / legendary state extracted from {@link Combatant}. */
class ResourcePoolsTest {

    private static ResourcePools pools() {
        return new ResourcePools(
                List.of(
                        new ResourceSpec("rage", 3, null, null),
                        new ResourceSpec("channel", 1, Recharge.of(1), null)),
                null,
                0);
    }

    private static SpellcastingSpec pactSlots() {
        return new SpellcastingSpec(
                Ability.CHA,
                List.of(new SpellcastingSpec.Slot(1, 2), new SpellcastingSpec.Slot(3, 1)),
                List.of(),
                List.of(),
                true);
    }

    @Test
    void unknownResourceCountsZeroAndCannotBeSpent() {
        ResourcePools p = pools();
        assertThat(p.resourceCount("nope")).isZero();
        assertThat(p.spendResource("nope")).isFalse();
    }

    @Test
    void failedSpendLeavesThePoolUnchanged() {
        ResourcePools p = pools();
        assertThat(p.spendResource("channel", 2)).isFalse();
        assertThat(p.resourceCount("channel")).isEqualTo(1);
        assertThat(p.spendResource("rage", 2)).isTrue();
        assertThat(p.resourceCount("rage")).isEqualTo(1);
    }

    @Test
    void shortRestAppliesShortRechargeCappedAtMax() {
        ResourcePools p = pools();
        p.spendResource("rage", 3);
        p.spendResource("channel", 1);
        p.shortRest();
        assertThat(p.resourceCount("rage")).isZero();
        assertThat(p.resourceCount("channel")).isEqualTo(1);
    }

    @Test
    void shortRestRefillsPactSlotsOnlyWhenShortRestSlots() {
        ResourcePools pact = new ResourcePools(List.of(), pactSlots(), 0);
        pact.spendSlot(1);
        pact.spendSlot(3);
        pact.shortRest();
        assertThat(pact.slotCount(1)).isEqualTo(2);
        assertThat(pact.slotCount(3)).isEqualTo(1);

        ResourcePools normal = new ResourcePools(
                List.of(),
                new SpellcastingSpec(
                        Ability.INT,
                        List.of(new SpellcastingSpec.Slot(1, 2)),
                        List.of(),
                        List.of(),
                        false),
                0);
        normal.spendSlot(1);
        normal.shortRest();
        assertThat(normal.slotCount(1)).isEqualTo(1);
    }

    @Test
    void longRestRefillsEverything() {
        ResourcePools p = pools();
        p.spendResource("rage", 3);
        p.spendResource("channel", 1);
        p.longRest();
        assertThat(p.resourceCount("rage")).isEqualTo(3);
        assertThat(p.resourceCount("channel")).isEqualTo(1);
    }

    @Test
    void availableSlotLevelsIsAscendingAndOmitsEmptyLevels() {
        ResourcePools p = new ResourcePools(List.of(), pactSlots(), 0);
        p.spendSlot(3);
        p.spendSlot(1);
        assertThat(p.availableSlotLevels()).containsExactly(1);
        p.restoreSlots();
        assertThat(p.availableSlotLevels()).containsExactly(1, 3);
        assertThat(p.spendSlot(2)).isFalse();
    }

    @Test
    void legendaryActionsSpendAndRefresh() {
        ResourcePools p = new ResourcePools(List.of(), null, 2);
        assertThat(p.legendaryMax()).isEqualTo(2);
        assertThat(p.spendLegendary()).isTrue();
        assertThat(p.spendLegendary()).isTrue();
        assertThat(p.spendLegendary()).isFalse();
        assertThat(p.legendaryRemaining()).isZero();
        p.refreshLegendary();
        assertThat(p.legendaryRemaining()).isEqualTo(2);
    }
}
