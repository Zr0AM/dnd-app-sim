package org.omnomnom.dnd.sim.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

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
}
