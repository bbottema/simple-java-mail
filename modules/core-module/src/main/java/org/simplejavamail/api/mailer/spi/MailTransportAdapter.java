package org.simplejavamail.api.mailer.spi;

import jakarta.mail.MessagingException;
import jakarta.mail.Transport;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.SmtpServerResponse;

/**
 * Service-provider interface for Jakarta Mail implementation-specific submission behavior.
 *
 * <p>Implementations are discovered through {@link java.util.ServiceLoader}. An adapter owns the actual
 * {@link Transport#sendMessage(jakarta.mail.Message, jakarta.mail.Address[])} call so it can apply envelope
 * options without leaking provider-specific message types into MIME construction.</p>
 */
public interface MailTransportAdapter {

    boolean supports(@NotNull Transport transport);

    @Nullable
    SmtpServerResponse sendMessage(@NotNull Transport transport, @NotNull PreparedMail preparedMail)
            throws MessagingException;
}
