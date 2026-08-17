package org.simplejavamail.api.mailer.spi;

import jakarta.mail.Address;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;

import static java.util.Objects.requireNonNull;

/**
 * A finalized MIME message together with the transport-only data needed to submit it.
 *
 * <p>The contained message remains a Jakarta Mail type so provider adapters can hand it to a normal
 * {@code Transport}. Provider adapters must not change bytes covered by a cryptographic signature when
 * {@link #requiresStableContent()} returns {@code true}.</p>
 */
public final class PreparedMail implements AutoCloseable {

    @NotNull
    private final MimeMessage mimeMessage;
    @NotNull
    private final Address[] recipients;
    @NotNull
    private final DeliveryEnvelope deliveryEnvelope;
    private final boolean stableContentRequired;

    public PreparedMail(@NotNull final MimeMessage mimeMessage,
                        @NotNull final Address[] recipients,
                        @NotNull final DeliveryEnvelope deliveryEnvelope,
                        final boolean stableContentRequired) {
        this.mimeMessage = requireNonNull(mimeMessage, "mimeMessage");
        this.recipients = requireNonNull(recipients, "recipients").clone();
        this.deliveryEnvelope = requireNonNull(deliveryEnvelope, "deliveryEnvelope");
        this.stableContentRequired = stableContentRequired;
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

    public boolean requiresStableContent() {
        return stableContentRequired;
    }

    /** Releases temporary storage owned by a finalized MIME message. */
    @Override
    public void close() {
        if (mimeMessage instanceof AutoCloseable) {
            try {
                ((AutoCloseable) mimeMessage).close();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("Unable to release prepared MIME message", e);
            }
        }
    }
}
