package org.simplejavamail.internal.util;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import org.jetbrains.annotations.ApiStatus;

/** Provider evidence that its AUTH wrapper represents an observed setup outage, not a credential rejection. */
@ApiStatus.Internal
public final class SmtpAuthenticationAvailabilityException extends MessagingException {
    private static final long serialVersionUID = 1L;

    public SmtpAuthenticationAvailabilityException(final AuthenticationFailedException authenticationWrapper) {
        super("The SMTP connection became unavailable during authentication.", authenticationWrapper);
    }
}
