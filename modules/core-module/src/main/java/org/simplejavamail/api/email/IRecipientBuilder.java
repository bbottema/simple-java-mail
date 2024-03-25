package org.simplejavamail.api.email;

import jakarta.mail.Message;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.security.cert.X509Certificate;

/**
 * Produces immutable recipient object, with a name, emailaddress and recipient type (eg {@link Message.RecipientType#BCC}), and optionally an S/MIME
 * certificate for encrypting messages on a per-user basis.
 */
public interface IRecipientBuilder {

    /**
     * @param name Optional explicit name of the recipient, otherwise taken from inside the address (if provided, for example "Joe Sixpack
     *             &lt;joesixpack@beerme.com&gt;"). Note that in {@link Recipients}, this can still be overridden by the {@code defaultName} and
     *             {@code overridingName} fields.
     */
    IRecipientBuilder withName(@NotNull String name);

    /**
     * @param address The email address of the recipient, can contain a name, but is ignored if a name was seperately provided, this includes names possibly
     *                provided by {@link Recipients}.
     */
    IRecipientBuilder withAddress(@NotNull String address);

    /**
     * @param type The recipient type (e.g. {@link Message.RecipientType#TO}), optional for {@code from} and {@code replyTo} fields.
     */
    IRecipientBuilder withType(@NotNull Message.RecipientType type);

    /**
     * @param smimeCertificate Optional S/MIME certificate for this recipient, used for encrypting S/MIME messages on a per-user basis. Overrides certificate
     *                         provided on {@link Email} level and {@link org.simplejavamail.api.mailer.Mailer} level (if provided).
     *                         <p></p>
     *                         So, the order of precedence is:
     *                         <ol>
     *                         <li>Mailer level (override value)</li>
     *                         <li>Recipient level (specific value)</li>
     *                         <li>Recipients level (default value)</li>
     *                         <li>Email level (default value)</li>
     *                         <li>Mailer level (default value)</li>
     *                         </ol>
     * @see #clearingSmimeCertificate()
     * @see IRecipientsBuilder#withDefaultSmimeCertificate(X509Certificate)
     */
    IRecipientBuilder withSmimeCertificate(@NotNull X509Certificate smimeCertificate);

    /**
     * Clears the S/MIME certificate used for encrypting S/MIME messages for this recipient. In this case, if available, the S/MIME certificate from the
     * {@link Email} object is used and from the {@link org.simplejavamail.api.mailer.Mailer} otherwise (if provided).
     *
     * @see #withSmimeCertificate(X509Certificate)
     */
    IRecipientBuilder clearingSmimeCertificate();

    /**
     * Creates a new {@link Recipient} instance, but first checks if address is set and throws an exception if not.
     */
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