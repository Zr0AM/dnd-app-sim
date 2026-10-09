package org.omnomnom.dnd.sim.application.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.omnomnom.dnd.sim.application.error.BusyException;
import org.omnomnom.dnd.sim.application.error.UnprocessableException;

class SimulationExecutorTest {

    @Test
    void runsATaskOnAWorkerThreadAndReturnsItsResult() {
        try (SimulationExecutor exec = new SimulationExecutor(1, 1)) {
            String thread = exec.call(() -> Thread.currentThread().getName());
            assertThat(thread).startsWith("sim-worker-");
            assertThat(exec.call(() -> 6 * 7)).isEqualTo(42);
        }
    }

    @Test
    void rethrowsTheTasksOwnException() {
        try (SimulationExecutor exec = new SimulationExecutor(1, 1)) {
            assertThatThrownBy(() -> exec.call(() -> {
                throw new UnprocessableException("x", "boom");
            })).isInstanceOf(UnprocessableException.class).hasMessage("boom");
            assertThatThrownBy(() -> exec.call(() -> {
                throw new java.io.IOException("checked");
            })).isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(java.io.IOException.class);
        }
    }

    @Test
    void rejectsWorkWhenTheQueueIsFull() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (SimulationExecutor exec = new SimulationExecutor(1, 1)) {
            Future<Integer> running = exec.submit(() -> {
                started.countDown();
                release.await();
                return 1;
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Integer> queued = exec.submit(() -> 2); // fills the single queue slot
            assertThat(exec.queued()).isEqualTo(1);
            assertThatThrownBy(() -> exec.submit(() -> 3)).isInstanceOf(BusyException.class)
                    .satisfies(e -> assertThat(((BusyException) e).retryAfterSeconds()).isPositive());
            release.countDown();
            assertThat(running.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(queued.get(5, TimeUnit.SECONDS)).isEqualTo(2);
        }
    }

    @Test
    void zeroThreadsMeansOnePerProcessor() throws Exception {
        try (SimulationExecutor exec = new SimulationExecutor(0, 4)) {
            assertThat(exec.call(() -> "ok")).isEqualTo("ok");
        }
    }

    @Test
    void workersAreDaemonThreads() {
        try (SimulationExecutor exec = new SimulationExecutor(1, 1)) {
            assertThat(exec.call(() -> Thread.currentThread().isDaemon())).isTrue();
        }
    }

    @Test
    void interruptingTheCallerInterruptsTheRunningTask() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch taskSawInterrupt = new CountDownLatch(1);
        try (SimulationExecutor exec = new SimulationExecutor(1, 1)) {
            Throwable[] failure = new Throwable[1];
            Thread caller = new Thread(() -> {
                try {
                    exec.call(() -> {
                        started.countDown();
                        try {
                            new CountDownLatch(1).await(); // blocks until interrupted
                        } catch (InterruptedException e) {
                            taskSawInterrupt.countDown();
                        }
                        return null;
                    });
                } catch (Throwable t) {
                    failure[0] = t;
                }
            });
            caller.start();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            caller.join(5000);
            assertThat(taskSawInterrupt.await(5, TimeUnit.SECONDS)).as("the worker is interrupted, not left running").isTrue();
            assertThat(failure[0]).isInstanceOf(IllegalStateException.class).hasMessageContaining("interrupted");
        }
    }

    @Test
    void theBusyRetryAfterIsConfigurable() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (SimulationExecutor exec = new SimulationExecutor(1, 1, 17)) {
            exec.submit(() -> {
                release.await();
                return 0;
            });
            exec.submit(() -> 0);
            long deadline = System.currentTimeMillis() + 5000;
            BusyException busy = null;
            while (busy == null && System.currentTimeMillis() < deadline) {
                try {
                    exec.submit(() -> 0);
                    Thread.sleep(5);
                } catch (BusyException e) {
                    busy = e;
                }
            }
            assertThat(busy).isNotNull();
            assertThat(busy.retryAfterSeconds()).isEqualTo(17);
            release.countDown();
        }
    }
}
