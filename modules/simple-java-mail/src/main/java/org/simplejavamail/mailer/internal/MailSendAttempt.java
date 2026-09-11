package org.simplejavamail.mailer.internal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSendCancelledException;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSendTimeoutException;
import org.simplejavamail.api.mailer.MailSubmissionException;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.internal.util.concurrent.MailSendControl;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Objects.requireNonNull;

/**
 * Tracks the observable state of one logical email send attempt without exposing the email or transport lifecycle publicly.
 */
final class MailSendAttempt {

	@Nullable private final MailSendObserverNotifier notifier;
	@Nullable private final String initialMessageId;
	@Nullable private final Instant requestedAt;
	private final boolean loggingOnly;
	@Nullable private Instant readyAt;
	@Nullable private Instant startedAt;
	@Nullable private Email effectiveEmail;
	private final AtomicBoolean completed = new AtomicBoolean();
	@Nullable private MailSendControl control;

	private MailSendAttempt(@Nullable final MailSendObserverNotifier notifier,
			@Nullable final String initialMessageId,
			@Nullable final Instant requestedAt,
			final boolean loggingOnly, @Nullable final MailSendControl control) {
		this.notifier = notifier;
		this.initialMessageId = initialMessageId;
		this.requestedAt = requestedAt;
		this.loggingOnly = loggingOnly;
		this.control = control;
	}

	@NotNull
	static MailSendAttempt observed(@NotNull final MailSendObserverNotifier notifier,
			@Nullable final String initialMessageId,
			@NotNull final Instant requestedAt,
			final boolean loggingOnly, @Nullable final MailSendControl control) {
		return new MailSendAttempt(notifier, initialMessageId, requestedAt, loggingOnly, control);
	}

	@NotNull
	static MailSendAttempt unobserved() {
		return new MailSendAttempt(null, null, null, false, null);
	}

	void prepared(@NotNull final Email email) {
		if (notifier == null) {
			return;
		}
		effectiveEmail = email;
		readyAt = Instant.now();
	}

	void attachControl(@NotNull final MailSendControl control) {
		this.control = control;
	}

	void started() {
		if (notifier != null) {
			startedAt = Instant.now();
		}
	}

	void completeSuccessfully(@NotNull final MailSubmissionReceipt submissionReceipt) {
		complete(true, submissionReceipt, null);
	}

	void completeWithFailure(@NotNull final Throwable failure) {
		final MailSubmissionReceipt submissionReceipt = submissionReceiptFromStartedFailure(failure);
		complete(false, submissionReceipt, failure);
	}

	/**
	 * Preparation and scheduling failures must remain receipt-free, even if application code throws a prebuilt submission exception before execution.
	 */
	@Nullable
	private MailSubmissionReceipt submissionReceiptFromStartedFailure(@NotNull final Throwable failure) {
		if (startedAt != null && failure instanceof MailSendCancelledException) {
			return ((MailSendCancelledException) failure).getSubmissionReceipt().orElse(null);
		}
		if (startedAt != null && failure instanceof MailSendTimeoutException) {
			return ((MailSendTimeoutException) failure).getSubmissionReceipt().orElse(null);
		}
		return startedAt != null && failure instanceof MailSubmissionException
				? ((MailSubmissionException) failure).getSubmissionReceipt()
				: null;
	}

	private void complete(final boolean successful,
			@Nullable final MailSubmissionReceipt submissionReceipt,
			@Nullable final Throwable failure) {
		if (notifier == null || !completed.compareAndSet(false, true)) {
			return;
		}
		final MailSendOutcome outcome = new MailSendOutcome(initialMessageId, effectiveMessageId(submissionReceipt),
				requireNonNull(requestedAt, "requestedAt"), readyAt, startedAt, Instant.now(), successful, loggingOnly,
				submissionReceipt, failure);
		effectiveEmail = null;
		try (MailSendControl.DeadlinePause ignored = control == null ? null : control.pauseDeadline()) {
			notifier.notifyCompletion(outcome);
		}
	}

	@Nullable
	private String effectiveMessageId(@Nullable final MailSubmissionReceipt submissionReceipt) {
		if (submissionReceipt != null && submissionReceipt.getEmailId() != null) {
			return submissionReceipt.getEmailId();
		}
		return effectiveEmail != null ? effectiveEmail.getId() : null;
	}
}
