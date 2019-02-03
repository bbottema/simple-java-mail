package org.simplejavamail.internal.modules;

import org.simplejavamail.api.email.Email;

import javax.mail.internet.MimeMessage;

/**
 * This interface only serves to hide the DKIM implementation behind an easy-to-load-with-reflection class.
 */
public interface DKIMModule {
	
	/**
	 * Primes the {@link MimeMessage} instance for signing with DKIM. The signing itself is performed by {@link
	 * net.markenwerk.utils.mail.dkim.DkimMessage} and {@link net.markenwerk.utils.mail.dkim.DkimSigner} during the physical sending of the message.
	 *
	 * @param messageToSign                 The message to be signed when sent.
	 * @param emailContainingSigningDetails The {@link Email} that contains the relevant signing information
	 *
	 * @return The original mime message wrapped in a new one that performs signing when sent.
	 */
	MimeMessage signMessageWithDKIM(MimeMessage messageToSign, Email emailContainingSigningDetails);
}
