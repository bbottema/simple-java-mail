package org.simplejavamail.api.mailer;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.config.OperationalConfig;

/**
 * By default, Simple Java Mail handles the ultimate connection and sending of emails. However, it is possible to replace this last step
 * with a custom implementation. Note this is not meant to 'override' the default behavior, but to provide a custom implementation for
 * specific use cases (for example sending using MailChimp or SendGrid, or logging to your own database).
 * <p>
 * The benefit of this is that Simple Java Mail acts as an accelerator, providing thread pool, applying email content-validation, address validations,
 * configuring a {@code Session} instance, producing a {@code MimeMessage}, all with full S/MIME, DKIM support and everything else.
 * <p>
 * <strong>Note:</strong> in this mode, proxy support is turned off assuming it is handled by the custom mailer as well.<br>
 * <strong>Note:</strong> in this mode, the batch-module (SMTP cluster(s) / smtp connection pooling) won't be used either.
 * <p>
 * When the {@link Email} was created from exact EML, defaults, validation and cryptographic processing have deliberately not changed it. The supplied
 * {@link MimeMessage} is backed by the authoritative input bytes; a custom implementation that serializes or forwards it must preserve that
 * representation rather than rebuilding it from the parsed Email getters.
 * <p>
 * SMTP envelope options, including a DSN envelope identifier, are available through {@link Email#getDeliveryStatusNotification()}, not MIME headers.
 * A custom implementation owns their transport mapping and must reject an explicitly supplied envelope identifier if it cannot honor it.
 * Simple Java Mail cannot inspect the remote capabilities of a caller-owned transport or external service.
 * It therefore does not generate an ENVID for CustomMailer or report an effective identifier on the submission receipt.
 * Recipient-specific NOTIFY preferences remain on each {@link org.simplejavamail.api.email.Recipient}, including override receivers.
 * A custom implementation owns their mapping too: preserve occurrence order, prefer explicit recipient preferences over the shared Email fallback,
 * and reject explicit preferences if the destination cannot honor them. Automatic ORCPT is not added by Simple Java Mail on this path.
 *
 * @see MailerGenericBuilder#withCustomMailer(CustomMailer)
 * @see <a href="https://simplejavamail.org/features.html#section-custom-mailer">Plug your own sending logic with a Custom Mailer</a>
 */
public interface CustomMailer {
	void testConnection(@NotNull OperationalConfig operationalConfig, @NotNull Session session);
	void sendMessage(@NotNull OperationalConfig operationalConfig, @NotNull Session session, @NotNull Email email, @NotNull MimeMessage message);
}
