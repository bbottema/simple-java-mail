package org.simplejavamail.internal.util;

import jakarta.mail.MessagingException;

/** Provider evidence that SMTP returned 421 during connection setup, before any message submission began. */
public final class SmtpServiceUnavailableException extends MessagingException {
	private static final long serialVersionUID = 1L;

	public SmtpServiceUnavailableException() {
		super("The SMTP server returned 421 while opening the connection; its service is temporarily unavailable.");
	}
}
