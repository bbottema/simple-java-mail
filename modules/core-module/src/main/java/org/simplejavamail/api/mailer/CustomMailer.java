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
 *
 * @see MailerGenericBuilder#withCustomMailer(CustomMailer)
 * @see <a href="https://simplejavamail.org/features.html#section-custom-mailer">Plug your own sending logic with a Custom Mailer</a>
 */
public interface CustomMailer {
	void testConnection(@NotNull OperationalConfig operationalConfig, @NotNull Session session);
	void sendMessage(@NotNull OperationalConfig operationalConfig, @NotNull Session session, @NotNull Email email, @NotNull MimeMessage message);
}