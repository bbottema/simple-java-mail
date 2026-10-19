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
	 * Validates the DKIM choices and signs the finalized message bytes. Configuration holders retain supplied values;
	 * this module owns rules such as requiring the From header in the signature and compatibility with the signing key.
	 *
	 * @param email The Email from which the MimeMessage was produced.
	 * @param messageToSign The message to finalize and sign.
	 * @param dkimConfig    The {@link DkimConfig} containing all DKIM signing details
	 * @param fromRecipient The "From" recipient to be used as identity
	 * @return A message retaining the finalized, signed bytes.
	 */
	MimeMessage signMessageWithDKIM(@NotNull Email email, @NotNull MimeMessage messageToSign, @NotNull DkimConfig dkimConfig, @NotNull Recipient fromRecipient);

}
