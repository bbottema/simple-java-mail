package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSender;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;

/** Guards callback-owned connection access without holding a monitor during sending or application code. */
final class ScopedMailSender implements MailSender {

	private final MailSender delegate;
	private final Thread callbackThread = Thread.currentThread();
	// Only the callback thread reads or changes this flag; other threads are rejected before accessing it.
	private boolean active = true;

	ScopedMailSender(final MailSender delegate) {
		this.delegate = delegate;
	}

	/** @see MailSender#sendMail(Email) */
	@Override
	public void sendMail(final Email email) {
		verifyUsable();
		delegate.sendMail(email);
	}

	/** @see MailSender#sendMailAndGetReceipt(Email) */
	@Override
	public MailSubmissionReceipt sendMailAndGetReceipt(final Email email) {
		verifyUsable();
		return delegate.sendMailAndGetReceipt(email);
	}

	private void verifyUsable() {
		verifyCallbackThread();
		if (!active) {
			throw new IllegalStateException("This withOpenConnection callback has ended, so its sender can no longer be used. "
					+ "Send through the Mailer or open a new withOpenConnection callback instead.");
		}
	}

	private void verifyCallbackThread() {
		if (Thread.currentThread() != callbackThread) {
			throw new IllegalStateException("Use the withOpenConnection sender only on the thread running its callback. "
					+ "Don't hand it to another thread; use mailer.async().sendMail(email) for independent asynchronous sends instead.");
		}
	}

	void endCallback() {
		verifyCallbackThread();
		active = false;
	}
}
