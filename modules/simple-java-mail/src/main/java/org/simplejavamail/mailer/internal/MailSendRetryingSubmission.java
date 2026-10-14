package org.simplejavamail.mailer.internal;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.internal.batchsupport.SendingAllowance;
import org.simplejavamail.api.mailer.MailSubmissionException;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.MailSendCancelledException;
import org.simplejavamail.api.mailer.MailSendTimeoutException;
import org.simplejavamail.api.mailer.config.MailSendRetryPolicy;
import org.simplejavamail.api.mailer.spi.PreparedMail;
import org.simplejavamail.internal.util.concurrent.MailSendControl;
import org.simplejavamail.mailer.internal.util.AcquiredMailTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.Supplier;
import static java.util.Objects.requireNonNull;

/** Owns replay content and the refusal between physical invocations. It never performs another route selection or reapplies templates. */
final class MailSendRetryingSubmission implements MailSendOperation.RetryingWork<MailSubmissionReceipt> {

	private static final Logger LOGGER = LoggerFactory.getLogger(MailSendRetryingSubmission.class);
	interface Connection extends AutoCloseable {
		<T> T withinOwners(Supplier<T> work);
		AcquiredMailTransport current();
		SendingAllowance.Reservation reserve(Address[] recipients) throws MessagingException;
		void reacquire(Address[] recipients);
		default boolean canRetryAfter(final MailSubmissionReceipt receipt) { return true; }
		void releaseAttempt() throws MessagingException;
		@Override void close() throws MessagingException;
	}

	private final MailerImpl destination;
	private final MailSendRetryPolicy policy;
	private final MailSendControl control;
	private final MailSendDiagnosticsRecorder diagnostics;
	private final MailSendReplayBudget budget;
	private final Connection connection;
	@Nullable private Email email;
	@Nullable private MailSendReplaySnapshot replay;
	@Nullable private RuntimeException lastFailure;
	@Nullable private String messageId;
	@Nullable private String subject;
	private int invocations;
	private boolean closed;

	MailSendRetryingSubmission(final MailerImpl destination, final Email email, final MailSendControl control,
			final MailSendDiagnosticsRecorder diagnostics, final MailSendReplayBudget budget, final Connection connection) {
		this.destination = destination;
		this.email = email;
		this.control = control;
		this.diagnostics = diagnostics;
		this.budget = budget;
		this.connection = connection;
		policy = requireNonNull(destination.getOperationalConfig().getMailSendRetryPolicy(), "selected Mailer retry policy");
		diagnostics.enableSubmissionHistory();
	}

	@Override
	public MailSendOperation.RetryResult<MailSubmissionReceipt> execute() {
		return connection.withinOwners(this::executeOnOwners);
	}

	private MailSendOperation.RetryResult<MailSubmissionReceipt> executeOnOwners() {
		try {
			control.checkStopped();
			if (replay == null) {
				prepareReplayOnce();
			} else {
				connection.reacquire(replay.prepared().getRecipients());
			}
			diagnostics.connectionAcquired(connection.current().getTransport());
			return invokeAndReleaseAttempt();
		} catch (MessagingException failure) {
			throw terminal(new MailerException("Couldn't prepare or finish this email's retry-enabled submission. "
					+ "No automatic retry was started for this failure.", failure));
		} catch (RuntimeException failure) {
			throw terminal(failure);
		}
	}

	private void prepareReplayOnce() throws MessagingException {
		diagnostics.connectionAcquired(connection.current().getTransport());
		diagnostics.startMimePreparation();
		final PreparedMail prepared = SessionBasedEmailToMimeMessageConverter.convertAndLogPreparedMail(destination.getSession(), email);
		replay = MailSendReplaySnapshot.capture(connection.current().getTransport(), prepared, budget, control);
		messageId = replay.prepared().getMimeMessage().getMessageID();
		subject = email.getSubject();
		email = null;
	}

