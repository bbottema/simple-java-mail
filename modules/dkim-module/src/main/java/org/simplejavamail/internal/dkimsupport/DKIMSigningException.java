package org.simplejavamail.internal.dkimsupport;

import org.simplejavamail.MailException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * This exception is used to communicate errors during signing with the DKIM library.
 */
@SuppressWarnings("serial")
class DKIMSigningException extends MailException {

	static final String ERROR_SIGNING_DKIM_INVALID_DOMAINKEY = "Error signing MimeMessage with DKIM";
	
	DKIMSigningException(@SuppressWarnings("SameParameterValue") @Nonnull final String message, @Nullable final Exception cause) {
		super(checkNonEmptyArgument(message, "message"), cause);
	}
}