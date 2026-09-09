package org.simplejavamail.api.mailer;

import lombok.Value;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.mailer.config.AsyncQueueConfig;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Content-free diagnostic snapshot of one Mailer's built-in executor, including sends, simple batches and asynchronous connection tests.
 * Queued and active counts are instantaneous estimates, not admission guarantees. Rejection counters are cumulative for this executor.
 * The configuration snapshot and built Mailer's OperationalConfig describe settings; this snapshot describes changing executor activity.
 */
@Value
public class AsyncQueueSnapshot {
    /** Immutable queue settings used by this executor. */
    @NotNull AsyncQueueConfig configuration;
    /** Maximum simultaneously executing tasks; these workers may themselves be waiting for a connection-pool lease. */
    int workerLimit;
    /** Tasks waiting for a worker; a simple batch occupies one slot regardless of its eventual email count. */
    int queuedCount;
    /** Approximate number of workers executing tasks, including observers and pool waits. */
    int activeCount;
    /** Whether shutdown has begun; accepted tasks may still be draining. */
    boolean shutdown;
    /** Counts since this executor was created, including asynchronous connection tests and rejected batches. */
    @NotNull Map<AsyncQueueRejectionReason, Long> rejectionCounts;

    /** Creates a detached snapshot and copies the rejection counters; no Email or executor is retained. */
    public AsyncQueueSnapshot(@NotNull final AsyncQueueConfig configuration, final int workerLimit,
                              final int queuedCount, final int activeCount, final boolean shutdown,
                              @NotNull final Map<AsyncQueueRejectionReason, Long> rejectionCounts) {
        this.configuration = requireNonNull(configuration, "configuration");
        this.workerLimit = workerLimit;
        this.queuedCount = queuedCount;
        this.activeCount = activeCount;
        this.shutdown = shutdown;
        final EnumMap<AsyncQueueRejectionReason, Long> counts = new EnumMap<>(AsyncQueueRejectionReason.class);
        counts.putAll(requireNonNull(rejectionCounts, "rejectionCounts"));
        this.rejectionCounts = Collections.unmodifiableMap(counts);
    }
}
