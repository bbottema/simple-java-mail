package org.simplejavamail.mailer.internal;

import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.internal.batchsupport.SelectedPoolTransport;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.internal.util.concurrent.MailSendControl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

/** One finite connection-routing operation. It never calls another Mailer's public send API and never catches submission failures. */
final class MailConnectionRoute<T> {

	private final MailerImpl initiator;
	private final MailSendDiagnosticsRecorder diagnostics;
	private final DestinationOperation<T> destinationOperation;
	private final MailSendControl control;
	private final List<MailerImpl> routeStages = new ArrayList<>();
	private final List<MailerImpl> unavailableDestinations = new ArrayList<>();
	private final Map<UUID, List<SelectedPoolTransport>> candidateGroups;
	private final Set<Object> visitedCandidates = Collections.newSetFromMap(new IdentityHashMap<>());
	@Nullable private ConnectionSetupFailure lastFailure;
	private int failedAcquisitions;
	private int cachedSkips;

	MailConnectionRoute(final MailerImpl initiator, final MailSendDiagnosticsRecorder diagnostics, final MailSendControl control,
			final DestinationOperation<T> operation) {
		this(initiator, diagnostics, control, operation, new HashMap<>());
	}

	MailConnectionRoute(final MailerImpl initiator, final MailSendDiagnosticsRecorder diagnostics, final MailSendControl control,
			final DestinationOperation<T> operation, final Map<UUID, List<SelectedPoolTransport>> groups) {
		this.initiator = initiator;
		this.diagnostics = diagnostics;
		this.control = control;
		this.destinationOperation = operation;
		this.candidateGroups = groups;
	}

	T execute() {
		return visitStage(initiator, null);
	}

	private T visitStage(final MailerImpl stage, @Nullable final FailoverPolicy inheritedPolicy) {
		control.checkStopped();
		if (routeStages.contains(stage)) {
			throw new IllegalStateException("The failover route returned to a Mailer already visited by this send. No message was replayed.");
		}
		routeStages.add(stage);
		final T retained = destinationOperation.retainAtRouteStage(stage);
		if (retained != null) {
			// Its retained scope already admitted this work, including when a participant has since begun graceful shutdown.
			return retained;
		}
		return stage.withDelegatedOperation(initiator, () -> visitStageAfterLifecycleAdmission(stage, inheritedPolicy));
	}

	private T visitStageAfterLifecycleAdmission(final MailerImpl stage, @Nullable final FailoverPolicy inheritedPolicy) {
		final FailoverPolicy configuredPolicy = stage.getOperationalConfig().getFailoverPolicy();
		final FailoverPolicy effectivePolicy = configuredPolicy != null ? configuredPolicy : inheritedPolicy;
		final T result = visitCandidateGroup(stage, effectivePolicy);
		if (result != null) {
			return result;
		}
		if (stage.failoverMailer() != null) {
			return visitStage(stage.failoverMailer(), effectivePolicy);
		}
		throw connectionRouteExhausted();
	}

	@Nullable
	private T visitCandidateGroup(final MailerImpl stage, @Nullable final FailoverPolicy policy) {
		for (SelectedPoolTransport selection : candidatesFor(stage, policy != null)) {
			final T result = visitSelectedCandidateOnce(stage, selection, policy);
			if (result != null) {
				return result;
			}
		}
		return null;
	}

	@Nullable
	private T visitSelectedCandidateOnce(final MailerImpl stage, @Nullable final SelectedPoolTransport selection,
			@Nullable final FailoverPolicy policy) {
		final MailerImpl destination = selection == null ? stage : MailerImpl.selectedOwner(selection.getSession());
		// Pinned pool registrations remain distinct even if their displayed host or configured name is equal.
		if (!visitedCandidates.add(selection == null ? destination : selection)) {
			return null;
		}
		return visitCandidate(destination, selection, policy);
	}

	private List<SelectedPoolTransport> candidatesFor(final MailerImpl stage, final boolean failoverEnabled) {
		if (!stage.usesSmtpConnection()) {
			return Collections.singletonList(null);
		}
		final UUID clusterKey = stage.getOperationalConfig().getClusterKey();
		return candidateGroups.computeIfAbsent(clusterKey, ignored -> stage.connectionCandidates(control, failoverEnabled));
	}

