package org.simplejavamail.api.email;

import jakarta.mail.Message;
import jakarta.mail.Message.RecipientType;
import lombok.Value;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.security.cert.X509Certificate;

/**
 * An immutable recipient object, with a name, emailaddress and recipient type (eg {@link Message.RecipientType#BCC}), and optionally an S/MIME certificate for
 * encrypting messages on a per-user basis.
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
     * @see IRecipientBuilder#withType(RecipientType)
     */
    @Nullable RecipientType type;

    /**
     * @see IRecipientBuilder#withSmimeCertificate(X509Certificate)
     */
    @Nullable X509Certificate smimeCertificate;

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