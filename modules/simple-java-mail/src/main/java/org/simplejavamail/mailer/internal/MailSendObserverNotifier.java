package org.simplejavamail.mailer.internal;

import lombok.Builder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSendResultObserver;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSendProgressObserver;
import org.simplejavamail.internal.util.concurrent.MailSendControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Owns independent result and progress registrations. Only final-result callbacks participate in the existing send-completion ordering.
 */
final class MailSendObserverNotifier {

	private static final Logger LOGGER = LoggerFactory.getLogger(MailSendObserverNotifier.class);

	@Nullable private final MailSendResultObserver mailSendResultObserver;
	private final boolean loggingOnly;
	@Nullable private final Executor observerExecutor;
	@Nullable private final MailSendProgressDispatcher progressDispatcher;

	MailSendObserverNotifier(@Nullable final MailSendResultObserver mailSendResultObserver, final boolean loggingOnly) {
		this(mailSendResultObserver, null, loggingOnly);
	}

	MailSendObserverNotifier(@Nullable final MailSendResultObserver mailSendResultObserver, @Nullable final Executor observerExecutor, final boolean loggingOnly) {
		this(mailSendResultObserver, observerExecutor, loggingOnly, null, null);
	}

	@Builder
	private MailSendObserverNotifier(@Nullable final MailSendResultObserver mailSendResultObserver, @Nullable final Executor observerExecutor,
			final boolean loggingOnly, @Nullable final MailSendProgressObserver progressObserver, @Nullable final Executor progressExecutor) {
		this.mailSendResultObserver = mailSendResultObserver;
		this.observerExecutor = observerExecutor;
		this.loggingOnly = loggingOnly;
		this.progressDispatcher = progressObserver == null ? null : new MailSendProgressDispatcher(progressObserver, progressExecutor);
	}

	@NotNull
	MailSendAttempt beginAttempt(@NotNull final Email email) {
		return beginAttempt(email, null);
	}

	@NotNull
	MailSendAttempt beginAttempt(@NotNull final Email email, @Nullable final MailSendControl control) {
		if (mailSendResultObserver == null && progressDispatcher == null) {
			return MailSendAttempt.unobserved();
		}
		return MailSendAttempt.observed(this, email.getId(), Instant.now(), loggingOnly, control);
	}

	boolean observesResults() {
		return mailSendResultObserver != null;
	}

	@Nullable
	MailSendProgressRecorder progressRecorder(final UUID observationId, @Nullable final String initialMessageId) {
		return progressDispatcher == null ? null : new MailSendProgressRecorder(progressDispatcher, observationId, initialMessageId, System::nanoTime);
	}

	void closeProgress() {
		if (progressDispatcher != null) {
			progressDispatcher.close();
		}
	}

	void notifyCompletion(@NotNull final MailSendOutcome outcome) {
		if (mailSendResultObserver == null) {
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
			if (mailSendResultObserver != null) {
				mailSendResultObserver.onMailSendCompleted(outcome);
			}
		} catch (RuntimeException observerFailure) {
			LOGGER.warn("Mail send observer failed; ignoring observer exception", observerFailure);
		}
	}
}
