package org.simplejavamail.internal.mailprovider.angus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(30)
class AngusWriteTimeoutSchedulerTest {
    @Test
    void theLastLeaseStopsTheWorkerAndLaterConnectionsCreateAFreshGeneration() throws Exception {
        final AngusWriteTimeoutScheduler scheduler = new AngusWriteTimeoutScheduler();
        assertThat(current(scheduler)).isNull();
        assertThatThrownBy(() -> scheduler.schedule(() -> { }, 0, SECONDS)).isInstanceOf(RejectedExecutionException.class);
        final AngusWriteTimeoutScheduler.Lease first = scheduler.retainConnection();
        final AngusWriteTimeoutScheduler.Lease second = scheduler.retainConnection();
        final ScheduledThreadPoolExecutor generation = current(scheduler);
        assertThat(generation.getPoolSize()).isZero();
        assertThat(scheduler.submit(() -> Thread.currentThread().isDaemon()).get(5, SECONDS)).isTrue();
        assertThat(generation.getPoolSize()).isEqualTo(1);
        first.close();
        first.close();
        assertThat(generation.isShutdown()).isFalse();
        assertThat(scheduler.submit(() -> "another connection still owns the scheduler").get(5, SECONDS)).isNotNull();
        second.close();
        assertThat(generation.isShutdown()).isTrue();
        assertThat(generation.awaitTermination(5, SECONDS)).isTrue();
        assertThat(current(scheduler)).isNull();
        try (AngusWriteTimeoutScheduler.Lease next = scheduler.retainConnection()) {
            assertThat(current(scheduler)).isNotSameAs(generation);
            first.close();
            second.close();
            assertThat(scheduler.submit(() -> "fresh generation").get(5, SECONDS)).isEqualTo("fresh generation");
        }
    }

    @Test
    void successfulWritesDoNotRetainCancelledTasksAndCallbacksDoNotOwnTheControllerMonitor() throws Exception {
        final AngusWriteTimeoutScheduler scheduler = new AngusWriteTimeoutScheduler();
        try (AngusWriteTimeoutScheduler.Lease ignored = scheduler.retainConnection()) {
            for (int index = 0; index < 1000; index++) {
                final ScheduledFuture<?> timeout = scheduler.schedule(() -> { }, 1, MINUTES);
                timeout.cancel(false);
            }
            assertThat(current(scheduler).getQueue()).isEmpty();
            assertThat(scheduler.submit(() -> {
                synchronized (scheduler) {
                    return "callback acquired the controller monitor";
                }
            }).get(5, SECONDS)).isEqualTo("callback acquired the controller monitor");
        }
    }

    @Test
    void aLastReleaseRacingWithANewConnectionNeverStopsTheNewConnectionsScheduler() throws Exception {
        final AngusWriteTimeoutScheduler scheduler = new AngusWriteTimeoutScheduler();
        final ExecutorService racers = Executors.newFixedThreadPool(2);
        try {
            for (int index = 0; index < 200; index++) {
                final AngusWriteTimeoutScheduler.Lease previous = scheduler.retainConnection();
                final ScheduledThreadPoolExecutor original = current(scheduler);
                final CountDownLatch start = new CountDownLatch(1);
                final Future<?> release = racers.submit(() -> {
                    await(start);
                    previous.close();
                });
                final Future<AngusWriteTimeoutScheduler.Lease> acquire = racers.submit(() -> {
                    await(start);
                    return scheduler.retainConnection();
                });
                start.countDown();
                release.get(5, SECONDS);
                try (AngusWriteTimeoutScheduler.Lease next = acquire.get(5, SECONDS)) {
                    previous.close();
                    assertThat(scheduler.submit(() -> "still active").get(5, SECONDS)).isEqualTo("still active");
                }
                assertThat(original.isShutdown()).isTrue();
                assertThat(original.awaitTermination(5, SECONDS)).isTrue();
            }
            assertThat(current(scheduler)).isNull();
        } finally {
            racers.shutdownNow();
            assertThat(racers.awaitTermination(5, SECONDS)).isTrue();
        }
    }

    @Test
    void configuringAndIsolatingSessionsDoesNotReplaceExternalOrDisabledSchedulers() {
        final Properties properties = new Properties();
        properties.setProperty("mail.smtp.writetimeout", "60000");
        AngusWriteTimeoutScheduler.configure(properties, "mail.smtp");
        final AngusWriteTimeoutScheduler configured = AngusWriteTimeoutScheduler.find(properties, "smtp");
        assertThat(configured).isNotNull();
        AngusWriteTimeoutScheduler.configure(properties, "mail.smtp");
        assertThat(AngusWriteTimeoutScheduler.find(properties, "smtp")).isSameAs(configured);
        final Properties probe = (Properties) properties.clone();
        AngusWriteTimeoutScheduler.isolateProbe(probe, "mail.smtp");
        assertThat(AngusWriteTimeoutScheduler.find(probe, "smtp")).isNotSameAs(configured);
        final ScheduledThreadPoolExecutor external = new ScheduledThreadPoolExecutor(1);
        try {
            properties.put("mail.smtp.executor.writetimeout", external);
            AngusWriteTimeoutScheduler.configure(properties, "mail.smtp");
            AngusWriteTimeoutScheduler.isolateProbe(properties, "mail.smtp");
            assertThat(properties.get("mail.smtp.executor.writetimeout")).isSameAs(external);
            assertThat(AngusWriteTimeoutScheduler.find(properties, "smtp")).isNull();
        } finally {
            external.shutdownNow();
        }
        for (int timeout : new int[]{0, -1}) {
            final Properties disabled = new Properties();
            disabled.setProperty("mail.smtp.writetimeout", Integer.toString(timeout));
            AngusWriteTimeoutScheduler.configure(disabled, "mail.smtp");
            assertThat(disabled).doesNotContainKey("mail.smtp.executor.writetimeout");
        }
    }

    @Test
    void explicitShutdownCannotRestartTheController() throws Exception {
        final AngusWriteTimeoutScheduler scheduler = new AngusWriteTimeoutScheduler();
        try (AngusWriteTimeoutScheduler.Lease ignored = scheduler.retainConnection()) {
            scheduler.submit(() -> "start worker").get(5, SECONDS);
            scheduler.shutdown();
            assertThat(scheduler.awaitTermination(5, SECONDS)).isTrue();
            assertThat(scheduler.isShutdown()).isTrue();
            assertThat(scheduler.isTerminated()).isTrue();
            assertThatThrownBy(scheduler::retainConnection).isInstanceOf(RejectedExecutionException.class);
        }
    }

    private static ScheduledThreadPoolExecutor current(final AngusWriteTimeoutScheduler scheduler) throws Exception {
        final Field current = AngusWriteTimeoutScheduler.class.getDeclaredField("current");
        current.setAccessible(true);
        return (ScheduledThreadPoolExecutor) current.get(scheduler);
    }

    private static void await(final CountDownLatch latch) {
        try {
            assertThat(latch.await(5, SECONDS)).isTrue();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
