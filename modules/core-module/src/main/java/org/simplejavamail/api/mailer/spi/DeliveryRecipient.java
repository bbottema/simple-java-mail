package org.simplejavamail.api.mailer.spi;

import lombok.Value;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption;
import org.simplejavamail.internal.util.DsnNotifyOptions;

import java.io.Serializable;
import java.util.Collection;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Transport-only policy for one envelope-recipient occurrence, before provider expansion of RFC address groups.
 * Unlike a map keyed by address, the ordered list in {@link DeliveryEnvelope} retains different preferences for duplicate recipients.
 * No display name, certificate or MIME content is exposed here.
 */
@Value
public class DeliveryRecipient implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The transport mailbox (or RFC address group), without a display name; this is not a caller-selected ORCPT override. */
    @NotNull String address;

    /** Immutable recipient-specific preference; empty leaves the shared Email/provider fallback in effect. */
    @NotNull Set<NotifyOption> notifyOptions;

    /**
     * @param address The mailbox or RFC address group for this exact occurrence in the envelope recipient list.
     * @param notifyOptions The recipient preference; an empty collection inherits the shared Email/provider behavior.
     */
    public DeliveryRecipient(@NotNull final String address, @NotNull final Collection<NotifyOption> notifyOptions) {
        this.address = requireNonNull(address, "address");
        this.notifyOptions = DsnNotifyOptions.copyOf(notifyOptions);
    }
}
