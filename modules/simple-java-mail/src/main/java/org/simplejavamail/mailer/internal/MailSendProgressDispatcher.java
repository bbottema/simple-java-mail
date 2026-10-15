package org.simplejavamail.mailer.internal;

import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailSendProgress;
import org.simplejavamail.api.mailer.MailSendProgressObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Keeps dashboard work away from send threads. The monitor protects only queue/dispatch bookkeeping; callbacks and executor handoff run outside it.
 * Closing wakes an idle dispatcher but never interrupts or waits for application code, including an executor's blocking execute method.
 */
final class MailSendProgressDispatcher implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(MailSendProgressDispatcher.class);
    // A lagging UI retains at most 1K latest email snapshots, not an unbounded history or a limit on sending throughput.
    static final int MAX_PENDING_IDS = 1024;

    private final MailSendProgressObserver observer;
    @Nullable private final Executor executor;
    private final Map<UUID, MailSendProgress> pending = new LinkedHashMap<>();
    @Nullable private Thread worker;
    @Nullable private Notification inFlight;
    private boolean closed;

    MailSendProgressDispatcher(final MailSendProgressObserver observer, @Nullable final Executor executor) {
        this.observer = observer;
        this.executor = executor;
    }

    synchronized void offer(final MailSendProgress progress) {
        if (closed) {
            return;
        }
        final MailSendProgress previous = pending.get(progress.getObservationId());
        if (previous != null && previous.getSequence() >= progress.getSequence()) {
            return;
        }
        // Replacing a snapshot refreshes its position too: overflow discards the oldest pending snapshot, not the oldest send ID.
        pending.remove(progress.getObservationId());
        pending.put(progress.getObservationId(), progress);
        if (pending.size() > MAX_PENDING_IDS) {
            pending.remove(pending.keySet().iterator().next());
        }
        startDispatcherIfNeeded();
        notifyAll();
    }

    private void startDispatcherIfNeeded() {
        if (worker == null) {
            try {
                worker = new Thread(this::dispatchPending, "Simple Java Mail progress");
                worker.setDaemon(true);
                worker.start();
            } catch (RuntimeException unavailable) {
                close();
                LOGGER.warn("Mail send progress dispatcher could not start; sending continues without progress reporting", unavailable);
            }
        }
    }

    private void dispatchPending() {
        try {
            Notification next;
            while ((next = awaitNextNotification()) != null) {
                handOff(next);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            close();
        }
    }

    @Nullable
    private synchronized Notification awaitNextNotification() throws InterruptedException {
        while (!closed && (pending.isEmpty() || inFlight != null)) {
            wait();
        }
        if (closed) {
            return null;
        }
        final UUID oldest = pending.keySet().iterator().next();
        inFlight = new Notification(this, pending.remove(oldest));
        return inFlight;
    }

    private void handOff(final Notification notification) {
        try {
            if (executor == null) {
                notification.run();
            } else {
                executor.execute(notification);
            }
        } catch (RuntimeException rejection) {
            // A strange executor can start a task and then throw. Keep that running callback serialized until its own finally acknowledges completion.
            discardUnstartedNotification(notification);
            LOGGER.warn("Mail send progress could not be handed off; sending continues unchanged", rejection);
        }
    }

    private synchronized void discardUnstartedNotification(final Notification notification) {
        if (!notification.started) {
            acknowledge(notification);
        }
    }

    @Nullable
    private synchronized MailSendProgress beginNotification(final Notification notification) {
        if (closed || inFlight != notification || notification.started) {
            return null;
        }
        notification.started = true;
        final MailSendProgress progress = notification.progress;
        // An executor can retain a completed Runnable. Only the running callback's stack should retain its owner and payload.
        notification.detach();
        return progress;
    }

    private void deliver(final Notification notification) {
        final MailSendProgress progress = beginNotification(notification);
        if (progress == null) {
            return;
        }
        try {
            observer.onMailSendProgress(progress);
        } catch (RuntimeException failure) {
            LOGGER.warn("Mail send progress observer failed; sending continues unchanged", failure);
        } finally {
            acknowledge(notification);
        }
    }

    private synchronized void acknowledge(final Notification notification) {
        notification.detach();
        if (inFlight == notification) {
            inFlight = null;
            notifyAll();
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        pending.clear();
        if (inFlight != null) {
            discardUnstartedNotification(inFlight);
        }
        notifyAll();
    }

    /** Owns one handoff, so duplicate/late execution by an application executor cannot overlap later callbacks. */
    private static final class Notification implements Runnable {
        @Nullable private volatile MailSendProgressDispatcher owner;
        // Payload and started are guarded by the owner's monitor; the volatile owner also permits an inert late run().
        @Nullable private MailSendProgress progress;
        private boolean started;

        private Notification(final MailSendProgressDispatcher owner, final MailSendProgress progress) {
            this.owner = owner;
            this.progress = progress;
        }

        private void detach() {
            progress = null;
            owner = null;
        }

        @Override
        public void run() {
            final MailSendProgressDispatcher dispatcher = owner;
            if (dispatcher != null) {
                dispatcher.deliver(this);
            }
        }
    }
}
