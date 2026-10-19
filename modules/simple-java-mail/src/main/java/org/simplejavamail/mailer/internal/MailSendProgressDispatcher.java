package org.simplejavamail.mailer.internal;

import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailSendHandlers;
import org.simplejavamail.api.mailer.MailSendProgress;
import org.simplejavamail.api.mailer.MailSendProgressObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

/** One bounded, serialized progress lane per Mailer. Pending snapshots own their callback targets; there is no unbounded ID registry. */
final class MailSendProgressDispatcher implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(MailSendProgressDispatcher.class);
    static final int MAX_PENDING_IDS = 1024;
    private final Target[] defaultTargets;
    private final Map<UUID, Delivery> pending = new LinkedHashMap<>();
    @Nullable private Thread worker;
    @Nullable private Notification inFlight;
    @Nullable private Delivery continuation;
    private boolean closed;

    MailSendProgressDispatcher() { defaultTargets = new Target[0]; }
    MailSendProgressDispatcher(final MailSendProgressObserver observer, @Nullable final Executor executor) {
        defaultTargets = new Target[] {new Target(observer, executor)};
    }

    static Target[] targets(@Nullable final MailSendHandlers fixed, @Nullable final MailSendHandlers generated) {
        final List<Target> targets = new ArrayList<>(2);
        addTarget(targets, fixed);
        addTarget(targets, generated);
        return targets.toArray(new Target[0]);
    }

    private static void addTarget(final List<Target> targets, @Nullable final MailSendHandlers handlers) {
        if (handlers != null && handlers.getProgressObserver() != null) {
            targets.add(new Target(handlers.getProgressObserver(), handlers.getProgressObserverExecutor()));
        }
    }

    void offer(final MailSendProgress progress) { offer(progress, defaultTargets); }

    synchronized void offer(final MailSendProgress progress, final Target[] targets) {
        if (closed || targets.length == 0) { return; }
        final Delivery previous = pending.get(progress.getObservationId());
        if (previous != null && previous.snapshot.getSequence() >= progress.getSequence()) { return; }
        pending.remove(progress.getObservationId());
        pending.put(progress.getObservationId(), new Delivery(progress, targets, 0));
        if (pending.size() > MAX_PENDING_IDS) { pending.remove(pending.keySet().iterator().next()); }
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
            while ((next = awaitNextNotification()) != null) { handOff(next); }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); close(); }
    }

    @Nullable private synchronized Notification awaitNextNotification() throws InterruptedException {
        while (!closed && ((pending.isEmpty() && continuation == null) || inFlight != null)) { wait(); }
        if (closed) { return null; }
        final Delivery next;
        if (continuation != null) { next = continuation; continuation = null; }
        else { next = pending.remove(pending.keySet().iterator().next()); }
        inFlight = new Notification(this, next);
        return inFlight;
    }

    private void handOff(final Notification notification) {
        final Delivery delivery = notification.progress;
        if (delivery == null) { return; }
        try {
            final Executor executor = delivery.targets[delivery.index].executor;
            if (executor == null) { notification.run(); }
            else { executor.execute(notification); }
        } catch (RuntimeException rejection) {
            discardUnstartedNotification(notification);
            LOGGER.warn("Mail send progress could not be handed off; sending continues unchanged", rejection);
        }
    }

    private synchronized void discardUnstartedNotification(final Notification notification) {
        if (!notification.started) { acknowledge(notification, notification.progress); }
    }

    @Nullable private synchronized Delivery beginNotification(final Notification notification) {
        if (closed || inFlight != notification || notification.started) { return null; }
        notification.started = true;
        final Delivery progress = notification.progress;
        notification.detach();
        return progress;
    }

    private void deliver(final Notification notification) {
        final Delivery delivery = beginNotification(notification);
        if (delivery == null) { return; }
        try { delivery.targets[delivery.index].observer.onMailSendProgress(delivery.snapshot); }
        catch (RuntimeException failure) { LOGGER.warn("Mail send progress observer failed; sending continues unchanged", failure); }
        finally { acknowledge(notification, delivery); }
    }

    private synchronized void acknowledge(final Notification notification, @Nullable final Delivery delivery) {
        notification.detach();
        if (inFlight == notification) {
            inFlight = null;
            if (!closed && delivery != null && delivery.index + 1 < delivery.targets.length) {
                continuation = new Delivery(delivery.snapshot, delivery.targets, delivery.index + 1);
            }
            notifyAll();
        }
    }

    @Override public synchronized void close() {
        closed = true;
        pending.clear();
        continuation = null;
        if (inFlight != null) { discardUnstartedNotification(inFlight); }
        notifyAll();
    }

    static final class Target {
        private final MailSendProgressObserver observer;
        @Nullable private final Executor executor;
        private Target(final MailSendProgressObserver observer, @Nullable final Executor executor) { this.observer = observer; this.executor = executor; }
    }

    private static final class Delivery {
        private final MailSendProgress snapshot;
        private final Target[] targets;
        private final int index;
        private Delivery(final MailSendProgress snapshot, final Target[] targets, final int index) {
            this.snapshot = snapshot; this.targets = targets; this.index = index;
        }
    }

    private static final class Notification implements Runnable {
        @Nullable private volatile MailSendProgressDispatcher owner;
        @Nullable private Delivery progress;
        private boolean started;
        private Notification(final MailSendProgressDispatcher owner, final Delivery progress) { this.owner = owner; this.progress = progress; }
        private void detach() { progress = null; owner = null; }
        @Override public void run() {
            final MailSendProgressDispatcher dispatcher = owner;
            if (dispatcher != null) { dispatcher.deliver(this); }
        }
    }
}