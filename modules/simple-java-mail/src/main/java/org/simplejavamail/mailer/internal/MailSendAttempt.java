package org.simplejavamail.mailer.internal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSubmissionException;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;

import java.time.Instant;

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
	private boolean completed;

	private MailSendAttempt(@Nullable final MailSendObserverNotifier notifier,
			@Nullable final String initialMessageId,
			@Nullable final Instant requestedAt,
			final boolean loggingOnly) {
		this.notifier = notifier;
		this.initialMessageId = initialMessageId;
		this.requestedAt = requestedAt;
		this.loggingOnly = loggingOnly;
	}

	@NotNull
	static MailSendAttempt observed(@NotNull final MailSendObserverNotifier notifier,
			@Nullable final String initialMessageId,
			@NotNull final Instant requestedAt,
			final boolean loggingOnly) {
		return new MailSendAttempt(notifier, initialMessageId, requestedAt, loggingOnly);
	}

	@NotNull
	static MailSendAttempt unobserved() {
		return new MailSendAttempt(null, null, null, false);
	}

	void prepared(@NotNull final Email email) {
		if (notifier == null) {
			return;
		}
		effectiveEmail = email;
		readyAt = Instant.now();
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
		return startedAt != null && failure instanceof MailSubmissionException
				? ((MailSubmissionException) failure).getSubmissionReceipt()
				: null;
	}

	private void complete(final boolean successful,
			@Nullable final MailSubmissionReceipt submissionReceipt,
			@Nullable final Throwable failure) {
		if (notifier == null || completed) {
			return;
		}
		completed = true;
		final MailSendOutcome outcome = new MailSendOutcome(initialMessageId, effectiveMessageId(submissionReceipt),
				requireNonNull(requestedAt, "requestedAt"), readyAt, startedAt, Instant.now(), successful, loggingOnly,
				submissionReceipt, failure);
		notifier.notifyCompletion(outcome);
	}

	@Nullable
	private String effectiveMessageId(@Nullable final MailSubmissionReceipt submissionReceipt) {
		if (submissionReceipt != null && submissionReceipt.getEmailId() != null) {
			return submissionReceipt.getEmailId();
		}
		return effectiveEmail != null ? effectiveEmail.getId() : null;
	}
}
