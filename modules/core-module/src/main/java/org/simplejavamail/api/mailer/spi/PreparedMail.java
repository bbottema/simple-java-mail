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

    public PreparedMail(@NotNull final MimeMessage mimeMessage,
                        @NotNull final Address[] recipients,
                        @NotNull final DeliveryEnvelope deliveryEnvelope,
                        @NotNull final ContentRequirement contentRequirement) {
        this.mimeMessage = requireNonNull(mimeMessage, "mimeMessage");
        this.recipients = requireNonNull(recipients, "recipients").clone();
        this.deliveryEnvelope = requireNonNull(deliveryEnvelope, "deliveryEnvelope");
        this.contentRequirement = requireNonNull(contentRequirement, "contentRequirement");
    }

    /**
     * @deprecated Use {@link #PreparedMail(MimeMessage, Address[], DeliveryEnvelope, ContentRequirement)} to state the preservation contract explicitly.
     */
    @Deprecated
    public PreparedMail(@NotNull final MimeMessage mimeMessage,
                        @NotNull final Address[] recipients,
                        @NotNull final DeliveryEnvelope deliveryEnvelope,
                        final boolean stableContentRequired) {
        this(mimeMessage, recipients, deliveryEnvelope, stableContentRequired
                ? ContentRequirement.PRESERVE_PROTECTED_CONTENT
                : ContentRequirement.NORMAL);
    }

    @NotNull
    public MimeMessage getMimeMessage() {
        return mimeMessage;
    }

    @NotNull
    public Address[] getRecipients() {
        return recipients.clone();
    }

    @NotNull
    public DeliveryEnvelope getDeliveryEnvelope() {
        return deliveryEnvelope;
    }

    /**
     * @return The preservation contract the selected transport adapter must honor.
     */
    @NotNull
    public ContentRequirement getContentRequirement() {
        return contentRequirement;
    }

    /**
     * @deprecated Use {@link #getContentRequirement()} to distinguish protected-content stability from preservation of every supplied byte.
     */
    @Deprecated
    public boolean requiresStableContent() {
        return contentRequirement != ContentRequirement.NORMAL;
    }

}
