package org.simplejavamail.api.email;

import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.security.cert.X509Certificate;
import java.util.Collection;

/**
 * This builder is used to handle recipient data that may contain multiple recipients. This is possible in the case of a standard RFC 2822 {@code String} based
 * email address.
 * <p>
 * Produces a list of recipients.
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
     *
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
     *
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
     */
    @NotNull
    IRecipientsBuilder clearingSmimeCertificates();

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
     * Note that the email address must be a single address according to RFC2822 format. Name can be provided explicitly or as part of the RFC2822 email address
     * or omitted completely. If provided as method argument, the name overrides any nested name.
     *
     * @param name          Optional explicit name. Can be included in the email address instead, or omitted completely. A name will show as
     *                      {@code "Name Here <address@domain.com>"}
     * @param singleAddress A single address according to RFC2822 format with or without personal name.
     * @param recipientType Optional type of recipient. This is needed for TO, CC and BCC, but not for <em>bounceTo</em>, <em>returnReceiptTo</em>,
     *                      <em>replyTo</em>, <em>from</em> etc.
     */
    @NotNull
    IRecipientsBuilder withRecipient(@Nullable String name, boolean fixedName, @NotNull String singleAddress, @Nullable Message.RecipientType recipientType);

    /**
     * Adds a new {@link Recipient} instance as copy of the provided recipient (copying name, address and {@link Message.RecipientType}).
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
     * Delegates to {@link #withRecipient(Recipient)} for each address found in not just the collection, but also in every individual address string that is in
     * the collection.
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
     * Delegates to {@link #withRecipient(String, String, Message.RecipientType)} for each address in the provided collection.
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
