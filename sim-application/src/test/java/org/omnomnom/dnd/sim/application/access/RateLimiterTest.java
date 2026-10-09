package org.omnomnom.dnd.sim.application.access;

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
    void anAllowedDecisionHasNoWait() {
        RateLimiter.Decision d = limiter.tryAcquire("a", RateLimiter.Cost.ORDINARY);
        assertThat(d.allowed()).isTrue();
        assertThat(d.retryAfterSeconds()).isZero();
    }

    private RateLimiter capped(int maxClients) {
        return new RateLimiter(clock, 60, 6, maxClients, Duration.ofMinutes(10));
    }

    @Test
    void idleClientsAreForgottenWhenTheCapIsPassed() {
        RateLimiter small = capped(100);
        for (int i = 0; i < 100; i++) {
            small.tryAcquire("client-" + i, RateLimiter.Cost.ORDINARY);
        }
        assertThat(small.tracked()).isEqualTo(100);
        clock.advance(Duration.ofMinutes(11));
        small.tryAcquire("fresh", RateLimiter.Cost.ORDINARY);
        assertThat(small.tracked()).isEqualTo(1);
    }

    @Test
    void exactlyTheIdleTimeoutIsNotYetIdle() {
        RateLimiter small = capped(100);
        for (int i = 0; i < 100; i++) {
            small.tryAcquire("client-" + i, RateLimiter.Cost.ORDINARY);
        }
        clock.advance(Duration.ofMinutes(10));
        small.tryAcquire("fresh", RateLimiter.Cost.ORDINARY);
        // Nobody is idle, so the least recently seen are dropped to 90% of the cap instead.
        assertThat(small.tracked()).isEqualTo(90);
    }

    @Test
    void aFloodOfActiveClientsIsBoundedByDroppingTheLeastRecentlySeen() {
        RateLimiter small = capped(100);
        // A real client spends its whole simulation budget, then a flood of fresh addresses arrives.
        for (int i = 0; i < 6; i++) {
            small.tryAcquire("real", RateLimiter.Cost.SIMULATION);
        }
        clock.advance(Duration.ofSeconds(1));
        for (int i = 0; i < 1_000; i++) {
            small.tryAcquire("flood-" + i, RateLimiter.Cost.ORDINARY);
            assertThat(small.tracked()).isLessThanOrEqualTo(100);
        }
        // The most recent flood clients are still tracked (their budgets are not reset).
        for (int i = 0; i < 5; i++) {
            small.tryAcquire("flood-999", RateLimiter.Cost.ORDINARY);
        }
        assertThat(small.tracked()).isLessThanOrEqualTo(100);
    }

    @Test
    void cleanupIsAmortizedNotPerRequest() {
        RateLimiter small = capped(100);
        for (int i = 0; i < 101; i++) {
            small.tryAcquire("c-" + i, RateLimiter.Cost.ORDINARY);
        }
        assertThat(small.tracked()).isEqualTo(90);
        // Ten more clients fit before the next cleanup.
        for (int i = 0; i < 10; i++) {
            small.tryAcquire("d-" + i, RateLimiter.Cost.ORDINARY);
        }
        assertThat(small.tracked()).isEqualTo(100);
    }

    @Test
    void aClockReadThatStraddlesASecondDoesNotCreditExtraTokens() {
        // Every read advances one nanosecond, starting a nanosecond before a whole second. Reading the clock twice for
        // one timestamp would combine the seconds of one read with the nanoseconds of the next and step back ~1 s.
        Clock stepping = new Clock() {
            Instant next = Instant.parse("2026-10-08T12:00:09.999999999Z");

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
                Instant now = next;
                next = next.plusNanos(1);
                return now;
            }
        };
        RateLimiter strict = new RateLimiter(stepping, 60, 6);
        for (int i = 0; i < 60; i++) {
            assertThat(strict.tryAcquire("a", RateLimiter.Cost.ORDINARY).allowed()).isTrue();
        }
        assertThat(strict.tryAcquire("a", RateLimiter.Cost.ORDINARY).allowed()).isFalse();
    }

    @Test
    void theDefaultCapIsTenThousandClients() {
        for (int i = 0; i < 10_000; i++) {
            limiter.tryAcquire("client-" + i, RateLimiter.Cost.ORDINARY);
        }
        assertThat(limiter.tracked()).isEqualTo(10_000);
        limiter.tryAcquire("one-more", RateLimiter.Cost.ORDINARY);
        assertThat(limiter.tracked()).isEqualTo(9_000);
    }
}
