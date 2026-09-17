package org.simplejavamail.api.email;

import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption;

import java.security.cert.X509Certificate;
import java.util.Set;

/**
 * Produces immutable recipient object, with a name, emailaddress and recipient type (eg {@link Message.RecipientType#BCC}), and optionally an S/MIME
 * certificate for encrypting messages on a per-user basis and recipient-specific DSN notification preferences.
 */
public interface IRecipientBuilder {

    /**
     * @param type The recipient type (e.g. {@link Message.RecipientType#TO}), optional for {@code from} and {@code replyTo} fields.
     */
    IRecipientBuilder withType(@NotNull Message.RecipientType type);

    /**
     * @param name Optional explicit name of the recipient, otherwise taken from inside the address (if provided, for example "Joe Sixpack
     *             &lt;joesixpack@beerme.com&gt;"). Note that in {@link Recipients}, this can still be overridden by the {@code defaultName} and
     *             {@code overridingName} fields.
     */
    IRecipientBuilder withName(@Nullable String name);

    /**
     * @param address The email address of the recipient, can contain a name, but is ignored if a name was seperately provided, this includes names possibly
     *                provided by {@link Recipients}.
     */
    IRecipientBuilder withAddress(@NotNull String address);

    /**
     * Delegates to {@link #withAddress(String)}, using the {@link InternetAddress} to set the address field, ignoring the name part.
     */
    IRecipientBuilder withAddressOnlyFrom(@NotNull InternetAddress address);

    /**
     * Delegates to {@link #withAddress(String)}, using the provided address to set the address field, extracting only the email address part.
     */
    IRecipientBuilder withAddressOnlyFrom(@NotNull String address);

    /**
     * Delegates to {@link #withName(String)} and {@link #withAddress(String)}, using the {@link InternetAddress} to set both fields and if no name is provided,
     * uses the provided default name.
     */
    IRecipientBuilder withAddressAndNameOrDefault(@NotNull InternetAddress address, @Nullable String defaultName);

    /**
     * Delegates to {@link #withName(String)} and {@link #withAddress(String)}, using the provided address to set both fields and if no name is provided, uses
     * the provided default name.
     */
    IRecipientBuilder withAddressAndNameOrDefault(@NotNull String address, @Nullable String defaultName);

    /**
     * Delegates to {@link #withName(String)} and {@link #withAddress(String)}, using the {@link InternetAddress} to set both fields. If a name is provided, it
     * will override the name part of the address. If no name is provided, the name part of the address is used.
     */
    IRecipientBuilder withAddressAndFixedNameOrProvided(@NotNull InternetAddress address, @Nullable String fixedName);

    /**
     * Delegates to {@link #withName(String)} and {@link #withAddress(String)}, using the provided address to set both fields. If a name is provided, it will
     * override the name part of the address. If no name is provided, the name part of the address is used.
     */
    IRecipientBuilder withAddressAndFixedNameOrProvided(@NotNull String address, @Nullable String fixedName);

    /**
     * @param smimeCertificate Optional S/MIME certificate for this recipient, used for encrypting S/MIME messages on a per-user basis. After recipients are
     *                         built, any certificate present on a {@link Recipient} is treated as the recipient-specific encryption certificate and takes
     *                         precedence over the governance-resolved {@link Email} / {@link org.simplejavamail.api.mailer.Mailer} S/MIME encryption
     *                         fallback.
     *                         <p>
     *                         For reusable recipient groups, use {@link IRecipientsBuilder#withDefaultSmimeCertificate(X509Certificate)},
     *                         {@link IRecipientsBuilder#withFixedSmimeCertificate(X509Certificate)} or
     *                         {@link IRecipientsBuilder#clearingSmimeCertificates()} before adding the resulting flat recipient list to an email.
     * @see #clearingSmimeCertificate()
     */
    IRecipientBuilder withSmimeCertificate(@NotNull X509Certificate smimeCertificate);

    /**
     * Clears the S/MIME certificate used for encrypting S/MIME messages for this recipient. In this case, if available, the S/MIME certificate from the
     * {@link Email} object is used and from the {@link org.simplejavamail.api.mailer.Mailer} otherwise (if provided).
     *
     * @see #withSmimeCertificate(X509Certificate)
     * @see IRecipientsBuilder#clearingSmimeCertificates()
     */
    IRecipientBuilder clearingSmimeCertificate();

    /**
     * Chooses which delivery notifications to request for this recipient, replacing its previous preference. It wins over group defaults and the
     * governance-resolved Email fallback, including Mailer Email overrides. A group's fixed preference can still replace it when building that group.
     * Use {@link NotifyOption#NEVER} alone to request no notifications; an empty array restores fallback. Null options and NEVER combined with other events are rejected.
     * For example, {@code withDeliveryStatusNotificationNotifyOptions(NotifyOption.FAILURE, NotifyOption.DELAY)} requests failure and delay notifications.
     * <p>
     * Explicit recipient preferences require a supporting provider adapter and DSN on the actual connection; otherwise sending fails before MAIL FROM.
     * The bundled managed Angus transport supports them; caller-owned Sessions and custom socket factories bypass its command hook.
     * This does not guarantee that a DSN will arrive or change RET or ENVID.
     *
     * @see IRecipientsBuilder#withDefaultDeliveryStatusNotificationNotifyOptions(NotifyOption...)
     * @see #clearingDeliveryStatusNotificationNotifyOptions()
     */
    @NotNull IRecipientBuilder withDeliveryStatusNotificationNotifyOptions(@NotNull NotifyOption @NotNull ... notifyOptions);

    /** Removes the local preference, allowing group defaults and then the Email fallback. This does not mean NEVER. */
    @NotNull IRecipientBuilder clearingDeliveryStatusNotificationNotifyOptions();

    /** Returns an immutable snapshot of the local NOTIFY preference; empty means inherit, not NEVER. */
    @NotNull Set<NotifyOption> getDeliveryStatusNotificationNotifyOptions();

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
