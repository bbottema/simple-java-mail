package org.simplejavamail.api.email;

import jakarta.mail.Message;
import jakarta.mail.Message.RecipientType;
import lombok.Value;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption;
import org.simplejavamail.internal.util.DsnNotifyOptions;

import java.io.Serializable;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Set;

import static java.util.Collections.emptySet;
import static java.util.Objects.requireNonNull;

/**
 * An immutable recipient object, with a name, emailaddress and recipient type (eg {@link Message.RecipientType#BCC}), and optionally an S/MIME certificate for
 * encrypting messages on a per-user basis and optional recipient-specific DSN notification preferences.
 *
 * @see IRecipientBuilder
 */
@Value
public class Recipient implements Serializable {

    private static final long serialVersionUID = 1234567L;

    /**
     * @see IRecipientBuilder#withName(String)
     */
    @Nullable String name;

    /**
     * @see IRecipientBuilder#withAddress(String)
     */
    @NotNull String address;

    /**
     * @see IRecipientBuilder#withType(Message.RecipientType)
     */
    @Nullable RecipientType type;

    /**
     * @see IRecipientBuilder#withSmimeCertificate(X509Certificate)
     */
    @Nullable X509Certificate smimeCertificate;

    /**
     * Immutable recipient-specific NOTIFY preference. Empty means use the Email fallback; NEVER explicitly requests no notifications.
     * @see IRecipientBuilder#withDeliveryStatusNotificationNotifyOptions(NotifyOption...)
     */
    @NotNull Set<NotifyOption> deliveryStatusNotificationNotifyOptions;

    /** Creates a recipient without an explicit DSN preference; the Email fallback applies. */
    public Recipient(@Nullable final String name, @NotNull final String address, @Nullable final RecipientType type,
            @Nullable final X509Certificate smimeCertificate) {
        this(name, address, type, smimeCertificate, emptySet());
    }

    /**
     * Creates a recipient with a defensively copied NOTIFY set. Empty means inherit; NEVER must be the only option when present.
     * @see IRecipientBuilder#withDeliveryStatusNotificationNotifyOptions(NotifyOption...)
     */
    public Recipient(@Nullable final String name, @NotNull final String address, @Nullable final RecipientType type,
            @Nullable final X509Certificate smimeCertificate, @NotNull final Collection<NotifyOption> notifyOptions) {
        this.name = name;
        this.address = requireNonNull(address, "address");
        this.type = type;
        this.smimeCertificate = smimeCertificate;
        this.deliveryStatusNotificationNotifyOptions = DsnNotifyOptions.copyOf(notifyOptions);
    }

    /** Older serialized recipients have no NOTIFY field and continue to inherit the Email fallback. */
    private Object readResolve() {
        return new Recipient(name, address, type, smimeCertificate,
                deliveryStatusNotificationNotifyOptions == null ? emptySet() : deliveryStatusNotificationNotifyOptions);
    }

    /**
     * Custom toString to maintain backwards-compatible format and exclude the verbose X509Certificate.
     */
    @Override
    public String toString() {
        return "Recipient{" +
                "name='" + name + "'" +
                ", address='" + address + "'" +
                ", type=" + type +
                '}';
    }
}