	@Nullable
	private T visitCandidate(final MailerImpl destination, @Nullable final SelectedPoolTransport selection,
			@Nullable final FailoverPolicy policy) {
		control.checkStopped();
		return destination.withDelegatedOperation(initiator, () -> {
			try (ConnectionRecovery.Visit visit = destination.definition().recovery.openVisit(policy);
					 MailSendControl.Registration ignored = control.onStop(visit::close)) {
				if (!visit.shouldAttemptConnection) {
					recordCachedSkip(destination, visit.cachedFailure);
					return null;
				}
				return tryConnectionAttempts(destination, selection, policy);
			}
		});
	}

	private void recordCachedSkip(final MailerImpl destination, final ConnectionSetupFailure failure) {
		lastFailure = failure;
		cachedSkips++;
		unavailableDestinations.add(destination);
		if (diagnostics.isObserved()) {
			diagnostics.skippedConnection(destination.definition().name, destination.logicalHost(), destination.logicalPort(),
					"Skipped after an earlier connection failure; the next recheck is not due or is already running.");
		}
	}

	@Nullable
	private T tryConnectionAttempts(final MailerImpl destination, @Nullable final SelectedPoolTransport selection,
			@Nullable final FailoverPolicy policy) {
		final int maximumConnectionAttempts = policy == null ? 1 : policy.getPrimaryConnectionAttempts();
		for (int attemptNumber = 0; attemptNumber < maximumConnectionAttempts; attemptNumber++) {
			if (attemptNumber > 0) {
				waitBeforeRetry(policy.getPrimaryConnectionRetryDelay());
			}
			control.checkStopped();
			recordFailoverContinuation();
			try {
				return destinationOperation.execute(destination, selection, routeStages);
			} catch (ConnectionSetupFailure failure) {
				verifySafeFailoverContinuation(failure, policy);
				lastFailure = failure;
				failedAcquisitions++;
			}
		}
		unavailableDestinations.add(destination);
		return null;
	}

	private void recordFailoverContinuation() {
		diagnostics.continuingAfterConnectionFailure();
		// A routing remedy is meaningful only when another destination is actually about to be used, not at exhaustion.
		unavailableDestinations.forEach(previous -> previous.connectionObserver().reportFailoverContinuation());
		unavailableDestinations.clear();
	}

	private void verifySafeFailoverContinuation(final ConnectionSetupFailure failure, @Nullable final FailoverPolicy policy) {
		diagnostics.failedConnectionStep(failure.explanation());
		control.checkStopped();
		if (failure.hasUnresolvedCleanup()) {
			throw new MailerException("The SMTP connection failed, and cleaning it up also failed. Failover has stopped so another "
					+ "connection isn't started while the first one's cleanup is unresolved. Check the cause and its suppressed cleanup failure.", failure);
		}
		if (destinationOperation.hasSubmissionStarted() || Thread.currentThread().isInterrupted() || policy == null || !failure.allowsFailover(policy)) {
			throw failure;
		}
	}

	private void waitBeforeRetry(final Duration delay) {
		final long nanos = delay.toNanos();
		final long waitStartedAt = System.nanoTime();
		final Thread waitingThread = Thread.currentThread();
		try (MailSendControl.Registration ignored = control.onStop(() -> LockSupport.unpark(waitingThread))) {
			long remainingNanos = nanos;
			while (remainingNanos > 0) {
				control.checkStopped();
				if (waitingThread.isInterrupted()) {
					throw new MailerException("Interrupted while waiting to retry an SMTP connection; no message was submitted.");
				}
				LockSupport.parkNanos(this, Math.min(remainingNanos, control.remainingNanos()));
				remainingNanos = nanos - (System.nanoTime() - waitStartedAt);
			}
		}
	}

	private MailerException connectionRouteExhausted() {
		return new MailerException("No configured SMTP destination could provide a usable connection. This send had " + failedAcquisitions
				+ " failed connection acquisition(s) and skipped " + cachedSkips + " destination(s) with an earlier failure awaiting recheck. "
				+ "No message was submitted. Check the final cause and the configured failover destinations.",
				lastFailure);
	}

	@FunctionalInterface
	interface DestinationOperation<T> {
		T execute(MailerImpl destination, @Nullable SelectedPoolTransport selected, List<MailerImpl> routeStages);

		/** A retained batch connection stays in use when the earlier route stages have not recovered. */
		@Nullable
		default T retainAtRouteStage(final MailerImpl stage) {
			return null;
		}

		default boolean hasSubmissionStarted() {
			return false;
		}
	}
}
