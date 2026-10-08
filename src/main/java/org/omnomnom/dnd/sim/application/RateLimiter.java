package org.omnomnom.dnd.sim.application;

import java.time.Clock;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-client token buckets. Each client has a bucket per cost class holding one minute's worth of its budget, refilled
 * continuously, so a client may burst up to a minute's allowance and then settles to the steady rate. State is in memory
 * and per instance: behind several instances each enforces its own budget, which is the usual trade for a limiter that
 * needs no shared store. Idle buckets are forgotten.
 */
public final class RateLimiter {

    /** Which budget a request draws on. */
    public enum Cost {
        ORDINARY,
        SIMULATION
    }

    /** The outcome of asking for a token. */
    public record Decision(boolean allowed, int retryAfterSeconds) {}

    private static final long IDLE_NANOS = 10L * 60 * 1_000_000_000L;
    private static final int MAX_BUCKETS = 10_000;

    private final Clock clock;
    private final int ordinaryPerMinute;
    private final int simulationPerMinute;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(Clock clock, int ordinaryPerMinute, int simulationPerMinute) {
        this.clock = clock;
        this.ordinaryPerMinute = ordinaryPerMinute;
        this.simulationPerMinute = simulationPerMinute;
    }

    private static final class Bucket {
        private double tokens;
        private long lastNanos;

        Bucket(double tokens, long nanos) {
            this.tokens = tokens;
            this.lastNanos = nanos;
        }
    }

    private long nanos() {
        return clock.instant().getEpochSecond() * 1_000_000_000L + clock.instant().getNano();
    }

    /** Take one token from the client's bucket for this cost class. */
    public Decision tryAcquire(String client, Cost cost) {
        int perMinute = cost == Cost.SIMULATION ? simulationPerMinute : ordinaryPerMinute;
        long now = nanos();
        if (buckets.size() > MAX_BUCKETS) {
            forgetIdle(now);
        }
        Bucket bucket = buckets.computeIfAbsent(client + "|" + cost, k -> new Bucket(perMinute, now));
        synchronized (bucket) {
            double refill = (now - bucket.lastNanos) / 60e9 * perMinute;
            bucket.tokens = Math.min(perMinute, bucket.tokens + Math.max(0, refill));
            bucket.lastNanos = now;
            if (bucket.tokens >= 1 - 1e-9) { // tolerance for accumulated floating-point error in the refill
                bucket.tokens -= 1;
                return new Decision(true, 0);
            }
            double missing = 1 - bucket.tokens;
            int wait = (int) Math.ceil(missing / perMinute * 60);
            return new Decision(false, Math.max(1, wait));
        }
    }

    private void forgetIdle(long now) {
        Iterator<Map.Entry<String, Bucket>> it = buckets.entrySet().iterator();
        while (it.hasNext()) {
            Bucket b = it.next().getValue();
            synchronized (b) {
                if (now - b.lastNanos > IDLE_NANOS) {
                    it.remove();
                }
            }
        }
    }

    /** Buckets currently tracked (for tests and metrics). */
    public int tracked() {
        return buckets.size();
    }
}
