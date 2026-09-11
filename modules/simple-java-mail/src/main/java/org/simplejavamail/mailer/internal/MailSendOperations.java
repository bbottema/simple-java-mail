package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.internal.util.concurrent.MailSendControl;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Keeps deadline signals and terminal application callbacks on separate, lazily started Mailer-owned workers. */
final class MailSendOperations {
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final ThreadLocal<MailSendOperations> CURRENT_OPERATION = new ThreadLocal<>();

    private final OperationalConfig config;
    private final ScheduledThreadPoolExecutor deadlines;
    private final ExecutorService completions;
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private int active;
    private boolean closing;

    MailSendOperations(final OperationalConfig config) {
        this.config = config;
        final int number = SEQUENCE.incrementAndGet();
        deadlines = new ScheduledThreadPoolExecutor(1, daemonThreads("sjm-deadline-" + number));
        deadlines.setRemoveOnCancelPolicy(true);
        completions = Executors.newSingleThreadExecutor(daemonThreads("sjm-control-completion-" + number));
    }

    synchronized <T> MailSendOperation<T> begin(final Consumer<T> success, final Consumer<Throwable> failure) {
        if (closing) {
            throw closedRejection();
        }
        final MailSendOperation<T> operation = new MailSendOperation<>(this, new MailSendControl(config.getMailSendTimeout(), deadlines), success, failure);
        active++;
        return operation;
    }

    void schedule(final MailSendOperation<?> operation) {
        config.getExecutorService().execute(operation);
    }

    void completeUnstarted(final MailSendOperation<?> operation, final Runnable completion) {
        if (config.getExecutorService() instanceof MailSendExecutor) {
            ((MailSendExecutor) config.getExecutorService()).remove(operation);
        }
        completions.execute(completion);
    }

    void withinOperation(final Runnable action) {
        withinOperation(() -> {
            action.run();
            return null;
        });
    }

    <T> T withinOperation(final Supplier<T> action) {
        final MailSendOperations previous = CURRENT_OPERATION.get();
        CURRENT_OPERATION.set(this);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT_OPERATION.remove();
            } else {
                CURRENT_OPERATION.set(previous);
            }
        }
    }

    synchronized Scope openScope() {
        if (closing) {
            throw closedRejection();
        }
        active++;
        return new Scope();
    }

    /** A caller's open-connection scope owns resources, but its application work has no send deadline. */
    final class Scope implements AutoCloseable {
        private boolean closed;

        private Scope() {
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                finished();
            }
        }
    }

    boolean isCurrentOperation() {
        return CURRENT_OPERATION.get() == this;
    }

    private RuntimeException closedRejection() {
        return !config.isExecutorServiceIsUserProvided() && config.getExecutorService() instanceof MailSendExecutor
                ? ((MailSendExecutor) config.getExecutorService()).rejectAfterShutdown()
                : new RejectedExecutionException("Mailer is closing");
    }

    synchronized void finished() {
        active--;
        finishShutdownIfDrained();
    }

    synchronized CompletableFuture<Void> shutdown() {
        closing = true;
        finishShutdownIfDrained();
        return drained;
    }

    private void finishShutdownIfDrained() {
        if (closing && active == 0) {
            deadlines.shutdown();
            completions.shutdown();
            drained.complete(null);
        }
    }

    private static ThreadFactory daemonThreads(final String name) {
        return action -> {
            final Thread thread = new Thread(action, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
