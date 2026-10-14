package org.simplejavamail.mailer.internal;

import org.simplejavamail.internal.util.concurrent.MailSendControl;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Synchronous and deliberately retained scopes wait on their existing thread, and wake promptly on cancellation or expiry. */
final class MailSendRetryWait {

	private MailSendRetryWait() { }

	static void await(final Duration delay, final MailSendControl control) {
		final CountDownLatch stopped = new CountDownLatch(1);
		try (MailSendControl.Registration ignored = control.onStop(stopped::countDown)) {
			control.checkStopped();
			stopped.await(Math.min(delay.toNanos(), control.remainingNanos()), TimeUnit.NANOSECONDS);
			control.checkStopped();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new MailerException("The thread waiting to retry this email was interrupted. No further submission was started.", interrupted);
		}
	}
}
