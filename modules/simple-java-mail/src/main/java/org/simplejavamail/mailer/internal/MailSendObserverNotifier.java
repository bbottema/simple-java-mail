package org.simplejavamail.mailer.internal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSendObserver;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Owns the application callback boundary for terminal mail-send observations.
 */
final class MailSendObserverNotifier {

	private static final Logger LOGGER = LoggerFactory.getLogger(MailSendObserverNotifier.class);

	@Nullable private final MailSendObserver mailSendObserver;
	private final boolean loggingOnly;

	MailSendObserverNotifier(@Nullable final MailSendObserver mailSendObserver, final boolean loggingOnly) {
		this.mailSendObserver = mailSendObserver;
		this.loggingOnly = loggingOnly;
	}

	@NotNull
	MailSendAttempt beginAttempt(@NotNull final Email email) {
		if (mailSendObserver == null) {
			return MailSendAttempt.unobserved();
		}
		return MailSendAttempt.observed(this, email.getId(), Instant.now(), loggingOnly);
	}

	void notifyCompletion(@NotNull final MailSendOutcome outcome) {
		if (mailSendObserver == null) {
			return;
		}

		try {
			mailSendObserver.onMailSendCompleted(outcome);
		} catch (final RuntimeException observerFailure) {
			LOGGER.warn("Mail send observer failed; ignoring observer exception", observerFailure);
		}
	}
}
