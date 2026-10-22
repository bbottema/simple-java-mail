package org.simplejavamail.api.email;

import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption;

import java.security.cert.X509Certificate;
import java.util.Collection;

/**
 * Produces a flat, ordered list of recipients from mailbox strings, address groups or prepared recipients.
 * <p>
 * Every String collection/varargs entry may itself contain a complete mailbox list. Parsing follows the same lenient rules as
 * {@link EmailPopulatingBuilder#to(String)}: commas and legacy semicolon separators are supported without splitting quoted names or comments.
 * RFC address groups expand to their members; an empty group adds nothing. Order and duplicate occurrences are retained.
 * Nonempty unparseable source remains intact for the configured send-time validator; parsing does not check delivery or query DNS.
 * Default names fill missing names; fixed names replace embedded names when supplied. Group certificate/notification policies apply after parsing.
 * Prepared {@link Recipient} values are not reparsed and retain their metadata unless a group policy explicitly replaces it.
 */
public interface IRecipientsBuilder {

    @NotNull
    Collection<Recipient> buildRecipients();

    /**
     * Applies the given S/MIME certificate to recipients produced by this builder that do not already have a recipient-specific certificate.
     * <p>
     * This is useful for group-level defaults: a mailing list, department or shared mailbox can have a certificate while still allowing individual
     * recipients to override it with their own certificate.
     * <p>
     * This is mutually exclusive with {@link #withFixedSmimeCertificate(X509Certificate)} and {@link #clearingSmimeCertificates()}; the last group S/MIME
     * policy configured on this builder wins.
     * Use {@link #resetSmimeCertificatePolicy()} to stop applying a group rule and keep each original recipient's certificate instead, even after
     * a clear policy. Resetting does not strip certificates or change the group's notification rule, source recipients or already built lists.
     * Null is rejected without changing the previous certificate or policy. The supplied certificate is stored without cryptographic validation.
     *
     * @param smimeCertificate required certificate to use where a recipient has none
     * @throws IllegalArgumentException if the certificate is null
     * @see IRecipientBuilder#withSmimeCertificate(X509Certificate)
     */
    @NotNull
    IRecipientsBuilder withDefaultSmimeCertificate(@NotNull X509Certificate smimeCertificate);

    /**
     * Applies the given S/MIME certificate to all recipients produced by this builder, overriding any recipient-specific certificate already present.
     * <p>
     * This is useful when a group should always use one certificate regardless of the source recipient data.
     * <p>
     * This is mutually exclusive with {@link #withDefaultSmimeCertificate(X509Certificate)} and {@link #clearingSmimeCertificates()}; the last group S/MIME
     * policy configured on this builder wins.
     * Null is rejected without changing the previous certificate or policy. Use {@link #clearingSmimeCertificates()} to strip certificates or
     * {@link #resetSmimeCertificatePolicy()} to preserve the original recipients' certificates. The supplied certificate is stored without cryptographic validation.
     *
     * @param smimeCertificate required certificate to use for every recipient
     * @throws IllegalArgumentException if the certificate is null
     * @see IRecipientBuilder#withSmimeCertificate(X509Certificate)
     */
    @NotNull
    IRecipientsBuilder withFixedSmimeCertificate(@NotNull X509Certificate smimeCertificate);

    /**
     * Clears all S/MIME certificates from recipients produced by this builder, including certificates copied from source {@link Recipient} objects and
     * previously configured group-level certificate defaults or fixed values.
     * <p>
     * This is useful when reusing recipients that may already carry certificate state, but the current email should rely on the email-level or mailer-level
     * S/MIME encryption fallback instead.
     * <p>
     * This is mutually exclusive with {@link #withDefaultSmimeCertificate(X509Certificate)} and {@link #withFixedSmimeCertificate(X509Certificate)}; the last
     * group S/MIME policy configured on this builder wins.
     *
     * @see IRecipientBuilder#clearingSmimeCertificate()
     * @see EmailPopulatingBuilder#clearSmime()
     * @see #resetSmimeCertificatePolicy()
     */
    @NotNull
    IRecipientsBuilder clearingSmimeCertificates();

    /**
     * Restores the original recipients' certificates rather than stripping them. Repeating the reset has no additional effect.
     *
     * @see #withDefaultSmimeCertificate(X509Certificate)
     * @see #withFixedSmimeCertificate(X509Certificate)
     */
    @NotNull
    IRecipientsBuilder resetSmimeCertificatePolicy();

