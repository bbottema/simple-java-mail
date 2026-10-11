package org.simplejavamail.mailer.internal;

import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.config.FailoverPolicy;

import java.util.function.LongSupplier;

/** Factory-local connection observations. Its monitor protects bookkeeping only, never connection work or waiting. */
final class ConnectionRecovery {

	private final LongSupplier clock;
	/** Success and a new due recheck retire older work; a failure must not discard a still-running peer's success. */
	private long observationGeneration;
	private long latestFailureAt;
	private int consecutiveFailedRechecks;
	private boolean hasReportedFailover;
	private boolean usesCoordinatedFailover;
	@Nullable private ConnectionSetupFailure latestFailure;
	@Nullable private Visit activeRecheck;

	ConnectionRecovery() {
		this(System::nanoTime);
	}

	ConnectionRecovery(final LongSupplier clock) {
		this.clock = clock;
	}

	/** Once this destination participates in a configured route, background allocation must respect recorded incidents too. */
	synchronized void enableCoordinatedFailover() {
		usesCoordinatedFailover = true;
	}

	/** Each caller applies its own permission and intervals to shared facts; one permissive policy never changes another policy. */
	synchronized Visit openVisit(@Nullable final FailoverPolicy policy) {
		usesCoordinatedFailover |= policy != null;
		if (latestFailure == null || policy == null) {
			return new Visit(true, null);
		}
		if (hasCurrentRecheck() || clock.getAsLong() - latestFailureAt < recheckIntervalNanos(policy)) {
			if (!latestFailure.allowsFailover(policy)) {
				throw new MailerException("An earlier connection attempt failed, and this Mailer's failover policy does not allow another destination "
						+ "for that failure. No new connection was attempted; a primary recheck is not due or is already running. "
						+ "Review the cause and this route's authentication/certificate permissions.", latestFailure);
			}
			return new Visit(false, latestFailure);
		}
		// Rechecking the original destination is not permission to bypass its authentication or certificate checks.
		// A deliberate new recheck supersedes pending results from the earlier connection window.
		observationGeneration++;
		activeRecheck = new Visit(true, latestFailure);
		return activeRecheck;
	}

	/** A recovered visit may still be sending or cleaning up, but it no longer owns later connection observations. */
	private boolean hasCurrentRecheck() {
		return activeRecheck != null && activeRecheck.generation == observationGeneration;
	}

	private long recheckIntervalNanos(final FailoverPolicy policy) {
		long interval = policy.getPrimaryRecheckInterval().toNanos();
		final long maximum = policy.getPrimaryMaximumRecheckInterval().toNanos();
		final int multiplier = policy.getPrimaryRecheckBackoffMultiplier();
		for (int recheckNumber = 0; multiplier > 1 && recheckNumber < consecutiveFailedRechecks && interval < maximum; recheckNumber++) {
			// Compare before multiplying so increasing backoff cannot overflow into an immediate recheck.
			interval = interval > maximum / multiplier ? maximum : Math.min(maximum, interval * multiplier);
		}
		return interval;
	}

	synchronized boolean allowsBackgroundConnections() {
		return !usesCoordinatedFailover || latestFailure == null;
	}

	/** Report the routing remedy once per incident, independently of how many Mailers or concurrent sends observe it. */
	synchronized boolean markFailoverReported() {
		if (latestFailure == null || hasReportedFailover) {
			return false;
		}
		hasReportedFailover = true;
		return true;
	}

	/** Capture the current connection window and its recheck owner; all publication happens under this monitor. */
	synchronized Observation observeConnection() {
		return new Observation(observationGeneration, hasCurrentRecheck() ? activeRecheck : null);
	}

	final class Observation {
		private final long startedGeneration;
		@Nullable private final Visit owningRecheck;
		private boolean completed;

		private Observation(final long startedGeneration, @Nullable final Visit owningRecheck) {
			this.startedGeneration = startedGeneration;
			this.owningRecheck = owningRecheck;
		}

		boolean recordSuccessfulConnection() {
			synchronized (ConnectionRecovery.this) {
				if (!canPublishObservation()) {
					return false;
				}
				completed = true;
				final boolean recovered = latestFailure != null;
				latestFailure = null;
				consecutiveFailedRechecks = 0;
				hasReportedFailover = false;
				// A usable connection outweighs overlapping failures; their late results must not restore the old incident.
				observationGeneration++;
				return recovered;
			}
		}

		boolean recordFailedConnection(final ConnectionSetupFailure currentFailure) {
			synchronized (ConnectionRecovery.this) {
				if (!canPublishObservation()) {
					return false;
				}
				completed = true;
				final boolean firstFailure = latestFailure == null;
				latestFailure = currentFailure;
				latestFailureAt = clock.getAsLong();
				// Immediate retries belong to one visit, not separate recovery-backoff steps.
				if (owningRecheck != null && !owningRecheck.hasRecordedFailure) {
					consecutiveFailedRechecks = consecutiveFailedRechecks == Integer.MAX_VALUE ? consecutiveFailedRechecks : consecutiveFailedRechecks + 1;
					owningRecheck.hasRecordedFailure = true;
				}
				// Keep the window open: another connection already in flight can still demonstrate recovery.
				return firstFailure;
			}
		}

		/** Called under the recovery monitor; cancellation retires the whole visit, independently of peer results. */
		private boolean canPublishObservation() {
			return !completed && startedGeneration == observationGeneration && (owningRecheck == null || !owningRecheck.closed);
		}
	}

	/** A routing scope fences unfinished recheck observations on close without retiring a newer connection window. */
	final class Visit implements AutoCloseable {
		final boolean shouldAttemptConnection;
		@Nullable final ConnectionSetupFailure cachedFailure;
		private final long generation;
		private boolean closed;
		private boolean hasRecordedFailure;

		private Visit(final boolean shouldAttemptConnection, @Nullable final ConnectionSetupFailure cachedFailure) {
			this.shouldAttemptConnection = shouldAttemptConnection;
			this.cachedFailure = cachedFailure;
			this.generation = observationGeneration;
		}

		@Override
		public void close() {
			synchronized (ConnectionRecovery.this) {
				if (activeRecheck == this) {
					activeRecheck = null;
				}
				closed = true;
			}
		}
	}
}
