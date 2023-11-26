package org.simplejavamail.api.email;

import jakarta.mail.Message;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.security.cert.X509Certificate;

/**
 * Produces immutable recipient object, with a name, emailaddress and recipient type (eg {@link Message.RecipientType#BCC}),
 * and optionally an S/MIME certificate for encrypting messages on a per-user basis.
 */
public interface IRecipientBuilder {

    /**
     * @param name Optional explicit name of the recipient, otherwise taken from inside the address (if provided) (for example "Joe Sixpack &lt;joesixpack@beerme.com&gt;").
     * @see #clearingName()
     */
    IRecipientBuilder withName(@NotNull String name);

    /**
     * Clears the name, in which case the name from inside the provided address is used (if provided), or else the address is used as-is.
     * So in email clients you won't see a name, just the address.
     *
     * @see #withName(String)
     */
    IRecipientBuilder clearingName();

    /**
     * @param address The email address of the recipient, can contain a name, but is ignored if a name was seperately provided.
     */
    IRecipientBuilder withAddress(@NotNull String address);

    /**
     * @param type The recipient type (e.g. {@link Message.RecipientType#TO}), optional for {@code from} and {@code replyTo} fields.
     */
    IRecipientBuilder withType(@NotNull Message.RecipientType type);

    /**
     * @param smimeCertificate Optional S/MIME certificate for this recipient, used for encrypting messages on a per-user basis. Overrides certificate provided
     *                         on {@link Email} level and {@link org.simplejavamail.api.mailer.Mailer} level (if provided).
     * @see #clearingSmimeCertificate()
     */
    IRecipientBuilder withSmimeCertificate(@NotNull X509Certificate smimeCertificate);

    /**
     * Clears the S/MIME certificate used for encrypting S/MIME messages for this recipient. In this case, if available, the S/MIME certificate from
     * the {@link Email} object is used and from the {@link org.simplejavamail.api.mailer.Mailer} otherwise (if provided).
     *
     * @see #withSmimeCertificate(X509Certificate)
     */
    IRecipientBuilder clearingSmimeCertificate();

    @NotNull Recipient build();

    /**
     * @see #withName(String)
     */
    @Nullable String getName();

    /**
     * @see #withAddress(String)
     */
    @Nullable String getAddress();

    /**
     * @see #withType(Message.RecipientType)
     */
    @Nullable Message.RecipientType getType();

    /**
     * @see #withSmimeCertificate(X509Certificate)
     */
    @Nullable X509Certificate getSmimeCertificate();
}
