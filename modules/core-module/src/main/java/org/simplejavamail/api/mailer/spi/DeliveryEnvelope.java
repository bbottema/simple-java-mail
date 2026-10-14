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
 * These values are deliberately kept separate from the MIME message: envelope sender, delivery notifications, onward TLS and recipient-rejection
 * handling govern submission, not message headers or body content.
 */
@lombok.Builder(builderClassName = "Builder", setterPrefix = "with")
public final class DeliveryEnvelope implements Serializable {

    private static final long serialVersionUID = 1L;

    @Nullable
    private final String envelopeFrom;
    @Nullable
    private final DeliveryStatusNotification deliveryStatusNotification;
    @lombok.Builder.Default
    @NotNull
    private final List<DeliveryRecipient> recipientOptions = emptyList();
    private final boolean tlsRequiredForOnwardDelivery;
    private final boolean deliveryStatusNotificationRequired;
    @Nullable
    private final Boolean sendingToAcceptedRecipients;

    private DeliveryEnvelope(@Nullable final String envelopeFrom, @Nullable final DeliveryStatusNotification deliveryStatusNotification,
            @NotNull final List<DeliveryRecipient> recipientOptions, final boolean tlsRequiredForOnwardDelivery,
            final boolean deliveryStatusNotificationRequired, @Nullable final Boolean sendingToAcceptedRecipients) {
        if (deliveryStatusNotificationRequired && deliveryStatusNotification == null) {
            throw new IllegalArgumentException("deliveryStatusNotificationRequired is true, but deliveryStatusNotification is null. "
                    + "Supply a DeliveryStatusNotification request, or set deliveryStatusNotificationRequired to false when no mandatory request is needed.");
        }
        this.envelopeFrom = envelopeFrom;
        this.deliveryStatusNotification = deliveryStatusNotification;
        this.recipientOptions = List.copyOf(recipientOptions);
        this.tlsRequiredForOnwardDelivery = tlsRequiredForOnwardDelivery;
        this.deliveryStatusNotificationRequired = deliveryStatusNotificationRequired;
        this.sendingToAcceptedRecipients = sendingToAcceptedRecipients;
    }

    /** SMTP envelope sender; null leaves selection to the provider. Does not change the MIME From header. */
    @Nullable
    public String getEnvelopeFrom() {
        return envelopeFrom;
    }

    /** Shared delivery-notification request, or null when none is configured. */
    @Nullable
    public DeliveryStatusNotification getDeliveryStatusNotification() {
        return deliveryStatusNotification;
    }

    public boolean hasProviderSpecificOptions() {
        return envelopeFrom != null || deliveryStatusNotification != null || hasRecipientNotifyOptions() || tlsRequiredForOnwardDelivery
                || sendingToAcceptedRecipients != null;
    }

    /** True permits partial sending, false withholds content after recipient rejection, and null retains provider behavior. Partial sending still fails. */
    @Nullable
    public Boolean getSendingToAcceptedRecipients() {
        return sendingToAcceptedRecipients;
    }

    /** Whether the adapter must request REQUIRETLS for onward delivery; not a claim that the server accepted it. */
    public boolean isTlsRequiredForOnwardDelivery() {
        return tlsRequiredForOnwardDelivery;
    }

    /** Whether the shared DSN request is mandatory. A server that cannot honor it must be rejected before submission. */
    public boolean isDeliveryStatusNotificationRequired() {
        return deliveryStatusNotificationRequired;
    }

    /** Immutable recipient policies in PreparedMail recipient order, including duplicates; empty when none are supplied. */
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
                tlsRequiredForOnwardDelivery, deliveryStatusNotificationRequired, sendingToAcceptedRecipients);
    }
}
