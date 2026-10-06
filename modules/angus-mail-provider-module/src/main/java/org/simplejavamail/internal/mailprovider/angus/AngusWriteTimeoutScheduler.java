package org.simplejavamail.internal.mailprovider.angus;

import org.eclipse.angus.mail.util.PropUtil;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Shares Angus write-timeout scheduling within one owned Session, without keeping an idle Session's worker alive.
 * Each physical connection holds a lease until disposal. Socket abort alone does not release that lease.
 */
final class AngusWriteTimeoutScheduler extends AbstractExecutorService implements ScheduledExecutorService {
    @Nullable private ScheduledThreadPoolExecutor current;
    private int connections;
    private boolean shutdown;

    static void configure(final Properties properties, final String prefix) {
        final String key = prefix + ".executor.writetimeout";
        if (PropUtil.getIntProperty(properties, prefix + ".writetimeout", -1) > 0
                && !properties.containsKey(key) && properties.getProperty(key) == null) {
            properties.put(key, new AngusWriteTimeoutScheduler());
        }
    }

    @Nullable
    static AngusWriteTimeoutScheduler find(final Properties properties, final String protocol) {
        final Object configured = properties.get("mail." + protocol + ".executor.writetimeout");
        return configured instanceof AngusWriteTimeoutScheduler ? (AngusWriteTimeoutScheduler) configured : null;
    }

    /** A probe copied the source Session's properties, but must not inherit its internal runtime ownership. */
    static void isolateProbe(final Properties properties, final String prefix) {
        if (properties.get(prefix + ".executor.writetimeout") instanceof AngusWriteTimeoutScheduler) {
            properties.put(prefix + ".executor.writetimeout", new AngusWriteTimeoutScheduler());
        }
    }

    synchronized Lease retainConnection() {
        if (shutdown) {
            throw new RejectedExecutionException("The managed SMTP write-timeout scheduler was shut down. Build a new Mailer instead of shutting down "
                    + "the internal scheduler; application-supplied schedulers can be managed separately.");
        }
        if (current == null) {
            current = new ScheduledThreadPoolExecutor(1, task -> {
                final Thread worker = new Thread(task, "Simple Java Mail SMTP write timeout");
                worker.setDaemon(true);
                return worker;
            });
            current.setRemoveOnCancelPolicy(true);
        }
        connections++;
        return new Lease(this, current);
    }

    private void releaseConnection(final ScheduledThreadPoolExecutor generation) {
        final boolean unused;
        synchronized (this) {
            if (current != generation || connections == 0) {
                throw new IllegalStateException("The SMTP write-timeout connection lease does not belong to the active scheduler generation");
            }
            connections--;
            unused = connections == 0;
            if (unused && !shutdown) {
                current = null;
            }
        }
        if (unused) {
            generation.shutdownNow();
        }
    }

    private synchronized ScheduledThreadPoolExecutor activeExecutor() {
        if (shutdown || current == null) {
            throw new RejectedExecutionException("No open managed SMTP connection owns this write-timeout scheduler");
        }
        return current;
    }

    @Override
    public ScheduledFuture<?> schedule(final Runnable task, final long delay, final TimeUnit unit) {
        return activeExecutor().schedule(task, delay, unit);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(final Callable<V> task, final long delay, final TimeUnit unit) {
        return activeExecutor().schedule(task, delay, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(final Runnable task, final long initialDelay, final long period, final TimeUnit unit) {
        return activeExecutor().scheduleAtFixedRate(task, initialDelay, period, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(final Runnable task, final long initialDelay, final long delay, final TimeUnit unit) {
        return activeExecutor().scheduleWithFixedDelay(task, initialDelay, delay, unit);
    }

    @Override
    public void execute(final Runnable task) {
        activeExecutor().execute(task);
    }

    @Override
    public void shutdown() {
        final ScheduledThreadPoolExecutor executor = stopAcceptingConnections();
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Override
    public List<Runnable> shutdownNow() {
        final ScheduledThreadPoolExecutor executor = stopAcceptingConnections();
        return executor == null ? List.of() : executor.shutdownNow();
    }

    @Nullable
    private synchronized ScheduledThreadPoolExecutor stopAcceptingConnections() {
        shutdown = true;
        return current;
    }

    @Override
    public synchronized boolean isShutdown() {
        return shutdown;
    }

    @Override
    public synchronized boolean isTerminated() {
        return shutdown && (current == null || current.isTerminated());
    }

    @Override
    public boolean awaitTermination(final long timeout, final TimeUnit unit) throws InterruptedException {
        final ScheduledThreadPoolExecutor executor;
        synchronized (this) {
            if (!shutdown) {
                return false;
            }
            executor = current;
        }
        return executor == null || executor.awaitTermination(timeout, unit);
    }

    /** Idempotent disposal cannot release another connection's reference, even after a reconnect creates a new generation. */
    static final class Lease implements AutoCloseable {
        private final AngusWriteTimeoutScheduler owner;
        private final ScheduledThreadPoolExecutor generation;
        private boolean closed;

        private Lease(final AngusWriteTimeoutScheduler owner, final ScheduledThreadPoolExecutor generation) {
            this.owner = owner;
            this.generation = generation;
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                owner.releaseConnection(generation);
            }
        }
    }
}
