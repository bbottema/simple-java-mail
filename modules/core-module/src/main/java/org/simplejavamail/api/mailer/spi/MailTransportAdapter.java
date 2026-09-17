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
 * the original exception while translating provider recipient arrays into an immutable high-level result. When message data was transferred but the
 * final acceptance response was not observed, adapters should use
 * {@link MailTransportResult#failedWithUnknownAcceptance(jakarta.mail.MessagingException, jakarta.mail.Address[], jakarta.mail.Address[])} and retain
 * only recipient facts that remain certain.</p>
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

    /**
     * Indicates whether this adapter understands the supplied SMTP envelope options. The default preserves existing adapters' behavior for
     * envelope sender and shared NOTIFY/RET, but refuses a fixed ENVID or recipient-specific NOTIFY until an adapter explicitly supports them.
     * Simple Java Mail checks this before dispatch.
     * <p>
     * Returning {@code true} does not establish server support. For a fixed ENVID the adapter must also check DSN on the actual connected transport and fail
     * before MAIL FROM when unavailable; silently omitting the identifier is not allowed.
     * Without a fixed identifier, adapters may generate one for each DSN-capable submission and otherwise send normally without it.
     * Report the effective unencoded identifier through {@link MailTransportResult#withEnvelopeId(String)}, including failed submissions that used it.
     * Recipient-specific preferences must likewise be rejected before MAIL FROM when the transport or connection cannot honor them.
     * Preserve their occurrence order, apply each complete preference instead of merging with shared NOTIFY, and leave MIME bytes unchanged.
     * Automatic ORCPT is optional for adapters, must match the actual envelope recipient at initial submission, and does not enable NOTIFY.
     * An RFC 8689 REQUIRETLS request must be rejected before MAIL FROM unless the actual connection uses authenticated TLS and advertises
     * usable REQUIRETLS after TLS negotiation. When the adapter does issue that parameter, record it through
     * {@link MailTransportResult#withRequireTlsUsed(boolean)}; selecting the option alone is not enough.
     *
     * @param envelope Options for this submission, kept separate from MIME content.
     * @return Whether the adapter can honor these options, subject to the actual server's capabilities.
     */
    default boolean supportsDeliveryEnvelope(@NotNull final DeliveryEnvelope envelope) {
        return !envelope.isTlsRequiredForOnwardDelivery()
                && !envelope.hasRecipientNotifyOptions()
                && (envelope.getDeliveryStatusNotification() == null || envelope.getDeliveryStatusNotification().getEnvelopeId() == null);
    }

    @NotNull
    MailTransportResult sendMessage(@NotNull Transport transport, @NotNull PreparedMail preparedMail);
}
