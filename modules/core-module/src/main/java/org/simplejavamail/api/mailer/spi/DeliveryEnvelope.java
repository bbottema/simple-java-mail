package org.simplejavamail.api.mailer.spi;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;

import java.io.Serializable;
import java.util.List;

import static java.util.Collections.emptyList;

/**
 * Provider-neutral SMTP envelope options for one message submission.
 * <p>
 * These values are deliberately kept separate from the MIME message: an envelope sender, delivery-status notification request and
 * RFC 8689 REQUIRETLS requirement are SMTP commands, not message headers or body content.
 */
public final class DeliveryEnvelope implements Serializable {

    private static final long serialVersionUID = 1L;

    @Nullable
    private final String envelopeFrom;
    @Nullable
    private final DeliveryStatusNotification deliveryStatusNotification;
    @NotNull
    private final List<DeliveryRecipient> recipientOptions;
    private final boolean tlsRequiredForOnwardDelivery;

    /**
     * Creates an envelope without recipient-specific notification preferences.
     *
     * @param envelopeFrom The SMTP envelope sender, or {@code null} to leave sender selection to the transport.
     * @param deliveryStatusNotification The shared delivery-notification request, or {@code null} when none is configured.
     */
    public DeliveryEnvelope(@Nullable final String envelopeFrom,
            @Nullable final DeliveryStatusNotification deliveryStatusNotification) {
        this(envelopeFrom, deliveryStatusNotification, emptyList(), false);
    }

    /**
     * Recipient options are defensively copied and retain duplicate occurrences.
     *
     * @param envelopeFrom The SMTP envelope sender, or {@code null} to leave sender selection to the transport.
     * @param deliveryStatusNotification The shared delivery-notification request, or {@code null} when none is configured.
     * @param recipientOptions Policies in exactly the same order as {@link PreparedMail#getRecipients()}, including duplicate occurrences.
     *                         An empty list means no recipient-specific preferences; otherwise every recipient must have an entry.
     */
    public DeliveryEnvelope(@Nullable final String envelopeFrom, @Nullable final DeliveryStatusNotification deliveryStatusNotification,
            @NotNull final List<DeliveryRecipient> recipientOptions) {
        this(envelopeFrom, deliveryStatusNotification, recipientOptions, false);
    }

    /**
     * Creates the complete provider-neutral SMTP envelope for one message submission.
     *
     * @param envelopeFrom The SMTP envelope sender, or {@code null} to leave sender selection to the transport.
     * @param deliveryStatusNotification The shared delivery-notification request, or {@code null} when none is configured.
     * @param recipientOptions Policies in exactly the same order as {@link PreparedMail#getRecipients()}, including duplicate occurrences.
     * @param tlsRequiredForOnwardDelivery Whether the adapter must apply RFC 8689 REQUIRETLS to MAIL FROM.
     */
    public DeliveryEnvelope(@Nullable final String envelopeFrom, @Nullable final DeliveryStatusNotification deliveryStatusNotification,
            @NotNull final List<DeliveryRecipient> recipientOptions, final boolean tlsRequiredForOnwardDelivery) {
        this.envelopeFrom = envelopeFrom;
        this.deliveryStatusNotification = deliveryStatusNotification;
        this.recipientOptions = List.copyOf(recipientOptions);
        this.tlsRequiredForOnwardDelivery = tlsRequiredForOnwardDelivery;
    }

    @Nullable
    public String getEnvelopeFrom() {
        return envelopeFrom;
    }

    @Nullable
    public DeliveryStatusNotification getDeliveryStatusNotification() {
        return deliveryStatusNotification;
    }

    public boolean hasProviderSpecificOptions() {
        return envelopeFrom != null || deliveryStatusNotification != null || hasRecipientNotifyOptions() || tlsRequiredForOnwardDelivery;
    }

    /** @return Whether RFC 8689 REQUIRETLS must be applied to MAIL FROM for this submission. */
    public boolean isTlsRequiredForOnwardDelivery() {
        return tlsRequiredForOnwardDelivery;
    }

    /** Immutable ordered recipient policies; empty for older serialized envelopes and submissions without recipient policies. */
    @NotNull
    public List<DeliveryRecipient> getRecipientOptions() {
        return recipientOptions;
    }

    /** Whether at least one recipient has an explicit preference requiring adapter/connection support. */
    public boolean hasRecipientNotifyOptions() {
        return recipientOptions.stream().anyMatch(recipient -> !recipient.getNotifyOptions().isEmpty());
    }

    /** Normalize streams written before recipient policies were added, keeping the accessors as plain immutable data access. */
    private Object readResolve() {
        return new DeliveryEnvelope(envelopeFrom, deliveryStatusNotification, recipientOptions == null ? emptyList() : recipientOptions,
                tlsRequiredForOnwardDelivery);
    }
}
