package org.simplejavamail.internal.smimesupport;

import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.utils.mail.smime.SmimeKey;

import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/** Owns reuse of parsed S/MIME keys without changing their configuration or cryptographic interpretation. */
final class SmimeKeyCache {

    // Internal reuse policy: a finite working set across batches, without keeping every customer's old credentials.
    private static final int MAXIMUM_ENTRIES = 256;
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);

    private final Object monitor = new Object();
    private final LinkedHashMap<Pkcs12Config, ResidentKey> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<Pkcs12Config, CompletableFuture<SmimeKey>> loading = new HashMap<>();
    private final int maximumEntries;
    private final long idleNanos;
    private final LongSupplier clock;
    private final Supplier<ScheduledExecutorService> maintenanceFactory;
    private ScheduledExecutorService maintenance;

    static SmimeKeyCache create() {
        return new SmimeKeyCache(MAXIMUM_ENTRIES, IDLE_TIMEOUT, System::nanoTime, SmimeKeyCache::newMaintenanceWorker);
    }

    SmimeKeyCache(final int maximumEntries, final Duration idleTimeout, final LongSupplier clock,
            final Supplier<ScheduledExecutorService> maintenanceFactory) {
        if (maximumEntries < 1) throw new IllegalArgumentException("S/MIME cache capacity must be positive");
        this.idleNanos = idleTimeout.toNanos();
        if (idleNanos < 1) throw new IllegalArgumentException("S/MIME cache idle timeout must be positive");
        this.maximumEntries = maximumEntries;
        this.clock = requireNonNull(clock);
        this.maintenanceFactory = requireNonNull(maintenanceFactory);
    }

    SmimeKey get(final Pkcs12Config configuration, final Function<Pkcs12Config, SmimeKey> loader) {
        final CompletableFuture<SmimeKey> pending;
        final boolean loadRequired;
        synchronized (monitor) {
            final long now = clock.getAsLong();
            removeExpired(now);
            final ResidentKey resident = entries.get(configuration);
            if (resident != null) {
                resident.lastAccess = now;
                return resident.key;
            }
            final CompletableFuture<SmimeKey> existingLoad = loading.get(configuration);
            loadRequired = existingLoad == null;
            pending = loadRequired ? new CompletableFuture<>() : existingLoad;
            if (loadRequired) loading.put(configuration, pending);
        }
        return loadRequired ? loadAndPublish(configuration, loader, pending) : await(pending);
    }

    private SmimeKey loadAndPublish(final Pkcs12Config configuration, final Function<Pkcs12Config, SmimeKey> loader,
            final CompletableFuture<SmimeKey> pending) {
        try {
            // Keystore loading can be slow; unrelated configurations must never wait behind it on the cache monitor.
            final SmimeKey key = requireNonNull(loader.apply(configuration));
            synchronized (monitor) {
                final long now = clock.getAsLong();
                removeExpired(now);
                entries.put(configuration, new ResidentKey(key, now));
                evictOverCapacity();
                if (maintenance == null) scheduleMaintenance(now);
            }
            pending.complete(key);
            return key;
        } catch (RuntimeException | Error failure) {
            pending.completeExceptionally(failure);
            throw failure;
        } finally {
            synchronized (monitor) {
                loading.remove(configuration, pending);
            }
        }
    }

    private static SmimeKey await(final CompletableFuture<SmimeKey> pending) {
        try {
            return pending.join();
        } catch (CompletionException failure) {
            // Sharing a load must not replace the producing boundary's exception with a future wrapper.
            if (failure.getCause() instanceof RuntimeException) throw (RuntimeException) failure.getCause();
            if (failure.getCause() instanceof Error) throw (Error) failure.getCause();
            throw failure;
        }
    }

    private void removeExpired(final long now) {
        final Iterator<ResidentKey> oldestFirst = entries.values().iterator();
        while (oldestFirst.hasNext()) {
            if (now - oldestFirst.next().lastAccess < idleNanos) break;
            oldestFirst.remove();
        }
    }

    private void evictOverCapacity() {
        while (entries.size() > maximumEntries) {
            final Iterator<ResidentKey> oldestFirst = entries.values().iterator();
            oldestFirst.next();
            oldestFirst.remove();
        }
    }

    private void scheduleMaintenance(final long now) {
        try {
            if (maintenance == null) maintenance = requireNonNull(maintenanceFactory.get());
            final ScheduledExecutorService owner = maintenance;
            final long remaining = idleNanos - (now - entries.values().iterator().next().lastAccess);
            owner.schedule(() -> expireIdleEntries(owner), remaining, TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException | SecurityException unavailable) {
            // Reuse is optional. Without an expiry worker, drop cache ownership and let valid operations keep their key.
            entries.clear();
            retireMaintenance();
        } catch (RuntimeException | Error failure) {
            entries.clear();
            retireMaintenance();
            throw failure;
        }
    }

    private void expireIdleEntries(final ScheduledExecutorService owner) {
        synchronized (monitor) {
            if (maintenance != owner) return;
            final long now = clock.getAsLong();
            removeExpired(now);
            if (entries.isEmpty()) retireMaintenance();
            else scheduleMaintenance(now);
        }
    }

    private void retireMaintenance() {
        final ScheduledExecutorService retired = maintenance;
        maintenance = null;
        if (retired != null) {
            try {
                retired.shutdown();
            } catch (SecurityException unavailable) {
                // No cache entries remain, and any already queued task is fenced from a replacement worker.
            }
        }
    }

    private static ScheduledExecutorService newMaintenanceWorker() {
        final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1, task -> {
            final Thread thread = new Thread(null, task, "sjm-smime-key-cache", 0, false);
            thread.setDaemon(true);
            thread.setContextClassLoader(null);
            return thread;
        });
        // A supported security manager can allow thread creation while denying executor shutdown.
        // An empty worker must still release its thread and module loader in that case.
        worker.setKeepAliveTime(30, TimeUnit.SECONDS);
        worker.allowCoreThreadTimeOut(true);
        worker.setRemoveOnCancelPolicy(true);
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return worker;
    }

    private static final class ResidentKey {
        private final SmimeKey key;
        private long lastAccess;

        private ResidentKey(final SmimeKey key, final long lastAccess) {
            this.key = key;
            this.lastAccess = lastAccess;
        }
    }
}
