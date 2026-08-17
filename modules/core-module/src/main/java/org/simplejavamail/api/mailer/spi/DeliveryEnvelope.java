package org.simplejavamail.api.mailer.spi;

import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;

import java.io.Serializable;

/**
 * Provider-neutral SMTP envelope options for one message submission.
 * <p>
 * These values are deliberately kept separate from the MIME message: an envelope sender and delivery-status
 * notification request are SMTP commands, not message headers or body content.
 */
public final class DeliveryEnvelope implements Serializable {

    private static final long serialVersionUID = 1L;

    @Nullable
    private final String envelopeFrom;
    @Nullable
    private final DeliveryStatusNotification deliveryStatusNotification;

    public DeliveryEnvelope(@Nullable final String envelopeFrom,
                            @Nullable final DeliveryStatusNotification deliveryStatusNotification) {
        this.envelopeFrom = envelopeFrom;
        this.deliveryStatusNotification = deliveryStatusNotification;
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
        return envelopeFrom != null || deliveryStatusNotification != null;
    }
}
