package org.simplejavamail.api.internal.batchsupport;

import org.simplejavamail.api.mailer.MailFailureKind;

/** Retains upstream evidence that a claim failed during physical connection setup, not because the pool was busy. */
public final class PoolConnectionSetupException extends org.simplejavamail.MailException {
	private static final long serialVersionUID = 1L;

	public PoolConnectionSetupException(final Throwable cause) {
		super(MailFailureKind.CONNECTION,
				"The selected pool could not establish a usable SMTP connection.", cause, null);
	}
}