	private MailSendOperation.RetryResult<MailSubmissionReceipt> invokeAndReleaseAttempt() throws MessagingException {
		final PreparedMail prepared = replay.prepared();
		final SendingAllowance.Reservation reservation = connection.reserve(prepared.getRecipients());
		final int beforeInvocation = invocations;
		final SubmissionAttempt attempt = invokeProvider(prepared, reservation);
		recordInvokedSubmission(attempt, beforeInvocation);
		final AttemptCleanup cleanup = retireAttempt(reservation, attempt.refusal);
		checkForNewCleanupFailure(attempt.refusal, cleanup);
		return retryOrComplete(attempt, cleanup.successful);
	}

	private SubmissionAttempt invokeProvider(final PreparedMail prepared, final SendingAllowance.Reservation reservation) {
		MailSubmissionReceipt result = null;
		Throwable refusal = null;
		Duration nextDelay = null;
		try {
			result = connection.current().sendPrepared(prepared, messageId, subject, control, diagnostics, reservation, () -> invocations++);
		} catch (MessagingException failure) {
			refusal = control.translateFailure(new MailerException("The provider could not finish this submission. "
					+ "Check the cause; no automatic replay was started.", failure));
			lastFailure = (RuntimeException) refusal;
		} catch (RuntimeException | Error failure) {
			refusal = failure;
			if (failure instanceof RuntimeException) { lastFailure = (RuntimeException) failure; }
			if (failure instanceof RuntimeException && canRetry((RuntimeException) failure)) {
				// This refusal may be intermediate. Freezing it on the logical control now would prevent a later successful attempt.
				// Cleanup and retry admission still have to succeed before another invocation is allowed.
				nextDelay = MailSendRetryDelay.afterAttempt(policy, invocations);
			} else if (failure instanceof RuntimeException) {
				refusal = control.translateFailure((RuntimeException) failure);
			}
		}
		return new SubmissionAttempt(result, refusal, nextDelay);
	}

	private void recordInvokedSubmission(final SubmissionAttempt attempt, final int beforeInvocation) {
		if (invocations != beforeInvocation) {
			diagnostics.submissionStep(invocations, submissionReceipt(attempt.result, attempt.refusal), null, attempt.refusal != null);
		}
	}

	private static void checkForNewCleanupFailure(@Nullable final Throwable refusal, final AttemptCleanup cleanup) {
		if (cleanup.failure != refusal) {
			if (cleanup.failure instanceof Error) { throw (Error) cleanup.failure; }
			throw new MailerException("The SMTP attempt finished, but its connection or sending-allowance cleanup failed. "
					+ "Check the submission receipt before deciding whether to retry; it was not submitted again.", (Exception) cleanup.failure);
		}
	}

	private MailSendOperation.RetryResult<MailSubmissionReceipt> retryOrComplete(final SubmissionAttempt attempt, final boolean cleanupSuccessful) {
		// A failure retiring this invocation prevents replay, even when the SMTP refusal alone was eligible.
		if (cleanupSuccessful && attempt.nextDelay != null) {
			diagnostics.continuingAfterSubmissionRefusal();
			return MailSendOperation.RetryResult.retry(attempt.nextDelay);
		}
		if (attempt.refusal instanceof Error) { throw (Error) attempt.refusal; }
		if (attempt.refusal instanceof RuntimeException) { throw control.translateFailure((RuntimeException) attempt.refusal); }
		if (invocations > 1) { LOGGER.info("Automatic mail-send retry recovered after {} submission attempts.", invocations); }
		return MailSendOperation.RetryResult.completed(attempt.result);
	}

	/** Facts from one invocation; cleanup decides separately whether its selected retry delay may actually be used. */
	private static final class SubmissionAttempt {
		@Nullable private final MailSubmissionReceipt result;
		@Nullable private final Throwable refusal;
		@Nullable private final Duration nextDelay;

		private SubmissionAttempt(@Nullable final MailSubmissionReceipt result, @Nullable final Throwable refusal, @Nullable final Duration nextDelay) {
			this.result = result;
			this.refusal = refusal;
			this.nextDelay = nextDelay;
		}
	}

	@Nullable
	private static MailSubmissionReceipt submissionReceipt(@Nullable final MailSubmissionReceipt result, @Nullable final Throwable refusal) {
		if (result != null) { return result; }
		return refusal instanceof MailSubmissionException ? ((MailSubmissionException) refusal).getSubmissionReceipt() : null;
	}

