package org.simplejavamail.internal.modules;

import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
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
	 * @param email The Email from which the MimeMessage was produced. Used to take fixed Message-ID from, if applicable.
	 * @param messageToSign The message to be signed when sent.
	 * @param dkimConfig    The {@link DkimConfig} containing all DKIM signing details
	 * @param fromRecipient The "From" recipient to be used as identity
	 * @return The original mime message wrapped in a new one that performs signing when sent.
	 */
	MimeMessage signMessageWithDKIM(@NotNull Email email, @NotNull MimeMessage messageToSign, @NotNull DkimConfig dkimConfig, @NotNull Recipient fromRecipient);

	/**
	 * @return Whether the email has been properly wrapped in a MimeMessage subtype that overrides Message-ID. This is to
	 * make sure we never send an email without making sure the Message-ID is properly customized (using
	 * {@link org.simplejavamail.api.email.EmailPopulatingBuilder#fixingMessageId(String)}.
	 */
    boolean isMessageIdFixingMessage(MimeMessage message);
}