package org.omnomnom.dnd.sim.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-client token buckets. Each client has a bucket per cost class holding one minute's worth of its budget, refilled
 * continuously, so a client may burst up to a minute's allowance and then settles to the steady rate. State is in memory
 * and per instance: behind several instances each enforces its own budget, which is the usual trade for a limiter that
 * needs no shared store.
 *
 * <p>The number of tracked clients is capped. When the cap is passed, idle clients are forgotten first; if that is not
 * enough (for example a flood of fresh source addresses), the least recently seen are dropped until the table is back to
 * 90% of the cap. A forgotten client simply starts again with a full bucket. Cleanup is amortized: after it, at least a
 * tenth of the cap in new clients must arrive before it runs again, so it is never a per-request scan.
 */
public final class RateLimiter {

    /** Which budget a request draws on. */
    public enum Cost {
        ORDINARY,
        SIMULATION
    }

    /** The outcome of asking for a token. */
    public record Decision(boolean allowed, int retryAfterSeconds) {}

    private final Clock clock;
    private final int ordinaryPerMinute;
    private final int simulationPerMinute;
    private final int maxClients;
    private final long idleNanos;
    private final Map<String, ClientState> clients = new ConcurrentHashMap<>();
    private final Object cleanupLock = new Object();

    public RateLimiter(Clock clock, int ordinaryPerMinute, int simulationPerMinute) {
        this(clock, ordinaryPerMinute, simulationPerMinute, 10_000, Duration.ofMinutes(10));
    }

    /**
     * @param maxClients clients tracked before cleanup forgets idle or least recently seen ones
     * @param idleTimeout how long a client must be silent to count as idle
     */
    public RateLimiter(Clock clock, int ordinaryPerMinute, int simulationPerMinute, int maxClients, Duration idleTimeout) {
        this.clock = clock;
        this.ordinaryPerMinute = ordinaryPerMinute;
        this.simulationPerMinute = simulationPerMinute;
        this.maxClients = Math.max(1, maxClients);
        this.idleNanos = idleTimeout.toNanos();
    }

    /** One client's two buckets; guarded by its own monitor. */
    private static final class ClientState {
        double ordinaryTokens;
        double simulationTokens;
        long lastNanos;

        ClientState(double ordinary, double simulation, long nanos) {
            this.ordinaryTokens = ordinary;
            this.simulationTokens = simulation;
            this.lastNanos = nanos;
        }
    }

    private long nanos() {
        Instant now = clock.instant(); // read once: seconds and nanos from different instants could straddle a second
        return now.getEpochSecond() * 1_000_000_000L + now.getNano();
    }

    /** Take one token from the client's bucket for this cost class. */
    public Decision tryAcquire(String client, Cost cost) {
        long now = nanos();
        ClientState state = clients.computeIfAbsent(client, k -> new ClientState(ordinaryPerMinute, simulationPerMinute, now));
        Decision decision;
        synchronized (state) {
            long elapsed = Math.max(0, now - state.lastNanos);
            state.ordinaryTokens = Math.min(ordinaryPerMinute, state.ordinaryTokens + elapsed / 60e9 * ordinaryPerMinute);
            state.simulationTokens = Math.min(simulationPerMinute, state.simulationTokens + elapsed / 60e9 * simulationPerMinute);
            state.lastNanos = Math.max(state.lastNanos, now);
            int perMinute = cost == Cost.SIMULATION ? simulationPerMinute : ordinaryPerMinute;
            double tokens = cost == Cost.SIMULATION ? state.simulationTokens : state.ordinaryTokens;
            if (tokens >= 1 - 1e-9) { // tolerance for accumulated floating-point error in the refill
                if (cost == Cost.SIMULATION) {
                    state.simulationTokens -= 1;
                } else {
                    state.ordinaryTokens -= 1;
                }
                decision = new Decision(true, 0);
            } else {
                decision = new Decision(false, Math.max(1, (int) Math.ceil((1 - tokens) / perMinute * 60)));
            }
        }
        if (clients.size() > maxClients) {
            cleanup(now);
        }
        return decision;
    }

    private void cleanup(long now) {
        synchronized (cleanupLock) {
            if (clients.size() <= maxClients) {
                return; // another thread already cleaned up
            }
            record Seen(String client, long lastNanos) {}
            List<Seen> seen = new ArrayList<>(clients.size());
            clients.forEach((client, state) -> {
                synchronized (state) {
                    seen.add(new Seen(client, state.lastNanos));
                }
            });
            int target = (int) (maxClients * 0.9);
            int remaining = clients.size();
            for (Seen s : seen) {
                if (now - s.lastNanos() > idleNanos) {
                    clients.remove(s.client());
                    remaining--;
                }
            }
            if (remaining > maxClients) {
                seen.sort(Comparator.comparingLong(Seen::lastNanos));
                for (Seen s : seen) {
                    if (remaining <= target) {
                        break;
                    }
                    if (clients.remove(s.client()) != null) {
                        remaining--;
                    }
                }
            }
        }
    }

    /** Clients currently tracked (for tests and metrics). */
    public int tracked() {
        return clients.size();
    }
}
