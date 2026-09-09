package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.mailer.AsyncQueueRejectionReason;
import org.simplejavamail.api.mailer.MailSendRejectedException;
import org.simplejavamail.api.mailer.config.AsyncQueueConfig;
import org.simplejavamail.api.mailer.config.AsyncQueueOverflowPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(15)
class MailSendExecutorTest {
    @Test
    void concurrentProducersCannotOversubscribeTheWaitingQueue() throws Exception {
        final MailSendExecutor executor = new MailSendExecutor(2, 1, new AsyncQueueConfig(5, AsyncQueueOverflowPolicy.REJECT, 1000));
        final CountDownLatch started = new CountDownLatch(2);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger completedQueuedTasks = new AtomicInteger();
        try {
            for (int worker = 0; worker < 2; worker++) {
                executor.execute(() -> {
                    started.countDown();
                    await(release);
                });
            }
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            final List<CompletableFuture<Boolean>> admissions = new ArrayList<>();
            for (int caller = 0; caller < 24; caller++) {
                admissions.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        executor.execute(completedQueuedTasks::incrementAndGet);
                        return true;
                    } catch (MailSendRejectedException rejected) {
                        assertThat(rejected.getReason()).isEqualTo(AsyncQueueRejectionReason.QUEUE_FULL);
                        return false;
                    }
                }));
            }
            int accepted = 0;
            for (CompletableFuture<Boolean> admission : admissions) {
                if (admission.get(5, TimeUnit.SECONDS)) {
                    accepted++;
                }
            }
            assertThat(accepted).isEqualTo(5);
            assertThat(executor.snapshot().getQueuedCount()).isEqualTo(5);
            assertThat(executor.snapshot().getRejectionCounts()).containsEntry(AsyncQueueRejectionReason.QUEUE_FULL, 19L);
        } finally {
            release.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(completedQueuedTasks).hasValue(5);
    }

    @Test
    void workersAreNonDaemonEvenWhenSubmittedFromADaemonThread() throws Exception {
        final MailSendExecutor executor = new MailSendExecutor(1, 1, new AsyncQueueConfig(-1, AsyncQueueOverflowPolicy.REJECT, 1000));
        try {
            final CompletableFuture<Boolean> daemonWorker = new CompletableFuture<>();
            final Thread daemonCaller = new Thread(() -> executor.execute(() -> daemonWorker.complete(Thread.currentThread().isDaemon())));
            daemonCaller.setDaemon(true);
            daemonCaller.start();
            assertThat(daemonWorker.get(5, TimeUnit.SECONDS)).isFalse();
            daemonCaller.join(5000);
            assertThat(daemonCaller.isAlive()).isFalse();
        } finally {
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 4})
    void capacityExcludesActiveWorkers(final int capacity) throws Exception {
        final MailSendExecutor executor = new MailSendExecutor(3, 1,
                new AsyncQueueConfig(capacity, AsyncQueueOverflowPolicy.REJECT, 1000));
        final CountDownLatch started = new CountDownLatch(3);
        final CountDownLatch release = new CountDownLatch(1);
        try {
            for (int worker = 0; worker < 3; worker++) {
                executor.execute(() -> {
                    started.countDown();
                    await(release);
                });
            }
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            for (int queued = 0; queued < capacity; queued++) {
                executor.execute(() -> await(release));
            }
            assertThatThrownBy(() -> executor.execute(() -> { throw new AssertionError("Rejected work ran"); }))
                    .isInstanceOf(MailSendRejectedException.class);
            assertThat(executor.snapshot().getActiveCount()).isEqualTo(3);
            assertThat(executor.snapshot().getQueuedCount()).isEqualTo(capacity);
        } finally {
            release.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void shutdownReleasesAnAdmissionWaiterAndDoesNotDiscardAcceptedWork(final int capacity) throws Exception {
        final MailSendExecutor executor = new MailSendExecutor(1, 1,
                new AsyncQueueConfig(capacity, AsyncQueueOverflowPolicy.WAIT_FOR_CAPACITY, 10000));
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch submitting = new CountDownLatch(1);
        final AtomicReference<Thread> callerThread = new AtomicReference<>();
        try {
            executor.execute(() -> {
                started.countDown();
                await(release);
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            final CompletableFuture<Void> queued = capacity == 0 ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.runAsync(() -> { }, executor);
            final CompletableFuture<Throwable> rejected = CompletableFuture.supplyAsync(() -> {
                callerThread.set(Thread.currentThread());
                submitting.countDown();
                try {
                    executor.execute(() -> { throw new AssertionError("Rejected work ran"); });
                    return null;
                } catch (MailSendRejectedException failure) {
                    return failure;
                }
            });
            assertThat(submitting.await(5, TimeUnit.SECONDS)).isTrue();
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (callerThread.get().getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertThat(callerThread.get().getState()).isEqualTo(Thread.State.TIMED_WAITING);
            executor.shutdown();
            assertThat(((MailSendRejectedException) rejected.get(2, TimeUnit.SECONDS)).getReason())
                    .isEqualTo(AsyncQueueRejectionReason.EXECUTOR_SHUT_DOWN);
            release.countDown();
            queued.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void waitingAdmissionRestartsWorkersThatExpiredAfterRejection(final int capacity) throws Exception {
        final MailSendExecutor executor = new MailSendExecutor(1, 1,
                new AsyncQueueConfig(capacity, AsyncQueueOverflowPolicy.WAIT_FOR_CAPACITY, 1000));
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger executions = new AtomicInteger();
        try {
            executor.execute(() -> {
                started.countDown();
                await(release);
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            for (int queued = 0; queued < capacity; queued++) {
                executor.execute(() -> { });
            }
            final AtomicInteger admissionWaits = new AtomicInteger();
            final RejectedExecutionHandler admissionHandler = executor.getRejectedExecutionHandler();
            executor.setRejectedExecutionHandler((operation, pool) -> {
                admissionWaits.incrementAndGet();
                // Pause this caller after rejection until the busy worker has finished and expired.
                release.countDown();
                awaitWorkerExpiry(executor);
                assertThat(pool.isShutdown()).isFalse();
                admissionHandler.rejectedExecution(operation, pool);
            });

            final CompletableFuture<Thread> executedOn = new CompletableFuture<>();
            executor.execute(() -> {
                executions.incrementAndGet();
                executedOn.complete(Thread.currentThread());
            });

            assertThat(executedOn.get(5, TimeUnit.SECONDS)).isNotSameAs(Thread.currentThread());
            assertThat(admissionWaits).hasValue(1);
            assertThat(executor.snapshot().getRejectionCounts().values()).containsOnly(0L);
        } finally {
            release.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(executions).hasValue(1);
    }

    @Test
    void anIdleExecutorRestartsAfterItsLastWorkerExpires() throws Exception {
        final MailSendExecutor executor = new MailSendExecutor(1, 1,
                new AsyncQueueConfig(1, AsyncQueueOverflowPolicy.WAIT_FOR_CAPACITY, 1000));
        try {
            for (int attempt = 0; attempt < 20; attempt++) {
                CompletableFuture.runAsync(() -> { }, executor).get(2, TimeUnit.SECONDS);
                awaitWorkerExpiry(executor);
            }
        } finally {
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void awaitWorkerExpiry(final MailSendExecutor executor) {
        try {
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (executor.getPoolSize() != 0 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertThat(executor.getPoolSize()).isZero();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static void await(final CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
