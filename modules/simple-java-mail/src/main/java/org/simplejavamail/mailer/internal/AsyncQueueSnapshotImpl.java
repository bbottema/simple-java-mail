package org.simplejavamail.mailer.internal;

import lombok.Builder;
import lombok.Value;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.mailer.AsyncQueueRejectionReason;
import org.simplejavamail.api.mailer.AsyncQueueSnapshot;
import org.simplejavamail.api.mailer.config.AsyncQueueConfig;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/** Library-owned immutable implementation of {@link AsyncQueueSnapshot}. */
@Value
@ApiStatus.Internal
public final class AsyncQueueSnapshotImpl implements AsyncQueueSnapshot {
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

    /** Copies and validates the supplied observations; it performs no send work. */
	@Builder
	private AsyncQueueSnapshotImpl(@NotNull final AsyncQueueConfig configuration, final int workerLimit,
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
