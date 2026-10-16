package org.simplejavamail.mailer.internal;

import lombok.Builder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSendResultObserver;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSendProgressObserver;
import org.simplejavamail.internal.util.concurrent.MailSendControl;
import org.simplejavamail.internal.telemetry.MailSendTelemetryBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Owns independent result, progress and telemetry registrations. Only final-result callbacks participate in send-completion ordering.
 * Telemetry is recorded at the terminal boundary, not through delivery of either application callback.
 */
final class MailSendObserverNotifier {

	private static final Logger LOGGER = LoggerFactory.getLogger(MailSendObserverNotifier.class);

	@Nullable private final MailSendResultObserver resultObserver;
	private final boolean loggingOnly;
	@Nullable private final Executor resultExecutor;
	@Nullable private final MailSendProgressDispatcher progressDispatcher;
	@Nullable private final MailSendTelemetryBridge.Runtime telemetry;

	MailSendObserverNotifier(@Nullable final MailSendResultObserver resultObserver, final boolean loggingOnly) {
		this(resultObserver, null, loggingOnly);
	}

	MailSendObserverNotifier(@Nullable final MailSendResultObserver resultObserver, @Nullable final Executor resultExecutor, final boolean loggingOnly) {
		this(resultObserver, resultExecutor, loggingOnly, null, null, null);
	}

	@Builder
	private MailSendObserverNotifier(@Nullable final MailSendResultObserver resultObserver, @Nullable final Executor resultExecutor,
			final boolean loggingOnly, @Nullable final MailSendProgressObserver progressObserver, @Nullable final Executor progressExecutor,
			@Nullable final MailSendTelemetryBridge.Runtime telemetry) {
		this.resultObserver = resultObserver;
		this.resultExecutor = resultExecutor;
		this.loggingOnly = loggingOnly;
		this.progressDispatcher = progressObserver == null ? null : new MailSendProgressDispatcher(progressObserver, progressExecutor);
		this.telemetry = telemetry;
	}

	@NotNull
	MailSendAttempt beginAttempt(@NotNull final Email email) {
		return beginAttempt(email, null);
	}

	@NotNull
	MailSendAttempt beginAttempt(@NotNull final Email email, @Nullable final MailSendControl control) {
		if (resultObserver == null && progressDispatcher == null && telemetry == null) {
			return MailSendAttempt.unobserved();
		}
		return MailSendAttempt.observed(this, email.getId(), Instant.now(), loggingOnly, control);
	}

	@Nullable
	MailSendTelemetryBridge.CapturedContext captureTelemetryContext() {
		return MailSendTelemetryRecorder.capture(telemetry);
	}

	boolean observesResults() {
		return resultObserver != null;
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
		if (resultObserver == null) {
			return;
		}

		final Runnable notification = () -> invokeObserver(outcome);
		if (resultExecutor == null) {
			notification.run();
		} else {
			try {
				resultExecutor.execute(notification);
			} catch (RuntimeException rejection) {
				LOGGER.warn("Mail send observer notification was not handed off; leaving the send result unchanged", rejection);
			}
		}
	}

	private void invokeObserver(final MailSendOutcome outcome) {
		try {
			if (resultObserver != null) {
				resultObserver.onMailSendCompleted(outcome);
			}
		} catch (RuntimeException observerFailure) {
			LOGGER.warn("Mail send observer failed; ignoring observer exception", observerFailure);
		}
	}
}
