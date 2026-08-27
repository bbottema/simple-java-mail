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

    /**
     * Indicates whether this adapter can submit a message while honoring the requested content-preservation contract.
     * The default keeps existing adapters source-compatible and limits them to ordinary composed mail. Simple Java Mail fails before calling
     * {@link #sendMessage(Transport, PreparedMail)} when this method returns {@code false}.
     *
     * @param contentRequirement The prepared message's preservation requirement.
     * @return Whether this adapter can honor that requirement throughout submission.
     */
    default boolean supportsContentRequirement(@NotNull final ContentRequirement contentRequirement) {
        return contentRequirement == ContentRequirement.NORMAL;
    }

    @NotNull
    MailTransportResult sendMessage(@NotNull Transport transport, @NotNull PreparedMail preparedMail);
}
