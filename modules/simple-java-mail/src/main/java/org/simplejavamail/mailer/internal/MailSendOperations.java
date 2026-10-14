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
    private static final ThreadLocal<ExecutingScope> CURRENT_OPERATION = new ThreadLocal<>();

    private final OperationalConfig config;
    private final ScheduledThreadPoolExecutor deadlines;
    private final ExecutorService completions;
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private int active;
    private boolean closing;
    private MailSendRetryCoordinator retries;
    private MailSendReplayBudget replayBudget;

    MailSendOperations(final OperationalConfig config) {
        this(config, null);
    }

    /** Internal scheduler injection lets race tests force timer handoff without wall-clock sleeps. */
    MailSendOperations(final OperationalConfig config, final ScheduledThreadPoolExecutor suppliedTimers) {
        this.config = config;
        final int number = SEQUENCE.incrementAndGet();
        deadlines = suppliedTimers == null ? new ScheduledThreadPoolExecutor(1, daemonThreads("sjm-deadline-" + number)) : suppliedTimers;
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

    synchronized MailSendRetryCoordinator.Ticket reserveRetry(final MailSendOperation<?> operation) {
        if (retries == null) {
            retries = new MailSendRetryCoordinator(config.getMailSendRetryPolicy().getWaitingCapacity(), config.getExecutorService(), deadlines);
        }
        return retries.reserve(operation);
    }

    synchronized MailSendReplayBudget replayBudget() {
        if (replayBudget == null) { replayBudget = new MailSendReplayBudget(config); }
        return replayBudget;
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
        final ExecutingScope previous = CURRENT_OPERATION.get();
        CURRENT_OPERATION.set(new ExecutingScope(this, previous));
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
        for (ExecutingScope scope = CURRENT_OPERATION.get(); scope != null; scope = scope.previous) {
            if (scope.owner == this) {
                return true;
            }
        }
        return false;
    }

    /** Delegated sends belong to every participating owner until they return, not just to the innermost selected Mailer. */
    private static final class ExecutingScope {
        private final MailSendOperations owner;
        private final ExecutingScope previous;

        private ExecutingScope(final MailSendOperations owner, final ExecutingScope previous) {
            this.owner = owner;
            this.previous = previous;
        }
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
            if (retries != null) { retries.close(); }
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
