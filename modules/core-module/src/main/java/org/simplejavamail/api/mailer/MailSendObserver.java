package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Executor;

/**
 * Receives one terminal outcome for each individual email send attempt handled by a configured {@link Mailer}.
 * <p>
 * By default the observer runs inline before the send completes. The executor-taking builder overload instead hands it to an application-owned
 * executor before completing the send, without waiting for the callback. Different sends may notify concurrently and no ordering is guaranteed.
 * Keep implementations thread-safe; inline work delays completion, while executor-backed work follows that executor's dispatch policy.
 * <p>
 * A runtime exception thrown by an observer is logged and ignored. It never changes the send result or transport cleanup behavior.
 * <p>
 * Observed attempts automatically include {@link MailSendOutcome#getDiagnostics() actual-send measurements}. These end before observer dispatch;
 * they explain preparation, scheduling, connection acquisition, MIME preparation, submission and cleanup without reading the message again.
 * Measurements describe the particular send, unlike a separate connection probe. No extra diagnostics setting is needed.
 *
 * @see MailerGenericBuilder#withMailSendObserver(MailSendObserver)
 * @see MailerGenericBuilder#withMailSendObserver(MailSendObserver, Executor)
 */
@FunctionalInterface
public interface MailSendObserver {

	/**
	 * Handles the immutable terminal outcome of one email send attempt.
	 *
	 * @param outcome Completed send outcome.
	 */
	void onMailSendCompleted(@NotNull MailSendOutcome outcome);
}
