package org.simplejavamail.api.mailer;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.config.OperationalConfig;

/**
 * By default, Simple Java Mail handles the ultimate connection and sending of emails. However, it is possible to replace this last step
 * by a custom implementation.
 * <p>
 * The benefit of this is that Simple Java Mail acts as an accelarator, providing thread pool, applying email content-validation, address validations,
 * configuring a {@code Session} instance, producing a {@code MimeMessage}, all with full S/MIME, DKIM support and everything else.
 * <p>
 * <strong>Note:</strong> in this mode, proxy support is turned off assuming it is handled by the custom mailer as well.
 *
 * @see MailerGenericBuilder#withCustomMailer(CustomMailer)
 */
public interface CustomMailer {
	void testConnection(@NotNull OperationalConfig operationalConfig, @NotNull Session session);
	void sendMessage(@NotNull OperationalConfig operationalConfig, @NotNull Session session, final Email email, @NotNull MimeMessage message);
}