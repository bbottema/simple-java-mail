package org.simplejavamail.internal.modules;

import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DkimConfig;

/**
 * This interface only serves to hide the DKIM implementation behind an easy-to-load-with-reflection class.
 */
public interface DKIMModule {

	String NAME = "DKIM module";

	/**
	 * Primes the {@link MimeMessage} instance for signing with DKIM. The signing itself is performed by
	 * {@code org.simplejavamail.utils.mail.dkim.DkimMessage} and {@code org.simplejavamail.utils.mail.dkim.DkimSigner}
	 * during the physical sending of the message.
	 *
	 * @param messageToSign  The message to be signed when sent.
	 * @param signingDetails The {@link DkimConfig} that contains the relevant signing information
	 * @param fromRecipient  The "From" recipient to be used as identity
	 *
	 * @return The original mime message wrapped in a new one that performs signing when sent.
	 */
	MimeMessage signMessageWithDKIM(@NotNull MimeMessage messageToSign, @NotNull DkimConfig signingDetails, @NotNull Recipient fromRecipient);
}