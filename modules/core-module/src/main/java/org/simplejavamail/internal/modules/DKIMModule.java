package org.simplejavamail.internal.modules;

import jakarta.mail.internet.MimeMessage;
import org.simplejavamail.api.email.Email;

/**
 * This interface only serves to hide the DKIM implementation behind an easy-to-load-with-reflection class.
 */
public interface DKIMModule {

	String NAME = "DKIM module";

	/**
	 * Primes the {@link MimeMessage} instance for signing with DKIM. The signing itself is performed by
	 * {@code net.markenwerk.utils.mail.dkim.DkimMessage} and {@code net.markenwerk.utils.mail.dkim.DkimSigner}
	 * during the physical sending of the message.
	 *
	 * @param messageToSign                 The message to be signed when sent.
	 * @param emailContainingSigningDetails The {@link Email} that contains the relevant signing information
	 *
	 * @return The original mime message wrapped in a new one that performs signing when sent.
	 */
	MimeMessage signMessageWithDKIM(MimeMessage messageToSign, Email emailContainingSigningDetails);
}