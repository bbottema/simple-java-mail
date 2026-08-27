package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.NotNull;

/**
 * Receives one terminal outcome for each individual email send attempt handled by a configured {@link Mailer}.
 * <p>
 * The observer runs inline on the thread completing the send. Implementations must therefore be thread-safe, quick and non-blocking. Different
 * asynchronous sends may notify concurrently and no ordering is guaranteed across sends.
 * <p>
 * A runtime exception thrown by an observer is logged and ignored. It never changes the send result or transport cleanup behavior.
 *
 * @see MailerGenericBuilder#withMailSendObserver(MailSendObserver)
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
