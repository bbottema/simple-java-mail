package org.simplejavamail.api.mailer.spi;

import jakarta.mail.Address;
import jakarta.mail.SendFailedException;

/**
 * An adapter or connection capability mismatch detected before MAIL FROM. No message was submitted and the transport remains reusable.
 * Adapters must not use this for SMTP command failures or after submission has started: those require normal failure/acceptance reporting.
 */
public final class MailTransportCompatibilityException extends SendFailedException {

	private static final long serialVersionUID = 1L;

	public MailTransportCompatibilityException(final String message) {
		super(message);
	}

	/** Records a defensive copy of the supplied addresses as valid recipients that were not submitted. */
	public MailTransportCompatibilityException(final String message, final Address[] unsentRecipients) {
		super(message, null, null, unsentRecipients.clone(), null);
	}
}
