package org.omnomnom.dnd.sim.domain.dice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.domain.rng.LabeledRandom;
import org.omnomnom.dnd.sim.domain.rng.Rng;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Port of {@code sim/src/dice/dice.spec.ts}. {@code toBeCloseTo(x, n)} is {@code within(0.5 * 10^-n)}. */
class DiceTest {

    static Rng rng() {
        return new LabeledRandom(2024).stream("test");
    }

    @Test
    void rollDieStaysWithinRange() {
        Rng r = rng();
        for (int i = 0; i < 2000; i++) {
            assertThat(Dice.rollDie(r, 6)).isBetween(1, 6);
        }
    }

    @Test
    void rollDieRejectsNonPositiveSides() {
        assertThatThrownBy(() -> Dice.rollDie(rng(), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void empiricalMeanOfD6ApproachesThreePointFive() {
        Rng r = rng();
        long sum = 0;
        int n = 100000;
        for (int i = 0; i < n; i++) {
            sum += Dice.rollDie(r, 6);
        }
        assertThat((double) sum / n).isCloseTo(Dice.meanDie(6), within(0.05));
    }

    @Test
    void coversEveryFaceOfAD6() {
        Rng r = rng();
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            seen.add(Dice.rollDie(r, 6));
        }
        assertThat(seen).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6);
    }

    @Test
    void rollDiceSumsCountDiceWithinBounds() {
        Rng r = rng();
        for (int i = 0; i < 1000; i++) {
            assertThat(Dice.rollDice(r, 3, 8)).isBetween(3, 24);
        }
    }

    @Test
    void rollDiceRejectsNegativeCount() {
        assertThatThrownBy(() -> Dice.rollDice(rng(), -1, 6)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rollAppliesTheFlatBonus() {
        Rng r = rng();
        Dice d = Dice.of(2, 6, 5);
        for (int i = 0; i < 1000; i++) {
            assertThat(d.roll(r)).isBetween(2 + 5, 12 + 5);
        }
    }

    @Test
    void zeroCountDiceIsAFlatAmount() {
        assertThat(Dice.of(0, 1, 7).roll(rng())).isEqualTo(7);
    }

    @Test
    void empiricalMeanOfEightD6ApproachesTheClosedForm() {
        Rng r = rng();
        Dice d = Dice.of(8, 6);
        long sum = 0;
        int n = 50000;
        for (int i = 0; i < n; i++) {
            sum += d.roll(r);
        }
        assertThat((double) sum / n).isCloseTo(d.mean(), within(0.5));
        assertThat(d.mean()).isEqualTo(28.0);
    }

    @Test
    void meanMatchesCountTimesMeanDiePlusBonus() {
        assertThat(Dice.of(2, 8, 3).mean()).isEqualTo(2 * 4.5 + 3);
        assertThat(Dice.of(1, 10).mean()).isEqualTo(5.5);
    }

    @Test
    void withoutBonusDropsOnlyTheBonus() {
        assertThat(Dice.of(2, 6, 5).withoutBonus()).isEqualTo(Dice.of(2, 6));
    }

    @Test
    void normalD20StaysInRange() {
        Rng r = rng();
        for (int i = 0; i < 1000; i++) {
            assertThat(Dice.rollD20(r)).isBetween(1, 20);
        }
    }

    @Test
    void advantageMeanExceedsNormalExceedsDisadvantage() {
        double adv = meanOf(Advantage.ADVANTAGE);
        double norm = meanOf(Advantage.NORMAL);
        double dis = meanOf(Advantage.DISADVANTAGE);
        assertThat(adv).isCloseTo(Dice.meanD20(Advantage.ADVANTAGE), within(0.05));
        assertThat(norm).isCloseTo(Dice.meanD20(Advantage.NORMAL), within(0.05));
        assertThat(dis).isCloseTo(Dice.meanD20(Advantage.DISADVANTAGE), within(0.05));
        assertThat(adv).isGreaterThan(norm);
        assertThat(norm).isGreaterThan(dis);
    }

    private static double meanOf(Advantage adv) {
        Rng r = new LabeledRandom(1).stream("d20");
        long s = 0;
        int n = 200000;
        for (int i = 0; i < n; i++) {
            s += Dice.rollD20(r, adv);
        }
        return (double) s / n;
    }

    @Test
    void chanceToHitNeedingElevenIsFiftyPercent() {
        assertThat(Dice.chanceToHit(11)).isCloseTo(0.5, within(1e-10));
    }

    @Test
    void chanceToHitClamps() {
        assertThat(Dice.chanceToHit(1)).isEqualTo(1.0);
        assertThat(Dice.chanceToHit(0)).isEqualTo(1.0);
        assertThat(Dice.chanceToHit(21)).isEqualTo(0.0);
        assertThat(Dice.chanceToHit(25)).isEqualTo(0.0);
    }

    @Test
    void advantageAndDisadvantageMatchTheSquareFormulas() {
        double p = 0.5;
        assertThat(Dice.chanceToHit(11, Advantage.ADVANTAGE)).isCloseTo(1 - Math.pow(1 - p, 2), within(1e-10));
        assertThat(Dice.chanceToHit(11, Advantage.DISADVANTAGE)).isCloseTo(Math.pow(p, 2), within(1e-10));
    }

    @Test
    void empiricalHitRateMatchesChanceToHitUnderAdvantage() {
        Rng r = new LabeledRandom(777).stream("hit");
        int need = 15;
        int hits = 0;
        int n = 200000;
        for (int i = 0; i < n; i++) {
            if (Dice.rollD20(r, Advantage.ADVANTAGE) >= need) {
                hits++;
            }
        }
        assertThat((double) hits / n).isCloseTo(Dice.chanceToHit(need, Advantage.ADVANTAGE), within(0.005));
    }

    /** Dice rolls reproduce the TypeScript sequences exactly (same RNG, same draw order). */
    @Test
    void rollsMatchTypeScriptReferenceSequences() throws Exception {
        JsonNode ref;
        try (InputStream in = getClass().getResourceAsStream("/reference/rng.json")) {
            ref = new ObjectMapper().readTree(in).get("dice");
        }
        long seed = ref.get("seed").asLong();
        for (Advantage adv : Advantage.values()) {
            String key = adv.name().toLowerCase(java.util.Locale.ROOT);
            Rng r = new LabeledRandom(seed).stream(ref.get("d20Label").asString());
            for (JsonNode expected : ref.get("d20").get(key)) {
                assertThat(Dice.rollD20(r, adv)).as(key).isEqualTo(expected.asInt());
            }
        }
        Rng r = new LabeledRandom(seed).stream(ref.get("eightD6Plus2").get("label").asString());
        for (JsonNode expected : ref.get("eightD6Plus2").get("rolls")) {
            assertThat(Dice.of(8, 6, 2).roll(r)).isEqualTo(expected.asInt());
        }
    }
}
