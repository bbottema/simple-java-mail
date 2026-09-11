package org.simplejavamail.mailer.internal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSendObserver;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.internal.util.concurrent.MailSendControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.Executor;

/**
 * Owns the application callback boundary for terminal mail-send observations.
 */
final class MailSendObserverNotifier {

	private static final Logger LOGGER = LoggerFactory.getLogger(MailSendObserverNotifier.class);

	@Nullable private final MailSendObserver mailSendObserver;
	private final boolean loggingOnly;
	@Nullable private final Executor observerExecutor;

	MailSendObserverNotifier(@Nullable final MailSendObserver mailSendObserver, final boolean loggingOnly) {
		this(mailSendObserver, null, loggingOnly);
	}

	MailSendObserverNotifier(@Nullable final MailSendObserver mailSendObserver, @Nullable final Executor observerExecutor, final boolean loggingOnly) {
		this.mailSendObserver = mailSendObserver;
		this.observerExecutor = observerExecutor;
		this.loggingOnly = loggingOnly;
	}

	@NotNull
	MailSendAttempt beginAttempt(@NotNull final Email email) {
		return beginAttempt(email, null);
	}

	@NotNull
	MailSendAttempt beginAttempt(@NotNull final Email email, @Nullable final MailSendControl control) {
		if (mailSendObserver == null) {
			return MailSendAttempt.unobserved();
		}
		return MailSendAttempt.observed(this, email.getId(), Instant.now(), loggingOnly, control);
	}

	void notifyCompletion(@NotNull final MailSendOutcome outcome) {
		if (mailSendObserver == null) {
			return;
		}

		final Runnable notification = () -> invokeObserver(outcome);
		if (observerExecutor == null) {
			notification.run();
		} else {
			try {
				observerExecutor.execute(notification);
			} catch (RuntimeException rejection) {
				LOGGER.warn("Mail send observer notification was not handed off; leaving the send result unchanged", rejection);
			}
		}
	}

	private void invokeObserver(final MailSendOutcome outcome) {
		try {
			if (mailSendObserver != null) {
				mailSendObserver.onMailSendCompleted(outcome);
			}
		} catch (RuntimeException observerFailure) {
			LOGGER.warn("Mail send observer failed; ignoring observer exception", observerFailure);
		}
	}
}
