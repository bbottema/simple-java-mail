package org.simplejavamail.internal.util.concurrent;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailSendCancelledException;
import org.simplejavamail.api.mailer.MailSendTimeoutException;
import org.simplejavamail.api.mailer.MailSubmissionException;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

/**
 * Internal stop signal shared across queue, pool and transport boundaries. It never invokes application observers or completes futures.
 * Every resource registration is fenced before reuse; callback execution stays outside the signal's monitor.
 */
public final class MailSendControl implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(MailSendControl.class);
    private enum StopReason { CANCELLED, TIMED_OUT }

    private final ScheduledExecutorService watcher;
    private final boolean timed;
    private final List<Registration> registrations = new ArrayList<>();
    @Nullable private StopReason stopReason;
    @Nullable private StopReason stopAtFailure;
    private boolean failureRecorded;
    @Nullable private ScheduledFuture<?> alarm;
    private long remainingNanos;
    private long resumedAt;
    private long alarmGeneration;
    private int deadlinePauses;
    private boolean finished;

    public MailSendControl(@Nullable final Duration timeout, @NotNull final ScheduledExecutorService watcher) {
        this.watcher = requireNonNull(watcher, "watcher");
        timed = timeout != null;
        remainingNanos = timeout == null ? Long.MAX_VALUE : positiveTimeoutNanos(timeout);
        resumedAt = System.nanoTime();
        synchronized (this) {
            scheduleAlarm();
        }
    }

    /** Validates both Java configuration and parsed property values without overflowing deadline arithmetic. */
    public static long positiveTimeoutNanos(@NotNull final Duration timeout) {
        final long nanos;
        try {
            nanos = requireNonNull(timeout, "timeout").toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Mail-send timeout is too large", overflow);
        }
        if (nanos <= 0) {
            throw new IllegalArgumentException("Mail-send timeout must be positive");
        }
        return nanos;
    }

    public void requestCancellation() {
        requestStop(StopReason.CANCELLED);
    }

    /** Remaining budget for acquisition/admission. Disabled or paused deadlines impose no additional wait limit. */
    public synchronized long remainingNanos() {
        if (!timed || deadlinePauses > 0) {
            return Long.MAX_VALUE;
        }
        return Math.max(0, remainingNanos - (System.nanoTime() - resumedAt));
    }

    public synchronized boolean isStopRequested() {
        return stopReason != null;
    }

    public boolean isDeadlineEnabled() {
        return timed;
    }

    /** Checks elapsed time as well as the signal, so a busy scheduler cannot admit work after its budget expired. */
    public void checkStopped() {
        if (remainingNanos() == 0) {
            requestStop(StopReason.TIMED_OUT);
        }
        synchronized (this) {
            if (stopReason != null) {
                throw stoppedFailure(null);
            }
        }
    }

    /** Register only short control actions, such as waking a claimant or closing a captured socket. */
    @NotNull
    public Registration onStop(@NotNull final Runnable action) {
        final Registration registration = new Registration(requireNonNull(action, "action"));
        final boolean stopped;
        synchronized (this) {
            if (finished) {
                registration.closed = true;
                return registration;
            }
            registrations.add(registration);
            stopped = stopReason != null;
        }
        if (stopped) {
            registration.fire();
        }
        return registration;
    }

    /** Map a boundary failure once, before cleanup can race a later stop request. */
    @NotNull
    public RuntimeException translateFailure(@NotNull final RuntimeException failure) {
        if (remainingNanos() == 0) {
            requestStop(StopReason.TIMED_OUT);
        }
        synchronized (this) {
            if (!failureRecorded) {
                stopAtFailure = stopReason;
                failureRecorded = true;
            }
            return stopAtFailure == null || failure instanceof MailSendCancelledException || failure instanceof MailSendTimeoutException
                    ? failure : stoppedFailure(stopAtFailure, failure);
        }
    }

    @NotNull
    public synchronized RuntimeException stoppedFailure(@Nullable final Throwable cause) {
        if (stopReason == null) {
            throw new IllegalStateException("No mail-send stop was requested");
        }
        return stoppedFailure(stopReason, cause);
    }

    private RuntimeException stoppedFailure(final StopReason reason, @Nullable final Throwable cause) {
        final MailSubmissionReceipt receipt = cause instanceof MailSubmissionException
                ? ((MailSubmissionException) cause).getSubmissionReceipt() : null;
        return reason == StopReason.TIMED_OUT
                ? new MailSendTimeoutException(cause, receipt) : new MailSendCancelledException(cause, receipt);
    }

    /** Observer handoff/execution is outside the send budget, including between emails of a simple batch. */
    @NotNull
    public DeadlinePause pauseDeadline() {
        final boolean expired;
        synchronized (this) {
            expired = timed && !finished && deadlinePauses == 0 && remainingNanos() == 0;
            if (deadlinePauses == 0 && timed) {
                remainingNanos = remainingNanos();
                cancelAlarm();
            }
            deadlinePauses++;
        }
        if (expired) {
            requestStop(StopReason.TIMED_OUT);
        }
        return new DeadlinePause();
    }

    private void resumeDeadline() {
        synchronized (this) {
            if (--deadlinePauses == 0) {
                resumedAt = System.nanoTime();
                scheduleAlarm();
            }
        }
    }

    private void scheduleAlarm() {
        if (timed && !finished && stopReason == null && deadlinePauses == 0) {
            final long generation = ++alarmGeneration;
            alarm = watcher.schedule(() -> expire(generation), remainingNanos, TimeUnit.NANOSECONDS);
        }
    }

    private void expire(final long generation) {
        final List<Registration> pending;
        synchronized (this) {
            if (generation != alarmGeneration || finished || deadlinePauses > 0 || stopReason != null) {
                return;
            }
            if (remainingNanos() > 0) {
                remainingNanos = remainingNanos();
                resumedAt = System.nanoTime();
                scheduleAlarm();
                return;
            }
            stopReason = StopReason.TIMED_OUT;
            pending = new ArrayList<>(registrations);
        }
        pending.forEach(Registration::fire);
    }

    private void requestStop(final StopReason reason) {
        final List<Registration> pending = new ArrayList<>();
        synchronized (this) {
            if (finished || stopReason != null) {
                return;
            }
            stopReason = reason;
            cancelAlarm();
            pending.addAll(registrations);
        }
        pending.forEach(Registration::fire);
    }

    private void cancelAlarm() {
        alarmGeneration++;
        if (alarm != null) {
            alarm.cancel(false);
            alarm = null;
        }
    }

    /** Retire deadline and resource callbacks before observer notification or operation completion. */
    @Override
    public void close() {
        final List<Registration> pending;
        synchronized (this) {
            if (finished) {
                return;
            }
            finished = true;
            cancelAlarm();
            pending = new ArrayList<>(registrations);
        }
        pending.forEach(Registration::close);
    }

    /** Closing waits for any already-running action, preventing a stale action from escaping into the next lease generation. */
    public final class Registration implements AutoCloseable {
        private final Runnable action;
        private boolean closed;
        @Nullable private Thread executingThread;

        private Registration(final Runnable action) {
            this.action = action;
        }

        private void fire() {
            synchronized (this) {
                if (closed || executingThread != null) {
                    return;
                }
                executingThread = Thread.currentThread();
            }
            try {
                action.run();
            } catch (RuntimeException failure) {
                LOGGER.warn("Mail-send stop action failed", failure);
            } finally {
                synchronized (this) {
                    executingThread = null;
                    notifyAll();
                }
            }
        }

        @Override
        public void close() {
            boolean interrupted = false;
            synchronized (this) {
                closed = true;
                while (executingThread != null && executingThread != Thread.currentThread()) {
                    try {
                        wait();
                    } catch (InterruptedException interruption) {
                        interrupted = true;
                    }
                }
            }
            synchronized (MailSendControl.this) {
                registrations.remove(this);
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public final class DeadlinePause implements AutoCloseable {
        private boolean closed;

        private DeadlinePause() {
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                resumeDeadline();
            }
        }
    }
}