	private AttemptCleanup retireAttempt(final SendingAllowance.Reservation reservation, @Nullable final Throwable refusal) {
		Throwable primary = refusal;
		boolean successful = true;
		try {
			connection.releaseAttempt();
		} catch (MessagingException | RuntimeException | Error cleanupFailure) {
			successful = false;
			primary = preservePrimary(primary, cleanupFailure);
		}
		try {
			reservation.close();
		} catch (RuntimeException | Error cleanupFailure) {
			successful = false;
			primary = preservePrimary(primary, cleanupFailure);
		}
		return new AttemptCleanup(primary, successful);
	}

	/** Retains cleanup success separately: a provider exception may already have unrelated suppressed details. */
	private static final class AttemptCleanup {
		@Nullable private final Throwable failure;
		private final boolean successful;
		private AttemptCleanup(@Nullable final Throwable failure, final boolean successful) { this.failure = failure; this.successful = successful; }
	}

	private static Throwable preservePrimary(@Nullable final Throwable primary, final Throwable cleanupFailure) {
		if (primary == null) { return cleanupFailure; }
		if (primary != cleanupFailure) { primary.addSuppressed(cleanupFailure); }
		return primary;
	}

	private boolean canRetry(final RuntimeException failure) {
		return failure instanceof MailSubmissionException
				&& hasAnotherInvocationAvailable()
				&& !isExecutionStopped()
				&& canReplayRefusal((MailSubmissionException) failure);
	}

	private boolean hasAnotherInvocationAvailable() {
		return invocations > 0 && invocations < policy.getMaximumAttempts();
	}

	private boolean isExecutionStopped() {
		return control.isStopRequested() || Thread.currentThread().isInterrupted();
	}

	private boolean canReplayRefusal(final MailSubmissionException failure) {
		return connection.canRetryAfter(failure.getSubmissionReceipt()) && MailSendRetryEligibility.isTemporaryWholeMessageRefusal(failure);
	}

	private RuntimeException terminal(final RuntimeException failure) {
		diagnostics.failed();
		if (hasStoppedAfterRefusal(failure)) {
			return control.stoppedFailure(lastFailure);
		}
		return control.translateFailure(failure);
	}

	private boolean hasStoppedAfterRefusal(final RuntimeException failure) {
		return (failure instanceof MailSendCancelledException || failure instanceof MailSendTimeoutException) && lastFailure != null;
	}

	private void logRetry(final Duration delay) {
		if (invocations == 1) {
			LOGGER.warn("Temporary SMTP refusal; retrying the same email after {} (at most {} attempts).", delay, policy.getMaximumAttempts());
		} else {
			LOGGER.debug("Temporary SMTP refusal on attempt {}; next retry after {}.", invocations, delay);
		}
	}

	@Override @Nullable public RuntimeException lastFailure() { return lastFailure; }

	/** Retained scopes intentionally wait on their existing thread; ordinary asynchronous sends use the operation's parking boundary instead. */
	MailSubmissionReceipt completeOnCurrentThread() {
		MailSendOperation.RetryResult<MailSubmissionReceipt> result = execute();
		while (result.nextDelay != null) {
			waitOnCurrentThread(result.nextDelay);
			result = execute();
		}
		return result.value;
	}

	private void waitOnCurrentThread(final Duration delay) {
		waiting();
		retryScheduled(delay);
		try { MailSendRetryWait.await(delay, control); }
		catch (RuntimeException failure) { throw control.isStopRequested() ? control.stoppedFailure(lastFailure) : failure; }
		resumed();
	}
	@Override public void waiting() { diagnostics.startRetryWait(); }
	@Override public void resumed() { diagnostics.retryWaitCompleted(); }
	@Override public void retryScheduled(final Duration delay) {
		diagnostics.retryScheduled(delay);
		logRetry(delay);
	}
	@Override public void failed() { diagnostics.failed(); }

	@Override
	public void close() {
		if (closed) { return; }
		closed = true;
		try {
			connection.close();
		} catch (MessagingException failure) {
			throw new MailerException("Couldn't finish the retry-enabled send's connection cleanup. "
					+ "Check the retained submission facts before retrying.", failure);
		} finally {
			if (replay != null) { replay.close(); replay = null; }
			email = null;
			lastFailure = null;
		}
	}
}
