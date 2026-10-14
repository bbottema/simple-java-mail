package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.simplejavamail.api.mailer.MailSendCancelledException;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.MailSendRetryPolicy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;

@Timeout(15)
class MailSendRetrySchedulingTest {
    @Test
    void rejectedResumptionRetainsTheRefusalAndRetiresTheSameOperation() throws Exception {
        final ExecutorService executor = mock(ExecutorService.class);
        final AtomicInteger admissions = new AtomicInteger();
        doAnswer(invocation -> {
            if (admissions.incrementAndGet() > 1) { throw new RejectedExecutionException("fixture resumption rejected"); }
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(executor).execute(any());
        final MailSendOperations owner = new MailSendOperations(configuration(executor));
        final RuntimeException refusal = new MailerException("genuine refused attempt");
        final AtomicInteger invocations = new AtomicInteger();
        final AtomicInteger outcomes = new AtomicInteger();
        final AtomicBoolean closed = new AtomicBoolean();
        final MailSendOperation<String> operation = owner.begin(value -> outcomes.incrementAndGet(), failure -> outcomes.incrementAndGet());
        try {
            operation.schedule(() -> operation.executeRetrying(new MailSendOperation.RetryingWork<>() {
                @Override public MailSendOperation.RetryResult<String> execute() {
                    invocations.incrementAndGet();
                    return MailSendOperation.RetryResult.retry(Duration.ofNanos(1));
                }
                @Override public RuntimeException lastFailure() { return refusal; }
                @Override public void waiting() { }
                @Override public void resumed() { }
                @Override public void close() { closed.set(true); }
            }));
            final Throwable terminal = operation.handle().getCompletion().handle((value, failure) -> failure).get(5, SECONDS);
            assertThat(terminal).isSameAs(refusal);
            assertThat(terminal.getSuppressed()).anySatisfy(reason -> assertThat(reason).hasMessageContaining("could not resume"));
            assertThat(invocations).hasValue(1);
            assertThat(outcomes).hasValue(1);
            assertThat(closed).isTrue();
        } finally {
            operation.handle().requestCancellation();
            owner.shutdown().get(5, SECONDS);
        }
    }

    @Test
    void anInlineResumptionCannotBeCompletedByThePreviousExecutionTurn() throws Exception {
        final ScheduledThreadPoolExecutor timers = new ScheduledThreadPoolExecutor(1);
        final ExecutorService executor = mock(ExecutorService.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(executor).execute(any());
        final MailSendOperations owner = new MailSendOperations(configuration(executor), timers);
        try {
            for (int index = 0; index < 100; index++) {
                final AtomicInteger turns = new AtomicInteger();
                final MailSendOperation<String> operation = owner.begin(value -> assertThat(value).isEqualTo("accepted"),
                        failure -> { throw new AssertionError(failure); });
                operation.schedule(() -> operation.executeRetrying(new MailSendOperation.RetryingWork<>() {
                    @Override public MailSendOperation.RetryResult<String> execute() {
                        return turns.incrementAndGet() == 1
                                ? MailSendOperation.RetryResult.retry(Duration.ofNanos(1))
                                : MailSendOperation.RetryResult.completed("accepted");
                    }
                    @Override public RuntimeException lastFailure() { return new MailerException("temporary refusal"); }
                    @Override public void waiting() { }
                    @Override public void resumed() { }
                    @Override public void close() { }
                }));
                assertThat(operation.handle().getCompletion().get(5, SECONDS)).isEqualTo("accepted");
                assertThat(turns).hasValue(2);
            }
            owner.shutdown().get(5, SECONDS);
        } finally { timers.shutdownNow(); }
    }
    @Test
    void cancelledTimerCannotExecuteAgainAndReleasesWaitingCapacity() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final MailSendOperations owner = new MailSendOperations(configuration(executor));
        final AtomicInteger turns = new AtomicInteger();
        final CountDownLatch parked = new CountDownLatch(1);
        final AtomicBoolean closed = new AtomicBoolean();
        final MailSendOperation<String> operation = owner.begin(value -> { }, failure -> { });
        final RuntimeException refusal = new MailerException("retained refusal");
        final MailSendOperation.RetryingWork<String> work = new MailSendOperation.RetryingWork<>() {
            @Override public MailSendOperation.RetryResult<String> execute() {
                turns.incrementAndGet();
                return MailSendOperation.RetryResult.retry(Duration.ofHours(1));
            }
            @Override public RuntimeException lastFailure() { return refusal; }
            @Override public void waiting() { parked.countDown(); }
            @Override public void resumed() { }
            @Override public void close() { closed.set(true); }
        };
        try {
            operation.schedule(() -> operation.executeRetrying(work));
            assertThat(parked.await(5, SECONDS)).isTrue();
            executor.submit(() -> { }).get(5, SECONDS); // The first execution has returned, not merely entered its waiting callback.
            operation.handle().requestCancellation();
            final Throwable failure = operation.handle().getCompletion().handle((value, cause) -> cause).get(5, SECONDS);
            assertThat(failure).isInstanceOf(MailSendCancelledException.class).hasCause(refusal);
            assertThat(closed).isTrue();
            assertThat(turns).hasValue(1);
            owner.shutdown().get(5, SECONDS);
            assertThat(executor.isShutdown()).isFalse();
        } finally { executor.shutdownNow(); }
    }

    @Test
    void queuedResumptionKeepsItsPermitUntilExecutionAndCancellationRemovesIt() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final MailSendOperations owner = new MailSendOperations(configuration(executor));
        final CountDownLatch releaseWorker = new CountDownLatch(1);
        final CountDownLatch resumedQueue = new CountDownLatch(1);
        final AtomicInteger turns = new AtomicInteger();
        final MailSendOperation<String> operation = owner.begin(value -> { }, failure -> { });
        try {
            operation.schedule(() -> operation.executeRetrying(new MailSendOperation.RetryingWork<>() {
                @Override public MailSendOperation.RetryResult<String> execute() {
                    turns.incrementAndGet();
                    executor.execute(() -> {
                        resumedQueue.countDown();
                        await(releaseWorker);
                    });
                    return MailSendOperation.RetryResult.retry(Duration.ofNanos(1));
                }
                @Override public RuntimeException lastFailure() { return new MailerException("refused"); }
                @Override public void waiting() { }
                @Override public void resumed() { }
                @Override public void close() { }
            }));
            assertThat(resumedQueue.await(5, SECONDS)).isTrue();
            operation.handle().requestCancellation();
            assertThat(operation.handle().getCompletion().handle((value, failure) -> failure).get(5, SECONDS)).isInstanceOf(MailSendCancelledException.class);
            releaseWorker.countDown();
            executor.submit(() -> { }).get(5, SECONDS);
            assertThat(turns).hasValue(1);
            owner.shutdown().get(5, SECONDS);
        } finally {
            releaseWorker.countDown();
            executor.shutdownNow();
        }
    }

    private static OperationalConfig configuration(final ExecutorService executor) {
        final OperationalConfig config = mock(OperationalConfig.class);
        when(config.getExecutorService()).thenReturn(executor);
        when(config.getMailSendTimeout()).thenReturn(null);
        when(config.getMailSendRetryPolicy()).thenReturn(MailSendRetryPolicy.builder().withWaitingCapacity(1).build());
        return config;
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
