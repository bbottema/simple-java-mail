package org.simplejavamail.api.mailer;

import static java.lang.String.format;

/**
 * Thrown when an email (as MimeMessage) is bigger than the maximum allowed size.
 *
 * @see MailerGenericBuilder#withMaximumEmailSize(int)
 */
public class EmailTooBigException extends RuntimeException {
    public EmailTooBigException(final long emailSize, final long maximumEmailSize) {
        super(format("Email size of %s bytes exceeds maximum allowed size of %s bytes", emailSize, maximumEmailSize));
    }
}
