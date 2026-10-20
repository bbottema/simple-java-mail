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
     * Creates one TO recipient, using an embedded display name when present. Removes a previous explicit name and retains other recipient options.
     * The address remains one value; address lists are not split.
     * @see #to(String, String)
     */
    @NotNull Recipient to(@NotNull String address);

    /**
     * Creates one CC recipient, using an embedded display name when present. Removes a previous explicit name and retains other recipient options.
     * The address remains one value; address lists are not split.
     * @see #cc(String, String)
     */
    @NotNull Recipient cc(@NotNull String address);

    /**
     * Creates one BCC recipient, using an embedded display name when present. Removes a previous explicit name and retains other recipient options.
     * The address remains one value; address lists are not split.
     * @see #bcc(String, String)
     */
    @NotNull Recipient bcc(@NotNull String address);

    /**
     * Creates one TO recipient directly from a name and an address.
     * <p>
     * This is a shortcut for setting the name, address and type on this builder and calling {@link #build()}. Any S/MIME certificate or delivery
     * notification preference already configured on the builder is retained. A null name removes a previously configured explicit name and allows an
     * embedded display name. Comma- or semicolon-separated address lists are not split. Use {@link IRecipientsBuilder} for multiple addresses.
     *
     * @param name Optional explicit display name.
     * @param address One email address, validated as with {@link #withAddress(String)}.
     * @return The completed TO recipient.
     * @see #cc(String, String)
     * @see #bcc(String, String)
     */
    @NotNull Recipient to(@Nullable String name, @NotNull String address);

    /**
     * Creates one CC recipient directly from a name and an address.
     * <p>
     * Like {@link #to(String, String)}, this replaces the builder's name, address and type, retains its other configured options and returns the
     * completed recipient. A null name removes a previously configured explicit name and allows an embedded name; address lists are not split.
     *
     * @param name Optional explicit display name.
     * @param address One email address, validated as with {@link #withAddress(String)}.
     * @return The completed CC recipient.
     * @see #to(String, String)
     */
    @NotNull Recipient cc(@Nullable String name, @NotNull String address);

    /**
     * Creates one BCC recipient directly from a name and an address.
     * <p>
     * Like {@link #to(String, String)}, this replaces the builder's name, address and type, retains its other configured options and returns the
     * completed recipient. A null name removes a previously configured explicit name and allows an embedded name; address lists are not split.
     *
     * @param name Optional explicit display name.
     * @param address One email address, validated as with {@link #withAddress(String)}.
     * @return The completed BCC recipient.
     * @see #to(String, String)
     */
    @NotNull Recipient bcc(@Nullable String name, @NotNull String address);

    /**
     * @param type The recipient type (e.g. {@link Message.RecipientType#TO}), optional for {@code from} and {@code replyTo} fields.
     */
    IRecipientBuilder withType(@NotNull Message.RecipientType type);

    /**
     * Supplies the explicit display name independently of the address. It wins over an embedded name regardless of setter order.
     * A null value removes the explicit name and reveals any name inferred from the current address.
     *
     * @param name Optional explicit name of the recipient, otherwise taken from inside the address (if provided, for example "Joe Sixpack
     *             &lt;joesixpack@beerme.com&gt;"). Note that in {@link Recipients}, this can still be overridden by the {@code defaultName} and
     *             {@code overridingName} fields.
     */
    IRecipientBuilder withName(@Nullable String name);

    /**
     * Accepts one mailbox leniently, extracting its address and embedded display name. A name supplied separately with {@link #withName(String)}
     * takes precedence. Replacing the address replaces its inferred name, retaining the explicit name and other recipient options.
     * Unparseable input, groups and multiple-mailbox strings remain intact for normal send-time validation rather than being split or truncated.
     *
     * @param address The nonempty recipient address, optionally including a display name, such as {@code Alice <alice@example.org>}.
     * @see #withAddressOnlyFrom(String)
     */
    IRecipientBuilder withAddress(@NotNull String address);

    /**
     * Sets only the address from the supplied value, leaving this builder's configured name and other options unchanged.
     * An embedded display name is ignored.
     * @see #withAddress(String)
     */
    IRecipientBuilder withAddressOnlyFrom(@NotNull InternetAddress address);

    /**
     * Extracts only the email address, leaving this builder's configured name and other options unchanged.
     * For example, {@code "Alice <alice@example.org>"} supplies {@code alice@example.org}, not the name Alice.
     * If the source cannot be parsed, it is retained for the normal send-time validator rather than rejected while constructing a recipient.
     * @see #withAddressOnlyFrom(InternetAddress)
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
     * The bundled managed Angus transport supports them, including with custom socket factories on library-owned Sessions.
     * Ordinary Angus transports from caller-owned Sessions bypass the managed command hook.
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
