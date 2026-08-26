package org.simplejavamail.api.mailer.spi;

import jakarta.mail.Transport;
import org.jetbrains.annotations.NotNull;

/**
 * Service-provider interface for Jakarta Mail implementation-specific submission behavior.
 *
 * <p>Implementations are discovered through {@link java.util.ServiceLoader}. An adapter owns the actual
 * {@link Transport#sendMessage(jakarta.mail.Message, jakarta.mail.Address[])} call so it can apply envelope
 * options without leaking provider-specific message types into MIME construction.</p>
 * <p>Implementations capture checked Jakarta Mail submission failures in the returned {@link MailTransportResult}. This lets Simple Java Mail preserve
 * the original exception while translating provider recipient arrays into an immutable high-level result.</p>
 */
public interface MailTransportAdapter {

    boolean supports(@NotNull Transport transport);

    @NotNull
    MailTransportResult sendMessage(@NotNull Transport transport, @NotNull PreparedMail preparedMail);
}
