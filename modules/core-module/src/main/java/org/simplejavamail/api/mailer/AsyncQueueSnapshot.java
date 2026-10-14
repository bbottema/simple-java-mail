package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.mailer.config.AsyncQueueConfig;

import java.util.Map;

/**
 * Content-free diagnostic snapshot of one Mailer's built-in executor, including sends, simple batches and asynchronous connection tests.
 * Queued and active counts are instantaneous estimates, not admission guarantees. Rejection counters are cumulative for this executor.
 * The configuration snapshot and built Mailer's OperationalConfig describe settings; this snapshot describes changing executor activity.
 */
public interface AsyncQueueSnapshot {
	/** Immutable queue settings used by this executor. */
	@NotNull AsyncQueueConfig getConfiguration();
	/** Maximum simultaneous workers, including workers waiting for a connection. */
	int getWorkerLimit();
	/** Tasks waiting for a worker; a simple batch occupies one slot. */
	int getQueuedCount();
	/** Approximate number of executing workers, including observer and pool waits. */
	int getActiveCount();
	/** Whether shutdown has begun; accepted tasks may still be draining. */
	boolean isShutdown();
	/** Immutable rejection counts since executor creation. */
	@NotNull Map<AsyncQueueRejectionReason, Long> getRejectionCounts();
}