    /**
     * Fills missing recipient NOTIFY preferences when building the flat group. Existing recipient preferences win; a complete set replaces
     * the Email fallback rather than merging events. The last default/fixed/clear group NOTIFY policy wins, independently of S/MIME policy.
     * An empty array supplies no default. See the recipient method for validation and provider-support requirements.
     * Use {@link #resetDeliveryStatusNotificationNotifyPolicy()} to stop applying a group rule and keep the original recipient preferences,
     * even after a clear policy. Resetting does not replace those preferences with the Email fallback or change the group's certificate rule,
     * source recipients or already built lists.
     * @see IRecipientBuilder#withDeliveryStatusNotificationNotifyOptions(NotifyOption...)
     */
    @NotNull IRecipientsBuilder withDefaultDeliveryStatusNotificationNotifyOptions(@NotNull NotifyOption @NotNull ... notifyOptions);

    /**
     * Replaces NOTIFY preferences on every produced recipient, including explicit source preferences. The last default/fixed/clear group policy wins.
     * An empty array clears recipient preferences and restores the Email fallback; NEVER requests no notifications instead.
     * @see IRecipientBuilder#withDeliveryStatusNotificationNotifyOptions(NotifyOption...)
     */
    @NotNull IRecipientsBuilder withFixedDeliveryStatusNotificationNotifyOptions(@NotNull NotifyOption @NotNull ... notifyOptions);

    /**
     * Strips copied recipient preferences and any group default/fixed NOTIFY policy from the produced recipients, restoring the Email fallback.
     * Source recipients remain unchanged. This is not NEVER and does not clear S/MIME certificates.
     * @see #resetDeliveryStatusNotificationNotifyPolicy()
     */
    @NotNull IRecipientsBuilder clearingDeliveryStatusNotificationNotifyOptions();

    /**
     * Restores the original recipients' notification preferences rather than stripping them. Repeating the reset has no additional effect.
     *
     * @see #withDefaultDeliveryStatusNotificationNotifyOptions(NotifyOption...)
     * @see #withFixedDeliveryStatusNotificationNotifyOptions(NotifyOption...)
     */
    @NotNull
    IRecipientsBuilder resetDeliveryStatusNotificationNotifyPolicy();

    /**
     * Delegates to {@link #withRecipient(String, String, Message.RecipientType)} with the name omitted.
     */
    @NotNull
    IRecipientsBuilder withRecipient(@NotNull String singleAddress, @Nullable Message.RecipientType recipientType);

    /**
     * Delegates to {@link #withRecipient(String, boolean, String, Message.RecipientType)} with the name omitted and fixedName = true.
     */
    @NotNull
    IRecipientsBuilder withRecipient(@Nullable String name, @NotNull String singleAddress, @Nullable Message.RecipientType recipientType);

    /**
     * Adds a new {@link Recipient} instance with the given name, address and {@link Message.RecipientType}.
     * <p>
     * This singular method does not split lists or groups and never selects only their first mailbox. Nonempty unparseable, plural or group source
     * remains intact for normal send-time validation. Use {@link #withRecipients(String, boolean, Collection, Message.RecipientType)} to expand lists.
     *
     * @param name          Optional explicit name. Can be included in the email address instead, or omitted completely. A name will show as
     *                      {@code "Name Here <address@domain.com>"}
     * @param fixedName     When true, a non-null name replaces the embedded name; otherwise it fills only missing names.
     * @param singleAddress A single address according to RFC2822 format with or without personal name.
     * @param recipientType Optional type of recipient. This is needed for TO, CC and BCC, but not for <em>bounceTo</em>, <em>returnReceiptTo</em>,
     *                      <em>replyTo</em>, <em>from</em> etc.
     */
    @NotNull
    IRecipientsBuilder withRecipient(@Nullable String name, boolean fixedName, @NotNull String singleAddress, @Nullable Message.RecipientType recipientType);

    /**
     * Adds the immutable recipient, retaining its name, address, type, S/MIME certificate and NOTIFY preference. Group policies apply when
     * building the recipient list; the supplied recipient is not changed.
     * <p>
     * Note that the email address must be a single address according to RFC2822 format. Name can be provided explicitly or as part of the RFC2822 email address
     * or omitted completely.
     */
    @NotNull
    IRecipientsBuilder withRecipient(@NotNull Recipient recipient);

    /**
     * Delegates to {@link #withRecipients(String, boolean, Collection, Message.RecipientType)}, leaving existing names intact and defaulting when missing.
     */
    @NotNull
    IRecipientsBuilder withRecipientsWithDefaultName(@Nullable String defaultName, @NotNull Collection<String> oneOrMoreAddressesEach, @Nullable Message.RecipientType recipientType);

