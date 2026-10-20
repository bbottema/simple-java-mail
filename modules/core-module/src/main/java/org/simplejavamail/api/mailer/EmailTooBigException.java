package org.simplejavamail.api.mailer;

import static java.lang.String.format;

/**
 * Thrown when an email (as MimeMessage) is bigger than the maximum allowed size.
 * This failure reaches the caller directly with kind MESSAGE_SIZE; it belongs to the common MailException family.
 * The local check has no SMTP submission receipt. A server-advertised SIZE rejection is reported separately through a submission failure's receipt.
 *
 * @see org.simplejavamail.api.mailer.config.ValidationConfig.Builder#withMaximumEmailSize(int)
 */
public class EmailTooBigException extends MailSendException {
    private static final long serialVersionUID = 1L;

    public EmailTooBigException(final long emailSize, final long maximumEmailSize) {
        super(MailFailureKind.MESSAGE_SIZE,
                format("Email size of %s bytes exceeds maximum allowed size of %s bytes", emailSize, maximumEmailSize), null, null);
    }
}
