package org.omnomnom.dnd.sim.application;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A bounded pool for CPU-bound simulation work, separate from the request threads. Simulation does not block on I/O, so
 * more threads than cores would only add contention (which is why virtual threads are not used). When the pool and its
 * queue are full, work is rejected with {@link BusyException} instead of piling up.
 */
public final class SimulationExecutor implements AutoCloseable {

    private final ThreadPoolExecutor pool;

    /**
     * @param threads worker threads; zero or less means the number of available processors
     * @param queueCapacity tasks that may wait for a free worker
     */
    public SimulationExecutor(int threads, int queueCapacity) {
        int n = threads > 0 ? threads : Runtime.getRuntime().availableProcessors();
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "sim-worker-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.pool = new ThreadPoolExecutor(n, n, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(Math.max(1, queueCapacity)), factory);
    }

    /** Fire-and-forget submission for jobs; throws {@link BusyException} when the queue is full. */
    public <T> Future<T> submit(Callable<T> task) {
        try {
            return pool.submit(task);
        } catch (RejectedExecutionException e) {
            throw new BusyException(5);
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

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
