package org.simplejavamail.internal.util.concurrent;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.MailSendCancelledException;
import org.simplejavamail.api.mailer.MailSendTimeoutException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailSendControlTest {
    private static final class OwnedFailure extends org.simplejavamail.MailException {
        private OwnedFailure(final String message, final Throwable cause) {
            super(org.simplejavamail.api.mailer.MailFailureKind.CAPACITY, message, cause, null);
        }
    }

    @Test
    void anApplicationRuntimeFailureIsNotReclassifiedByARacingCancellation() {
        final ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor();
        try (MailSendControl control = new MailSendControl(null, watcher)) {
            final RuntimeException callbackFailure = new IllegalStateException("application defect");
            control.requestCancellation();
            assertThat(control.translateFailure(callbackFailure)).isSameAs(callbackFailure);
        } finally {
            watcher.shutdownNow();
        }
    }

    @Test
    void anExpiredBoundaryDoesNotDependOnTheWatcherHavingRun() throws Exception {
        final ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor();
        final CountDownLatch release = new CountDownLatch(1);
        watcher.execute(() -> await(release));
        try (MailSendControl control = new MailSendControl(Duration.ofMillis(20), watcher)) {
            new CountDownLatch(1).await(100, MILLISECONDS);
            assertThat(control.isStopRequested()).isFalse();
            final RuntimeException original = new OwnedFailure("pool wait expired", null);
            assertThat(control.translateFailure(original)).isInstanceOf(MailSendTimeoutException.class).hasCause(original);
        } finally {
            release.countDown();
            watcher.shutdownNow();
        }
    }

    @Test
    void aLaterCleanupCancellationCannotReplaceAnEarlierPrimaryFailure() {
        final ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor();
        try (MailSendControl control = new MailSendControl(null, watcher)) {
            final RuntimeException original = new OwnedFailure("real transport failure", null);
            assertThat(control.translateFailure(original)).isSameAs(original);
            control.requestCancellation();
            final RuntimeException wrapper = new OwnedFailure("caller-facing context", original);
            assertThat(control.translateFailure(wrapper)).isSameAs(wrapper);
        } finally {
            watcher.shutdownNow();
        }
    }

    @Test
    void deadlineSignalsAndRetiredRegistrationsNeverFire() throws Exception {
        final ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor();
        try (MailSendControl control = new MailSendControl(Duration.ofMillis(100), watcher)) {
            final CountDownLatch stopped = new CountDownLatch(1);
            final AtomicInteger retired = new AtomicInteger();
            control.onStop(retired::incrementAndGet).close();
            control.onStop(stopped::countDown);
            assertThat(stopped.await(2, SECONDS)).isTrue();
            assertThatThrownBy(control::checkStopped).isInstanceOf(MailSendTimeoutException.class);
            assertThat(retired).hasValue(0);
        } finally {
            watcher.shutdownNow();
        }
    }

    @Test
    void firstStopReasonWinsAndLateResourceRegistrationSeesIt() {
        final ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor();
        try (MailSendControl control = new MailSendControl(null, watcher)) {
            control.requestCancellation();
            final AtomicInteger callbacks = new AtomicInteger();
            control.onStop(callbacks::incrementAndGet);
            control.requestCancellation();
            assertThat(callbacks).hasValue(1);
            assertThatThrownBy(control::checkStopped).isInstanceOf(MailSendCancelledException.class);
        } finally {
            watcher.shutdownNow();
        }
    }

    @Test
    void nestedObserverPausesPreserveTheRemainingBudget() throws Exception {
        final ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor();
        try (MailSendControl control = new MailSendControl(Duration.ofMillis(150), watcher)) {
            final CountDownLatch stopped = new CountDownLatch(1);
            control.onStop(stopped::countDown);
            try (MailSendControl.DeadlinePause outer = control.pauseDeadline()) {
                try (MailSendControl.DeadlinePause inner = control.pauseDeadline()) {
                    assertThat(stopped.await(250, MILLISECONDS)).isFalse();
                }
                assertThat(stopped.await(250, MILLISECONDS)).isFalse();
            }
            assertThat(stopped.await(2, SECONDS)).isTrue();
        } finally {
            watcher.shutdownNow();
        }
    }

    @Test
    void cancelledAlarmRunningDuringPauseCannotCorruptTheResumedDeadline() {
        final ScheduledExecutorService watcher = mock(ScheduledExecutorService.class);
        final ScheduledFuture<?> scheduled = mock(ScheduledFuture.class);
        final List<Runnable> alarms = new ArrayList<>();
        when(watcher.schedule(any(Runnable.class), anyLong(), eq(NANOSECONDS))).thenAnswer(invocation -> {
            alarms.add(invocation.getArgument(0));
            return scheduled;
        });
        final Duration timeout = Duration.ofSeconds(10);
        final AtomicInteger stops = new AtomicInteger();
        try (MailSendControl control = new MailSendControl(timeout, watcher)) {
            control.onStop(stops::incrementAndGet);
            assertThat(alarms).hasSize(1);
            try (MailSendControl.DeadlinePause pause = control.pauseDeadline()) {
                verify(scheduled).cancel(false);
                alarms.get(0).run(); // A scheduler may already have dispatched a cancelled task.
                assertThat(stops).hasValue(0);
                assertThat(control.isStopRequested()).isFalse();
                assertThat(alarms).hasSize(1);
            }
            assertThat(alarms).hasSize(2);
            assertThat(control.remainingNanos()).isBetween(1L, timeout.toNanos());
            assertThat(stops).hasValue(0);
            assertThat(control.isStopRequested()).isFalse();
        }
    }

    @Test
    void closingRegistrationFencesAnAlreadyRunningAction() throws Exception {
        final ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor();
        final CountDownLatch release = new CountDownLatch(1);
        try (MailSendControl control = new MailSendControl(null, watcher)) {
            final CountDownLatch entered = new CountDownLatch(1);
            final MailSendControl.Registration registration = control.onStop(() -> {
                entered.countDown();
                await(release);
            });
            watcher.execute(control::requestCancellation);
            assertThat(entered.await(2, SECONDS)).isTrue();
            final FutureTask<Void> closing = new FutureTask<>(registration::close, null);
            final Thread closingThread = new Thread(closing, "registration-closing-test");
            closingThread.start();
            try {
                awaitRegistrationCloseWaiting(closingThread);
                assertThat(closing).isNotDone();
                release.countDown();
                closing.get(2, SECONDS);
            } finally {
                release.countDown();
                closingThread.join(SECONDS.toMillis(2));
                assertThat(closingThread.isAlive()).isFalse();
            }
        } finally {
            release.countDown();
            watcher.shutdownNow();
            assertThat(watcher.awaitTermination(2, SECONDS)).isTrue();
        }
    }

    @Test
    void retiringAStopActionAfterCancellationTakesItsSnapshotPreventsInvocation() throws Exception {
        final ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor();
        final CountDownLatch firstEntered = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final AtomicInteger retiredCalls = new AtomicInteger();
        try (MailSendControl control = new MailSendControl(null, watcher)) {
            control.onStop(() -> { firstEntered.countDown(); await(releaseFirst); });
            final MailSendControl.Registration retired = control.onStop(retiredCalls::incrementAndGet);
            final FutureTask<Void> cancelling = new FutureTask<>(() -> { control.requestCancellation(); return null; });
            final Thread thread = new Thread(cancelling, "stop-snapshot-test");
            thread.start();
            try {
                assertThat(firstEntered.await(2, SECONDS)).isTrue();
                retired.close();
                releaseFirst.countDown();
                cancelling.get(2, SECONDS);
                assertThat(retiredCalls).hasValue(0);
            } finally {
                releaseFirst.countDown();
                thread.join(SECONDS.toMillis(2));
                assertThat(thread.isAlive()).isFalse();
            }
        } finally {
            releaseFirst.countDown();
            watcher.shutdownNow();
            assertThat(watcher.awaitTermination(2, SECONDS)).isTrue();
        }
    }

    @Test
    void timeoutMustBePositiveAndRepresentable() {
        assertThatThrownBy(() -> MailSendControl.positiveTimeoutNanos(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MailSendControl.positiveTimeoutNanos(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MailSendControl.positiveTimeoutNanos(Duration.ofSeconds(Long.MAX_VALUE))).isInstanceOf(IllegalArgumentException.class);
        assertThat(MailSendControl.positiveTimeoutNanos(Duration.ofNanos(1))).isEqualTo(1);
    }

    private static void awaitRegistrationCloseWaiting(final Thread closingThread) throws InterruptedException {
        // Starting the thread alone does not prove close() reached its wait for the running action.
        final long deadline = System.nanoTime() + SECONDS.toNanos(2);
        while (closingThread.isAlive() && System.nanoTime() < deadline) {
            if (closingThread.getState() == Thread.State.WAITING) {
                for (StackTraceElement frame : closingThread.getStackTrace()) {
                    if (frame.getClassName().equals(MailSendControl.Registration.class.getName()) && frame.getMethodName().equals("close")) {
                        return;
                    }
                }
            }
            Thread.sleep(1);
        }
        fail("Registration.close() did not wait for the running stop action; thread state: " + closingThread.getState());
    }

    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(3, SECONDS)) {
                throw new AssertionError("Control action was not released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
