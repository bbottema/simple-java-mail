package org.simplejavamail.mailer.internal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;

/**
 * Carries one prepared email and its observation state across the optional asynchronous scheduling boundary.
 */
final class PreparedMailSend {

	@NotNull private final Email email;
	@NotNull private final MailSendAttempt mailSendAttempt;
	@Nullable private MailSubmissionReceipt submissionReceipt;

	PreparedMailSend(@NotNull final Email email, @NotNull final MailSendAttempt mailSendAttempt) {
		this.email = email;
		this.mailSendAttempt = mailSendAttempt;
	}

	@NotNull
	Email getEmail() {
		return email;
	}

	void markStarted() {
		mailSendAttempt.started();
	}

	void completeSuccessfully(@NotNull final MailSubmissionReceipt submissionReceipt) {
		this.submissionReceipt = submissionReceipt;
		mailSendAttempt.completeSuccessfully(submissionReceipt);
	}

	void completeWithFailure(@NotNull final Throwable failure) {
		mailSendAttempt.completeWithFailure(failure);
	}

	@NotNull
	MailSubmissionReceipt getSubmissionReceipt() {
		if (submissionReceipt == null) {
			throw new IllegalStateException("No submission receipt available; mail send has not completed successfully");
		}
		return submissionReceipt;
	}
}
