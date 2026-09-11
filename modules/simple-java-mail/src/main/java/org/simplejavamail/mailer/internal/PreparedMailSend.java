package org.simplejavamail.mailer.internal;

import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;

/**
 * Carries one prepared email and its observation state across the optional asynchronous scheduling boundary.
 */
final class PreparedMailSend {

	@NotNull private final Email email;
	@NotNull private final MailSendAttempt mailSendAttempt;

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

}