    /**
     * Delegates to {@link #withRecipients(String, boolean, Collection, Message.RecipientType)}, with <code>fixedName=true</code> assigning or overwriting
     * existing names with the provided name.
     */
    @NotNull
    IRecipientsBuilder withRecipientsWithFixedName(@Nullable String fixedName, @NotNull Collection<String> oneOrMoreAddressesEach, @Nullable Message.RecipientType recipientType);

    /**
     * Delegates to {@link #withRecipients(String, boolean, Collection, Message.RecipientType)}.
     */
    @NotNull
    IRecipientsBuilder withRecipientsWithDefaultName(@Nullable String name, @Nullable Message.RecipientType recipientType, @NotNull String @NotNull ... oneOrMoreAddressesEach);

    /**
     * Delegates to {@link #withRecipients(String, boolean, Collection, Message.RecipientType)}.
     */
    @NotNull
    IRecipientsBuilder withRecipientsWithFixedName(@Nullable String name, @Nullable Message.RecipientType recipientType, @NotNull String @NotNull ... oneOrMoreAddressesEach);

    /**
     * Delegates to {@link #withRecipients(String, boolean, Collection, Message.RecipientType)}.
     */
    @NotNull
    IRecipientsBuilder withRecipients(@Nullable String name, boolean fixedName, @Nullable Message.RecipientType recipientType, @NotNull String @NotNull ... oneOrMoreAddressesEach);

    /**
     * Parses every complete mailbox list in the collection before applying the supplied name policy. Quoted punctuation, comments and escapes
     * stay with their mailbox; RFC groups expand to members. Preserves iteration order, duplicate occurrences and nonempty unparseable source.
     *
     * @param fixedName              Indicates whether the provided name should be applied to all addresses, or only to those where a name is missing.
     * @param oneOrMoreAddressesEach Collection of addresses. Each entry itself can be a delimited list of RFC2822 addresses. Examples:
     *                               <ul>
     *                               <li>lolly.pop@pretzelfun.com</li>
     *                               <li>Moonpie &lt;moonpie@pies.com&gt;;Daisy &lt;daisy@pies.com&gt;</li>
     *                               <li>a1@b1.c1,a2@b2.c2,a3@b3.c3</li>
     *                               </ul>
     */
    @NotNull
    IRecipientsBuilder withRecipients(@Nullable String name, boolean fixedName, @NotNull Collection<String> oneOrMoreAddressesEach, @Nullable Message.RecipientType recipientType);

    /**
     * Delegates to {@link #withRecipientsFromAddresses(String, boolean, Collection, Message.RecipientType)}, leaving existing names intact and defaulting when
     * missing.
     */
    @NotNull
    IRecipientsBuilder withRecipientsFromAddressesWithDefaultName(@Nullable String defaultName, @NotNull Collection<InternetAddress> addresses, @Nullable Message.RecipientType recipientType);

    /**
     * Delegates to {@link #withRecipientsFromAddresses(String, boolean, Collection, Message.RecipientType)}, with <code>fixedName=true</code> assigning or
     * overwriting existing names with the provided name.
     */
    @NotNull
    IRecipientsBuilder withRecipientsFromAddressesWithFixedName(@Nullable String fixedName, @NotNull Collection<InternetAddress> addresses, @Nullable Message.RecipientType recipientType);

    /**
     * Interprets every supplied address, expanding RFC groups into members with the same rules as the String list inputs.
     * Group labels do not become member names. Applies the supplied name policy to each mailbox without changing the input objects.
     *
     * @param fixedName Indicates whether the provided name should be applied to all addresses, or only to those where a name is missing.
     */
    @NotNull
    IRecipientsBuilder withRecipientsFromAddresses(@Nullable String name, boolean fixedName, @NotNull Collection<InternetAddress> addresses, @Nullable Message.RecipientType recipientType);

    /**
     * Delegates to {@link #withRecipients(Collection, Message.RecipientType)} with {@link Message.RecipientType} left empty (so it will use the original
     * values).
     */
    @NotNull
    IRecipientsBuilder withRecipients(@NotNull Collection<Recipient> recipients);

    /**
     * Delegates to {@link #withRecipients(Collection, Message.RecipientType)} with {@link Message.RecipientType} left empty (so it will use the original
     * values).
     */
    @NotNull
    IRecipientsBuilder withRecipients(@NotNull Recipient @NotNull ... recipients);

    /**
     * Delegates to {@link #withRecipient(String, String, Message.RecipientType)} for each recipient in the provided collection, optionally fixing the
     * recipientType for all recipients to the provided type.
     *
     * @param fixedRecipientType Optional. Fixes all recipients to the given type. If omitted, the types are not removed, but kept as-is.
     */
    @NotNull
    IRecipientsBuilder withRecipients(@NotNull Collection<Recipient> recipients, @Nullable Message.RecipientType fixedRecipientType);
}
