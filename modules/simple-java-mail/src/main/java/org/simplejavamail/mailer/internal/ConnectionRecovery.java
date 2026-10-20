package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.mailer.MailFailureKind;

import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.config.FailoverPolicy;

import java.util.function.LongSupplier;

/** Factory-local recovery bookkeeping. Explicit visit ownership keeps cancellation from retiring another caller's connection. */
final class ConnectionRecovery {

	private final LongSupplier clock;
	private ConnectionRecoveryState state = new ConnectionAttemptsAllowed(0);
	/** Ordinary pools still replenish normally until this destination participates in coordinated failover. */
	private boolean usesCoordinatedFailover;

	ConnectionRecovery() {
		this(System::nanoTime);
	}

	ConnectionRecovery(final LongSupplier clock) {
		this.clock = clock;
	}

	synchronized void enableCoordinatedFailover() {
		usesCoordinatedFailover = true;
	}

	/** Each caller applies its own permissions and intervals to the shared incident. No connection work runs under this monitor. */
	synchronized ConnectionRecoveryVisit openVisit(@Nullable final FailoverPolicy policy) {
		usesCoordinatedFailover |= policy != null;
		final ConnectionFailureIncident incident = state.incident();
		if (incident == null || policy == null) {
			return new ConnectionRecoveryVisit(true, null, state.generation);
		}
		if (state instanceof ConnectionRecheckInProgress || clock.getAsLong() - incident.failedAt < recheckIntervalNanos(policy, incident.failedRechecks)) {
			if (!incident.failure.allowsFailover(policy)) {
				throw new MailerException(MailFailureKind.CONNECTION, "An earlier connection attempt failed, and this Mailer's failover policy does not allow another destination "
						+ "for that failure. No new connection was attempted; a primary recheck is not due or is already running. "
						+ "Review the cause and this route's authentication/certificate permissions.", incident.failure);
			}
			return new ConnectionRecoveryVisit(false, incident.failure, state.generation);
		}
		// One due visit owns the recheck. Its generation fences results from the earlier connection window.
		final ConnectionRecoveryVisit visit = new ConnectionRecoveryVisit(true, incident.failure, state.generation + 1);
		state = new ConnectionRecheckInProgress(visit.generation, incident, visit);
		return visit;
	}

	private static long recheckIntervalNanos(final FailoverPolicy policy, final int failedRechecks) {
		long interval = policy.getPrimaryRecheckInterval().toNanos();
		final long maximum = policy.getPrimaryMaximumRecheckInterval().toNanos();
		final int multiplier = policy.getPrimaryRecheckBackoffMultiplier();
		for (int index = 0; multiplier > 1 && index < failedRechecks && interval < maximum; index++) {
			interval = interval > maximum / multiplier ? maximum : Math.min(maximum, interval * multiplier);
		}
		return interval;
	}

	synchronized boolean allowsBackgroundConnections() {
		return !usesCoordinatedFailover || state.incident() == null;
	}

	/** Report the remedy once per incident, independently of how many operations encounter it. */
	synchronized boolean markFailoverReported() {
		final ConnectionFailureIncident incident = state.incident();
		if (incident == null || incident.hasReportedFailover) {
			return false;
		}
		incident.hasReportedFailover = true;
		return true;
	}

	/** Background or unrelated connection work has its own lifetime, never that of an ambient recheck. */
	synchronized ConnectionRecoveryObservation observeConnection() {
		return new ConnectionRecoveryObservation(state.generation, null);
	}

	private abstract static class ConnectionRecoveryState {
		final long generation;

		ConnectionRecoveryState(final long generation) {
			this.generation = generation;
		}

		@Nullable abstract ConnectionFailureIncident incident();
	}

	private static final class ConnectionAttemptsAllowed extends ConnectionRecoveryState {
		ConnectionAttemptsAllowed(final long generation) {
			super(generation);
		}

		@Override ConnectionFailureIncident incident() {
			return null;
		}
	}

	private static class ConnectionFailureRecorded extends ConnectionRecoveryState {
		private final ConnectionFailureIncident incident;

		ConnectionFailureRecorded(final long generation, final ConnectionFailureIncident incident) {
			super(generation);
			this.incident = incident;
		}

		@Override ConnectionFailureIncident incident() {
			return incident;
		}
	}

	private static final class ConnectionRecheckInProgress extends ConnectionFailureRecorded {
		private final ConnectionRecoveryVisit owner;

