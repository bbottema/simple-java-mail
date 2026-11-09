package org.simplejavamail.internal.smimesupport;

import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.utils.mail.smime.SmimeKey;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/** One factory's completed-key capacity and active loads. No time-based or process-global ownership. */
final class SmimeKeyCache {
    private final Object monitor = new Object();
    private final LinkedHashMap<Pkcs12Config, SmimeKey> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<Pkcs12Config, CompletableFuture<SmimeKey>> loading = new HashMap<>();
    private final int capacity;

    SmimeKeyCache(final int capacity) {
        if (capacity < -1) {
            throw new IllegalArgumentException(ConfigLoader.Property.SMIME_KEY_CACHE_CAPACITY.key()
                    + " must be -1 (unlimited), 0 (no completed entries), or a positive integer.");
        }
        this.capacity = capacity;
    }

    SmimeKey get(final Pkcs12Config configuration, final Function<Pkcs12Config, SmimeKey> loader) {
        final CompletableFuture<SmimeKey> pending;
        final boolean loadRequired;
        synchronized (monitor) {
            final SmimeKey resident = entries.get(configuration);
            if (resident != null) return resident;
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
            // Expensive keystore work stays on its caller, outside bookkeeping and unrelated configurations' loads.
            final SmimeKey key = requireNonNull(loader.apply(configuration));
            synchronized (monitor) {
                if (capacity != 0) {
                    entries.put(configuration, key);
                    evictOverCapacity();
                }
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

    private void evictOverCapacity() {
        if (capacity == -1) return;
        while (entries.size() > capacity) {
            final Iterator<SmimeKey> oldestFirst = entries.values().iterator();
            oldestFirst.next();
            oldestFirst.remove();
        }
    }

    private static SmimeKey await(final CompletableFuture<SmimeKey> pending) {
        try {
            return pending.join();
        } catch (CompletionException failure) {
            // join() may reuse a CompletionException supplied by the loader rather than add its own wrapper.
            final Throwable original = pending.handle((key, loadFailure) -> loadFailure).join();
            if (original instanceof RuntimeException) throw (RuntimeException) original;
            if (original instanceof Error) throw (Error) original;
            throw failure;
        }
    }
}
