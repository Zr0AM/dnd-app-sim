package org.omnomnom.dnd.sim.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    /** A clock the test moves by hand. */
    static final class ManualClock extends Clock {
        Instant now = Instant.parse("2026-10-08T12:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    ManualClock clock = new ManualClock();
    RateLimiter limiter = new RateLimiter(clock, 60, 6);

    @Test
    void aClientMayBurstItsWholeMinuteThenIsRefused() {
        for (int i = 0; i < 60; i++) {
            assertThat(limiter.tryAcquire("a", RateLimiter.Cost.ORDINARY).allowed()).as("request %d", i).isTrue();
        }
        RateLimiter.Decision refused = limiter.tryAcquire("a", RateLimiter.Cost.ORDINARY);
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterSeconds()).isEqualTo(1); // 60 per minute refills one token a second
    }

    @Test
    void tokensComeBackAtTheSteadyRate() {
        for (int i = 0; i < 6; i++) {
            limiter.tryAcquire("a", RateLimiter.Cost.SIMULATION);
        }
        RateLimiter.Decision refused = limiter.tryAcquire("a", RateLimiter.Cost.SIMULATION);
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterSeconds()).isEqualTo(10); // 6 per minute: one every ten seconds
        clock.advance(Duration.ofSeconds(9));
        assertThat(limiter.tryAcquire("a", RateLimiter.Cost.SIMULATION).allowed()).isFalse();
        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.tryAcquire("a", RateLimiter.Cost.SIMULATION).allowed()).isTrue();
        assertThat(limiter.tryAcquire("a", RateLimiter.Cost.SIMULATION).allowed()).isFalse();
    }

    @Test
    void theBucketNeverHoldsMoreThanAMinute() {
        clock.advance(Duration.ofHours(5));
        int allowed = 0;
        while (limiter.tryAcquire("a", RateLimiter.Cost.SIMULATION).allowed()) {
            allowed++;
        }
        assertThat(allowed).isEqualTo(6);
    }

    @Test
    void clientsAndCostClassesHaveSeparateBudgets() {
        for (int i = 0; i < 6; i++) {
            limiter.tryAcquire("a", RateLimiter.Cost.SIMULATION);
        }
        assertThat(limiter.tryAcquire("a", RateLimiter.Cost.SIMULATION).allowed()).isFalse();
        assertThat(limiter.tryAcquire("a", RateLimiter.Cost.ORDINARY).allowed()).isTrue();
        assertThat(limiter.tryAcquire("b", RateLimiter.Cost.SIMULATION).allowed()).isTrue();
    }

    @Test
    void idleBucketsAreForgottenOnceTheTableIsLarge() {
        for (int i = 0; i < 10_001; i++) {
            limiter.tryAcquire("client-" + i, RateLimiter.Cost.ORDINARY);
        }
        assertThat(limiter.tracked()).isGreaterThan(10_000);
        clock.advance(Duration.ofMinutes(11));
        limiter.tryAcquire("fresh", RateLimiter.Cost.ORDINARY);
        assertThat(limiter.tracked()).isLessThan(10);
    }

    @Test
    void anAllowedDecisionHasNoWait() {
        RateLimiter.Decision d = limiter.tryAcquire("a", RateLimiter.Cost.ORDINARY);
        assertThat(d.allowed()).isTrue();
        assertThat(d.retryAfterSeconds()).isZero();
    }

    @Test
    void recentlyUsedBucketsSurviveCleanupAndTenIdleMinutesIsTheBoundary() {
        for (int i = 0; i < 10_001; i++) {
            limiter.tryAcquire("client-" + i, RateLimiter.Cost.ORDINARY);
        }
        // Five idle minutes is not idle enough to forget anyone.
        clock.advance(Duration.ofMinutes(5));
        limiter.tryAcquire("probe", RateLimiter.Cost.ORDINARY);
        assertThat(limiter.tracked()).isGreaterThan(10_000);
        // Exactly ten minutes is still kept; only beyond it is a bucket forgotten.
        clock.advance(Duration.ofMinutes(5));
        limiter.tryAcquire("probe2", RateLimiter.Cost.ORDINARY);
        assertThat(limiter.tracked()).isGreaterThan(10_000);
        clock.advance(Duration.ofNanos(1));
        limiter.tryAcquire("probe3", RateLimiter.Cost.ORDINARY);
        assertThat(limiter.tracked()).isLessThan(10);
    }
}