		ConnectionRecheckInProgress(final long generation, final ConnectionFailureIncident incident, final ConnectionRecoveryVisit owner) {
			super(generation, incident);
			this.owner = owner;
		}
	}

	private static final class ConnectionFailureIncident {
		private final ConnectionSetupFailure failure;
		private final long failedAt;
		private final int failedRechecks;
		private boolean hasReportedFailover;

		ConnectionFailureIncident(final ConnectionSetupFailure failure, final long failedAt, final int failedRechecks, final boolean hasReportedFailover) {
			this.failure = failure;
			this.failedAt = failedAt;
			this.failedRechecks = failedRechecks;
			this.hasReportedFailover = hasReportedFailover;
		}
	}

	/** Internal publication result lets logging distinguish a repeated incident from a discarded late result. */
	enum ConnectionFailurePublication { FIRST_INCIDENT, REPEATED_FAILURE, IGNORED }

	final class ConnectionRecoveryObservation {
		private final long generation;
		@Nullable private final ConnectionRecoveryVisit owner;
		private boolean completed;

		private ConnectionRecoveryObservation(final long generation, @Nullable final ConnectionRecoveryVisit owner) {
			this.generation = generation;
			this.owner = owner;
		}

		boolean recordSuccessfulConnection() {
			synchronized (ConnectionRecovery.this) {
				if (!canPublish()) {
					return false;
				}
				completed = true;
				final boolean recovered = state.incident() != null;
				// A usable connection clears the incident and fences all overlapping older failures.
				state = new ConnectionAttemptsAllowed(state.generation + 1);
				return recovered;
			}
		}

		ConnectionFailurePublication recordFailedConnection(final ConnectionSetupFailure failure) {
			synchronized (ConnectionRecovery.this) {
				if (!canPublish()) {
					return ConnectionFailurePublication.IGNORED;
				}
				completed = true;
				final ConnectionFailureIncident previous = state.incident();
				final ConnectionFailureIncident incident = new ConnectionFailureIncident(failure, clock.getAsLong(), failedRecheckCount(previous),
						previous != null && previous.hasReportedFailover);
				// Failure keeps this generation open: an independent in-flight connection may still recover it.
				state = state instanceof ConnectionRecheckInProgress
						? new ConnectionRecheckInProgress(state.generation, incident, ((ConnectionRecheckInProgress) state).owner)
						: new ConnectionFailureRecorded(state.generation, incident);
				return previous == null ? ConnectionFailurePublication.FIRST_INCIDENT : ConnectionFailurePublication.REPEATED_FAILURE;
			}
		}

		private int failedRecheckCount(@Nullable final ConnectionFailureIncident previous) {
			final int count = previous == null ? 0 : previous.failedRechecks;
			if (state instanceof ConnectionRecheckInProgress && ((ConnectionRecheckInProgress) state).owner == owner && !owner.hasRecordedFailure) {
				owner.hasRecordedFailure = true;
				return count == Integer.MAX_VALUE ? count : count + 1;
			}
			return count;
		}

		void abandon() {
			synchronized (ConnectionRecovery.this) {
				completed = true;
			}
		}

		/** Called only under the recovery monitor; visit retirement does not fence independent work. */
		private boolean canPublish() {
			return !completed && generation == state.generation && (owner == null || !owner.closed);
		}
	}

	/** A visit explicitly owns its observations, including immediate retries, but never another visit's work. */
	final class ConnectionRecoveryVisit implements AutoCloseable {
		final boolean shouldAttemptConnection;
		@Nullable final ConnectionSetupFailure cachedFailure;
		private final long generation;
		private boolean closed;
		private boolean hasRecordedFailure;

		private ConnectionRecoveryVisit(final boolean shouldAttemptConnection, @Nullable final ConnectionSetupFailure cachedFailure, final long generation) {
			this.shouldAttemptConnection = shouldAttemptConnection;
			this.cachedFailure = cachedFailure;
			this.generation = generation;
		}

		ConnectionRecoveryObservation observeConnection() {
			synchronized (ConnectionRecovery.this) {
				return new ConnectionRecoveryObservation(state.generation, this);
			}
		}

		@Override
		public void close() {
			synchronized (ConnectionRecovery.this) {
				closed = true;
				if (state instanceof ConnectionRecheckInProgress && ((ConnectionRecheckInProgress) state).owner == this) {
					state = new ConnectionFailureRecorded(state.generation, state.incident());
				}
			}
		}
	}
}
