package org.simplejavamail.api.mailer.spi;

import jakarta.mail.Address;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;

import static java.util.Objects.requireNonNull;

/**
 * A finalized MIME message together with the transport-only data needed to submit it.
 *
 * <p>The contained message remains a Jakarta Mail type so provider adapters can hand it to a normal
 * {@code Transport}. {@link #getContentRequirement()} describes which message bytes a provider adapter must preserve.</p>
 */
public final class PreparedMail {

    @NotNull
    private final MimeMessage mimeMessage;
    @NotNull
    private final Address[] recipients;
    @NotNull
    private final DeliveryEnvelope deliveryEnvelope;
    private final ContentRequirement contentRequirement;
    private final boolean legacySmtpContentSupportEnabled;

    @lombok.Builder(builderClassName = "Builder", setterPrefix = "with")
    private PreparedMail(@NotNull final MimeMessage mimeMessage, @NotNull final Address[] recipients, @NotNull final DeliveryEnvelope deliveryEnvelope,
                        @NotNull final ContentRequirement contentRequirement, final boolean legacySmtpContentSupportEnabled) {
        this.mimeMessage = requireNonNull(mimeMessage, "mimeMessage");
        this.recipients = requireNonNull(recipients, "recipients").clone();
        this.deliveryEnvelope = requireNonNull(deliveryEnvelope, "deliveryEnvelope");
        this.contentRequirement = requireNonNull(contentRequirement, "contentRequirement");
        this.legacySmtpContentSupportEnabled = legacySmtpContentSupportEnabled;
    }

    /** The finalized message to submit. Construction never reads or serializes its content. */
    @NotNull
    public MimeMessage getMimeMessage() {
        return mimeMessage;
    }

    /** A defensive copy of the ordered envelope recipients, including duplicate occurrences. */
    @NotNull
    public Address[] getRecipients() {
        return recipients.clone();
    }

    /** Transport-only sender and delivery requirements; these do not change MIME headers. */
    @NotNull
    public DeliveryEnvelope getDeliveryEnvelope() {
        return deliveryEnvelope;
    }

    /** Which prepared bytes the adapter must preserve. NORMAL allows provider conversion. */
    @NotNull
    public ContentRequirement getContentRequirement() {
        return contentRequirement;
    }

    /** Whether the selected server permits unchanged content without advertised SMTPUTF8/8BITMIME support. Security requirements still apply. */
    public boolean isLegacySmtpContentSupportEnabled() {
        return legacySmtpContentSupportEnabled;
    }
}
