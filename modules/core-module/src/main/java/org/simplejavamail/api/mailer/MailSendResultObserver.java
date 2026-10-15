package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Executor;

/**
 * Receives one final result for each individual email send handled by a configured {@link Mailer}. Use it to record successes and failures,
 * inspect SMTP acceptance facts, or understand where a slow send spent its time.
 * <p>
 * By default, your callback runs on the thread completing the send, before the caller sees completion. Keep it quick, or use the builder overload
 * that takes an executor to process results separately. That overload submits the notification before completing the send, without waiting for
 * an asynchronously executed callback. Different sends can notify you concurrently or out of order, so your observer must be thread-safe.
 * <p>
 * If your callback throws a {@link RuntimeException}, it is logged and ignored: recording the result cannot change that result or interfere
 * with transport cleanup.
 * <p>
 * Registering this observer also gives you {@link MailSendOutcome#getDiagnostics() timing measurements} for the send, without another setting
 * or an extra read of the message. These explain the time spent preparing, scheduling, acquiring a connection, preparing MIME, submitting and
 * cleaning up. Measurement stops before your observer is called or handed to an executor, so callback work is not included.
 * Unlike a separate connection probe, these measurements describe what happened during this particular send.
 *
 * @see MailerGenericBuilder#withMailSendResultObserver(MailSendResultObserver)
 * @see MailerGenericBuilder#withMailSendResultObserver(MailSendResultObserver, Executor)
 */
@FunctionalInterface
public interface MailSendResultObserver {

	/**
	 * Receives the immutable final outcome of one email send, including any failover or retries within that send.
	 *
	 * @param outcome Completed send outcome.
	 */
	void onMailSendCompleted(@NotNull MailSendOutcome outcome);
}
