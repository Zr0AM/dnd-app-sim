package org.omnomnom.dnd.sim.application.execution;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.omnomnom.dnd.sim.application.error.BusyException;

/**
 * A bounded pool for CPU-bound simulation work, separate from the request threads. Simulation does not block on I/O, so
 * more threads than cores would only add contention (which is why virtual threads are not used). When the pool and its
 * queue are full, work is rejected with {@link BusyException} instead of piling up.
 */
public final class SimulationExecutor implements AutoCloseable {

    private final ThreadPoolExecutor pool;
    private final int busyRetryAfterSeconds;
    private final boolean ownsPool;

    /**
     * @param threads worker threads; zero or less means the number of available processors
     * @param queueCapacity tasks that may wait for a free worker
     */
    public SimulationExecutor(int threads, int queueCapacity) {
        this(threads, queueCapacity, 5);
    }

    /**
     * Builds and owns its own pool; {@link #close} shuts it down.
     *
     * @param busyRetryAfterSeconds the {@code Retry-After} a caller is given when the queue is full
     */
    public SimulationExecutor(int threads, int queueCapacity, int busyRetryAfterSeconds) {
        this(newPool(threads, queueCapacity), busyRetryAfterSeconds, true);
    }

    /**
     * Runs on a pool managed elsewhere (the Spring container in the service), which also shuts it down. The pool should
     * be bounded so a full queue rejects work rather than growing.
     */
    public SimulationExecutor(ThreadPoolExecutor pool, int busyRetryAfterSeconds) {
        this(pool, busyRetryAfterSeconds, false);
    }

    private SimulationExecutor(ThreadPoolExecutor pool, int busyRetryAfterSeconds, boolean ownsPool) {
        this.pool = pool;
        this.busyRetryAfterSeconds = Math.max(1, busyRetryAfterSeconds);
        this.ownsPool = ownsPool;
    }

    /** The worker count to use: {@code threads}, or the number of available processors when it is zero or less. */
    public static int workerCount(int threads) {
        return threads > 0 ? threads : Runtime.getRuntime().availableProcessors();
    }

    private static ThreadPoolExecutor newPool(int threads, int queueCapacity) {
        int n = workerCount(threads);
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "sim-worker-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        return new ThreadPoolExecutor(n, n, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(Math.max(1, queueCapacity)), factory);
    }

    /** Fire-and-forget submission for jobs; throws {@link BusyException} when the queue is full. */
    public <T> Future<T> submit(Callable<T> task) {
        try {
            return pool.submit(task);
        } catch (RejectedExecutionException e) {
            throw new BusyException(busyRetryAfterSeconds);
        }
    }

    /** Run a task on the pool and wait for its result, rethrowing the task's own exception. */
    public <T> T call(Callable<T> task) {
        Future<T> future = submit(task);
        try {
            return future.get();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for a simulation", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(cause);
        }
    }

    /** Tasks currently waiting for a worker. */
    public int queued() {
        return pool.getQueue().size();
    }

    /** Shuts the pool down if this executor created it; a pool supplied by the caller is left to its owner. */
    @Override
    public void close() {
        if (ownsPool) {
            pool.shutdownNow();
        }
    }
}
