package org.simplejavamail.api.email;

import jakarta.mail.Message;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.security.cert.X509Certificate;
import java.util.List;

/**
 * Produces immutable recipients object, with an optional default/overriding name.
 */
public interface IRecipientsBuilder {

    /**
     * Default explicit name of the recipient, otherwise taken from inside the {@link Recipient}'s explicitly set name, or otherwise from the nested address (if
     * provided).
     * <p>
     * The reason for this option is due to the natur of an email address on the string level, where we don't always control the value of the name part of the
     * address. This is especially true when the address is provided by a user, where the name part can be anything, including a name that is not a name at all,
     * or perhaps previously set defaults in a properties configuration.
     * </p>
     */
    IRecipientsBuilder withDefaultName(@Nullable String defaultName);

    /**
     * Similar to {@link #withDefaultName(String)}, but this field is used to override the name of all recipients in this object. The nested name of each
     * recipient is simply ignored if this field is set, including explicitly set names or implicitly derived names from the address.
     */
    IRecipientsBuilder withOverridingName(@Nullable String overridingName);

    IRecipientsBuilder withRecipient(@NotNull Recipient recipient);

    IRecipientsBuilder withRecipients(@NotNull Recipient... recipient);

    /**
     * @param defaultSmimeCertificate Optional S/MIME certificate for all recipients in this object, used for encrypting messages on a per-user basis. Overrides
     *                                certificate provided on {@link Email} level and {@link org.simplejavamail.api.mailer.Mailer} level (if provided). If
     *                                overridden by a specific recipient, that recipient's certificate is used instead. The only exception is when a
     *                                {@code Mailer} level override is set, in which case that is used for all recipients.
     *                                <p></p>
     *                                So, the order of precedence is:
     *                                <ol>
     *                                <li>Mailer level (override value)</li>
     *                                <li>Recipient level (specific value)</li>
     *                                <li>Recipients level (default value)</li>
     *                                <li>Email level (default value)</li>
     *                                <li>Mailer level (default value)</li>
     *                                </ol>
     * @see IRecipientBuilder#withSmimeCertificate(X509Certificate)
     */
    IRecipientsBuilder withDefaultSmimeCertificate(@NotNull X509Certificate defaultSmimeCertificate);

    /**
     * @return An immutable {@link Recipients} object, with an optional default/overriding name and an optional default S/MIME certificate.
     */
    @NotNull Recipients build();

    /**
     * @see #withDefaultName(String)
     */
    @Nullable String getDefaultName();

    /**
     * @see #withOverridingName(String)
     */
    @Nullable String getOverridingName();

    /**
     * @see #withRecipient(Recipient)
     * @see #withRecipients(Recipient...)
     */
    @NotNull List<Recipient> getRecipients();

    /**
     * @see #withDefaultSmimeCertificate(X509Certificate)
     */
    @Nullable X509Certificate getDefaultSmimeCertificate();
}