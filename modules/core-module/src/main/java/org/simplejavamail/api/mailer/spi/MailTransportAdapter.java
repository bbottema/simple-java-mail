package org.simplejavamail.api.mailer.spi;

import jakarta.mail.MessagingException;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;

/**
 * Service-provider interface for Jakarta Mail implementation-specific submission behavior.
 *
 * <p>Implementations are discovered through {@link java.util.ServiceLoader}. An adapter owns the actual
 * {@link Transport#sendMessage(jakarta.mail.Message, jakarta.mail.Address[])} call so it can apply envelope
 * options without leaking provider-specific message types into MIME construction.</p>
 * <p>Service registrations are discovered once per thread-context class loader. Each submission invokes the registered constructor or provider factory
 * again and matches the resulting adapter against the actual transport. Replacing the application class loader starts a new discovery scope.</p>
 * <p>Implementations capture checked Jakarta Mail submission failures in the returned {@link MailTransportResult}. This lets Simple Java Mail preserve
 * the original exception while translating provider recipient arrays into an immutable high-level result. When message data was transferred but the
 * final acceptance response was not observed, adapters should use
 * {@link MailTransportResult#failedWithUnknownAcceptance(jakarta.mail.MessagingException, jakarta.mail.Address[], jakarta.mail.Address[])} and retain
 * only recipient facts that remain certain.</p>
 */
public interface MailTransportAdapter {

    /**
     * Keeps one email's content and provider options stable when Simple Java Mail retries its submission.
     * Combine the original envelope/provider options with the already frozen MIME bytes supplied by the caller.
     * Called once on a connected transport before the first invocation, never to send or probe. Capture effective provider options
     * rather than rereading changing defaults on later attempts. Keep generated transaction identifiers out of this reusable value.
     * <p>
     * The returned mail must retain the same recipients, required envelope behavior and content bytes for every invocation. Ordinary
     * optional MIME rewriting must be suppressed; exact messages keep their complete-byte contract. Existing adapters do not opt in
     * implicitly, even when they already support protected content. The default fails before submission rather than guessing.
     * @param transport The actual selected connected provider transport; no commands may be issued by this hook.
     * @param originalMail Original prepared mail carrying envelope and advanced provider choices.
     * @param frozenContent Immutable repeatable MIME representation owned by the calling logical operation.
     * @return Repeatable prepared mail with resolved provider options, not transport-local mutable attempt history.
     * @throws MessagingException If this adapter cannot preserve repeated submission safely.
     */
    @NotNull
    default PreparedMail prepareForRepeatedSubmission(@NotNull final Transport transport, @NotNull final PreparedMail originalMail,
            @NotNull final MimeMessage frozenContent) throws MessagingException {
        throw new MailTransportCompatibilityException("This SMTP provider adapter cannot keep the email unchanged across automatic retries. "
                + "Use an adapter supporting repeatable submission, or call clearMailSendRetryPolicy() on the selected Mailer's builder "
                + "and manage retry decisions in the application instead. No message was submitted.", originalMail.getRecipients());
    }

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
     * Simple Java Mail checks this before dispatch. Recipient-rejection handling is checked separately through
     * {@link #supportsSendingToAcceptedRecipients()}, so supporting that choice does not require accepting every other envelope option.
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

    /**
     * Submits through the connection already supplied by the caller. Do not connect, reconnect or select another destination here:
     * acquisition and retained-scope ownership belong to Simple Java Mail. A closed/unusable connection must fail normally,
     * with conservative acceptance facts, rather than silently acquiring a replacement.
     * @param transport Selected connected transport, which can become unusable before or during invocation.
     * @param preparedMail Authoritative content and envelope for this physical submission.
     * @return Actual submission facts, retaining uncertainty when acceptance could not be established.
     */
    @NotNull
    MailTransportResult sendMessage(@NotNull Transport transport, @NotNull PreparedMail preparedMail);

    /**
     * Submits the email as usual, with an opportunity to report progress through {@link MailTransportProgress}. Report the sender and recipient
     * exchanges, DATA permission, content writes and final-response waiting that your provider can actually observe; missing detail does not
     * prevent sending. The default implementation calls the ordinary submission method, so existing adapters need no changes.
     *
     * @param transport Selected connected transport.
     * @param preparedMail Authoritative content and envelope for this submission.
     * @param progress Progress sink for this invocation only; do not retain it beyond this call or put it in reusable prepared mail.
     * @return The same submission facts as the ordinary invocation.
     */
    @NotNull
    default MailTransportResult sendMessage(@NotNull final Transport transport, @NotNull final PreparedMail preparedMail,
            @NotNull final MailTransportProgress progress) {
        return sendMessage(transport, preparedMail);
    }

    /**
     * Opts into mandatory shared DSN requests from locked configuration. When
     * {@link DeliveryEnvelope#isDeliveryStatusNotificationRequired()} is true, check the actual connection's DSN capability and reject before MAIL FROM
     * if unavailable; otherwise apply the configured NOTIFY/RET without silently weakening it. Existing adapters are not opted in by an older
     * {@link #supportsDeliveryEnvelope(DeliveryEnvelope)} implementation returning true.
     */
    default boolean supportsRequiredDeliveryStatusNotification() {
        return false;
    }

    /**
     * Opts into honoring both explicit recipient-rejection choices on {@link DeliveryEnvelope#getSendingToAcceptedRecipients()}.
     * {@code true} permits continuation for accepted recipients; {@code false} withholds content after a known rejection.
     * Check incompatible provider configuration before submission rather than silently weakening the choice. Keep partial acceptance a failed result,
     * with its accepted/unsent/rejected facts intact. An unset choice preserves existing behavior.
     * <p>
     * This separate opt-in prevents an older {@link #supportsDeliveryEnvelope(DeliveryEnvelope)} returning true from claiming support accidentally.
     * @return Whether this adapter understands the choice, subject to its actual transport configuration.
     */
    default boolean supportsSendingToAcceptedRecipients() {
        return false;
    }
}
