package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.simplejavamail.api.mailer.MailSendCancelledException;
import org.simplejavamail.api.mailer.MailSendTimeoutException;
import org.simplejavamail.api.mailer.config.AsyncQueueConfig;
import org.simplejavamail.api.mailer.config.AsyncQueueOverflowPolicy;
import org.simplejavamail.api.mailer.config.OperationalConfig;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Timeout(15)
class MailSendOperationTest {
    // Mockito's first agent attachment belongs to fixture setup, not the timed concurrency exercise.
    private final OperationalConfig config = mock(OperationalConfig.class);

    @Test
    void queuedCancellationReclaimsCapacityBeforeInvokingItsObserver() throws Exception {
        final MailSendExecutor executor = executor(1, AsyncQueueOverflowPolicy.REJECT);
        final MailSendOperations operations = operations(executor, null);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch entered = new CountDownLatch(1);
        try {
            executor.execute(() -> { entered.countDown(); await(release); });
            assertThat(entered.await(2, SECONDS)).isTrue();
            final AtomicReference<Thread> observerThread = new AtomicReference<>();
            final AtomicBoolean ran = new AtomicBoolean();
            final MailSendOperation<Void> queued = operations.begin(unused -> { }, failure -> {
                assertThat(executor.getQueue()).isEmpty();
                observerThread.set(Thread.currentThread());
            });
            queued.schedule(() -> { ran.set(true); return null; });
            queued.handle().requestCancellation();
            assertThat(failure(queued)).isInstanceOf(MailSendCancelledException.class);
            assertThat(observerThread.get().getName()).startsWith("sjm-control-completion-");
            final MailSendOperation<Void> replacement = operations.begin(unused -> { }, cause -> { });
            replacement.schedule(() -> null);
            release.countDown();
            replacement.handle().getCompletion().get(2, SECONDS);
            assertThat(ran).isFalse();
        } finally {
            release.countDown();
            executor.shutdownNow();
            operations.shutdown().get(3, SECONDS);
        }
    }

    @Test
    void slowTerminalObserverCannotBlockOtherDeadlineSignals() throws Exception {
        final MailSendExecutor executor = executor(2, AsyncQueueOverflowPolicy.REJECT);
        final MailSendOperations operations = operations(executor, Duration.ofMillis(250));
        final CountDownLatch releaseWorker = new CountDownLatch(1);
        final CountDownLatch releaseObserver = new CountDownLatch(1);
        final CountDownLatch observerEntered = new CountDownLatch(1);
        try {
            executor.execute(() -> await(releaseWorker));
            final MailSendOperation<Void> cancelled = operations.begin(unused -> { }, cause -> {
                observerEntered.countDown();
                await(releaseObserver);
            });
            cancelled.schedule(() -> null);
            cancelled.handle().requestCancellation();
            assertThat(observerEntered.await(2, SECONDS)).isTrue();
            final CountDownLatch deadlineSignalled = new CountDownLatch(1);
            final MailSendOperation<Void> expired = operations.begin(unused -> { }, cause -> { });
            expired.control().onStop(deadlineSignalled::countDown);
            expired.schedule(() -> null);
            assertThat(deadlineSignalled.await(2, SECONDS)).isTrue();
            assertThat(expired.handle().getCompletion()).isNotDone();
            assertThat(cancelled.handle().getCompletion()).isNotDone();
            releaseObserver.countDown();
            assertThat(failure(expired)).isInstanceOf(MailSendTimeoutException.class);
        } finally {
            releaseObserver.countDown();
            releaseWorker.countDown();
            executor.shutdownNow();
            operations.shutdown().get(3, SECONDS);
        }
    }

    @Test
    void admissionUsesTheRemainingDeadlineAndReportsOnTheSubmittingThread() throws Exception {
        final MailSendExecutor executor = executor(0, AsyncQueueOverflowPolicy.WAIT_FOR_CAPACITY);
        final MailSendOperations operations = operations(executor, Duration.ofMillis(120));
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch entered = new CountDownLatch(1);
        try {
            executor.execute(() -> { entered.countDown(); await(release); });
            assertThat(entered.await(2, SECONDS)).isTrue();
            final AtomicReference<Thread> notifiedOn = new AtomicReference<>();
            final MailSendOperation<Void> operation = operations.begin(unused -> { }, cause -> notifiedOn.set(Thread.currentThread()));
            operation.schedule(() -> { throw new AssertionError("Expired work must not execute"); });
            assertThat(failure(operation)).isInstanceOf(MailSendTimeoutException.class);
            assertThat(notifiedOn).hasValue(Thread.currentThread());
            assertThat(executor.getQueue()).isEmpty();
        } finally {
            release.countDown();
            executor.shutdownNow();
            operations.shutdown().get(3, SECONDS);
        }
    }

    @Test
    void callerExecutorWrapperNeverRunsAfterCancellationEvenWhenItCannotBeRemoved() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final MailSendOperations operations = operations(executor, null);
        final CountDownLatch release = new CountDownLatch(1);
        try {
            executor.execute(() -> await(release));
            final AtomicBoolean ran = new AtomicBoolean();
            final MailSendOperation<Void> queued = operations.begin(unused -> { }, cause -> { });
            queued.schedule(() -> { ran.set(true); return null; });
            queued.handle().requestCancellation();
            assertThat(failure(queued)).isInstanceOf(MailSendCancelledException.class);
            operations.shutdown().get(2, SECONDS);
            assertThat(executor.isShutdown()).isFalse();
            release.countDown();
            executor.submit(() -> { }).get(2, SECONDS);
            assertThat(ran).isFalse();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void inlineObserverTimeIsNotPartOfTheSendDeadlineAndShutdownWaitsForIt() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final MailSendOperations operations = operations(executor, Duration.ofSeconds(2));
        final CountDownLatch observerEntered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        try {
            final MailSendOperation<Void> operation = operations.begin(unused -> {
                assertThat(operations.isCurrentOperation()).isTrue();
                observerEntered.countDown();
                await(release);
            }, cause -> { throw new AssertionError(cause); });
            operation.schedule(() -> null);
            assertThat(observerEntered.await(5, SECONDS)).isTrue();
            final CompletableFuture<Void> closing = operations.shutdown();
            // Outlast the full send budget; entering the callback need not win a sub-second scheduling race.
            assertThatThrownBy(() -> closing.get(2500, MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(operation.control().isStopRequested()).isFalse();
            release.countDown();
            operation.handle().getCompletion().get(2, SECONDS);
            closing.get(2, SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static Throwable failure(final MailSendOperation<?> operation) throws Exception {
        return operation.handle().getCompletion().handle((value, cause) -> cause).get(3, SECONDS);
    }

    private static MailSendExecutor executor(final int capacity, final AsyncQueueOverflowPolicy policy) {
        return new MailSendExecutor(1, 100, new AsyncQueueConfig(capacity, policy, 5000));
    }

    private MailSendOperations operations(final ExecutorService executor, final Duration timeout) {
        when(config.getExecutorService()).thenReturn(executor);
        when(config.getMailSendTimeout()).thenReturn(timeout);
        return new MailSendOperations(config);
    }

    private static void await(final CountDownLatch latch) {
        try {
            assertThat(latch.await(5, SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
