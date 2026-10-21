package org.simplejavamail.recipient.internal;

import jakarta.mail.Message.RecipientType;
import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.IRecipientBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption;
import org.simplejavamail.internal.util.DsnNotifyOptions;

import java.security.cert.X509Certificate;
import java.util.Set;

import static java.util.Arrays.asList;
import static java.util.Collections.emptySet;
import static org.simplejavamail.internal.util.MiscUtil.defaultTo;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;
import static org.simplejavamail.recipient.internal.RecipientException.MISSING_ADDRESS;

/**
 * @see IRecipientBuilder
 */
@ApiStatus.Internal
public class RecipientBuilderImpl implements IRecipientBuilder {

    /**
     * @see IRecipientBuilder#withName(String)
     */
    @Nullable
    private String name;

    /** Display name inferred from the current address, used only when no explicit name is supplied. */
    @Nullable
    private String addressName;

    /**
     * @see IRecipientBuilder#withAddress(String)
     */
    @Nullable
    private String address;

    /**
     * @see IRecipientBuilder#withType(Message.RecipientType)
     */
    @Nullable
    private RecipientType type;

    /**
     * @see IRecipientBuilder#withSmimeCertificate(X509Certificate)
     */
    @Nullable
    private X509Certificate smimeCertificate;

    /** @see IRecipientBuilder#withDeliveryStatusNotificationNotifyOptions(NotifyOption...) */
    @NotNull
    private Set<NotifyOption> deliveryStatusNotificationNotifyOptions = emptySet();

    /** @see IRecipientBuilder#to(String) */
    @Override
    @NotNull
    public Recipient to(@NotNull final String address) {
        return to(null, address);
    }

    /** @see IRecipientBuilder#cc(String) */
    @Override
    @NotNull
    public Recipient cc(@NotNull final String address) {
        return cc(null, address);
    }

    /** @see IRecipientBuilder#bcc(String) */
    @Override
    @NotNull
    public Recipient bcc(@NotNull final String address) {
        return bcc(null, address);
    }

    /** @see IRecipientBuilder#to(String, String) */
    @Override
    @NotNull
    public Recipient to(@Nullable final String name, @NotNull final String address) {
        return withName(name).withAddress(address).withType(RecipientType.TO).build();
    }

    /** @see IRecipientBuilder#cc(String, String) */
    @Override
    @NotNull
    public Recipient cc(@Nullable final String name, @NotNull final String address) {
        return withName(name).withAddress(address).withType(RecipientType.CC).build();
    }

    /** @see IRecipientBuilder#bcc(String, String) */
    @Override
    @NotNull
    public Recipient bcc(@Nullable final String name, @NotNull final String address) {
        return withName(name).withAddress(address).withType(RecipientType.BCC).build();
    }

    /**
     * @see IRecipientBuilder#build()
     */
    @Override
    @NotNull
    public Recipient build() {
        if (address == null) {
            throw new RecipientException(MISSING_ADDRESS);
        }
        return new Recipient(getName(), address, type, smimeCertificate, deliveryStatusNotificationNotifyOptions);
    }

    /**
     * @see IRecipientBuilder#withType(Message.RecipientType)
     */
    @Override
    public IRecipientBuilder withType(@NotNull RecipientType type) {
        this.type = checkNonEmptyArgument(type, "RecipientType");
        return this;
    }

    /**
     * @see IRecipientBuilder#withName(String)
     */
    @Override
    public IRecipientBuilder withName(@Nullable String name) {
        this.name = name;
        return this;
    }

    /**
     * @see IRecipientBuilder#withAddress(String)
     */
    @Override
    public IRecipientBuilder withAddress(@NotNull String address) {
        this.address = checkNonEmptyArgument(address, "address");
        this.addressName = null;
        final InternetAddress mailbox = MailboxInterpretation.singleMailbox(address);
        if (mailbox != null) {
            this.address = mailbox.getAddress();
            this.addressName = mailbox.getPersonal();
        }
        return this;
    }

    /**
     * @see IRecipientBuilder#withAddressOnlyFrom(InternetAddress)
     */
    @Override
    public IRecipientBuilder withAddressOnlyFrom(@NotNull InternetAddress address) {
        this.address = checkNonEmptyArgument(checkNonEmptyArgument(address, "address").getAddress(), "address");
        return this;
    }

    /**
     * @see IRecipientBuilder#withAddressOnlyFrom(String)
     */
    @Override
    public IRecipientBuilder withAddressOnlyFrom(@NotNull String address) {
        checkNonEmptyArgument(address, "address");
        final InternetAddress parsedAddress = MailboxInterpretation.singleMailbox(address);
        this.address = checkNonEmptyArgument(parsedAddress == null ? address : parsedAddress.getAddress(), "address");
        return this;
    }

    /**
     * @see IRecipientBuilder#withAddressAndNameOrDefault(InternetAddress, String)
     */
    @Override
    public IRecipientBuilder withAddressAndNameOrDefault(@NotNull InternetAddress address, @Nullable String defaultName) {
        this.addressName = null;
        return withAddressOnlyFrom(checkNonEmptyArgument(address, "address"))
                .withName(defaultTo(address.getPersonal(), defaultName));
    }

    /**
     * @see IRecipientBuilder#withAddressAndNameOrDefault(String, String)
     */
    @Override
    public IRecipientBuilder withAddressAndNameOrDefault(@NotNull String address, @Nullable String defaultName) {
        return interpretAddressData(defaultName, false, checkNonEmptyArgument(address, "address"));
    }

    /**
     * @see IRecipientBuilder#withAddressAndFixedNameOrProvided(InternetAddress, String)
     */
    @Override
    public IRecipientBuilder withAddressAndFixedNameOrProvided(@NotNull InternetAddress address, @Nullable String fixedName) {
        this.addressName = null;
        return withAddressOnlyFrom(checkNonEmptyArgument(address, "address"))
                .withName(defaultTo(fixedName, address.getPersonal()));
    }

    /**
     * @see IRecipientBuilder#withAddressAndFixedNameOrProvided(String, String)
     */
    @Override
    public IRecipientBuilder withAddressAndFixedNameOrProvided(@NotNull String address, @Nullable String fixedName) {
        return interpretAddressData(fixedName, true, checkNonEmptyArgument(address, "address"));
    }

    /**
     * @see IRecipientBuilder#withSmimeCertificate(X509Certificate)
     */
    @Override
    public IRecipientBuilder withSmimeCertificate(@NotNull X509Certificate smimeCertificate) {
        this.smimeCertificate = smimeCertificate;
        return this;
    }

    /**
     * @param name         The name to use as fixed name or as default (depending on <code>fixedName</code> flag). Regardless of that flag, if a name
     *                     is <code>null</code>, the other one will be used.
     * @param fixedName    Determines if the given name should be used as override.
     * @param emailAddress An address source that can contain a name. Unparseable sources are retained for normal send-time validation.
     */
    @NotNull
    private IRecipientBuilder interpretAddressData(@Nullable final String name, boolean fixedName, @NotNull final String emailAddress) {
        final InternetAddress parsedAddress = MailboxInterpretation.singleMailbox(emailAddress);
        if (parsedAddress == null) {
            return withName(name)
                    .withAddress(emailAddress);
        }
        return withName((fixedName || parsedAddress.getPersonal() == null)
                ? defaultTo(name, parsedAddress.getPersonal())
                : defaultTo(parsedAddress.getPersonal(), name))
                .withAddress(parsedAddress.getAddress());
    }

    /**
     * @see IRecipientBuilder#clearingSmimeCertificate()
     */
    @Override
    public IRecipientBuilder clearingSmimeCertificate() {
        this.smimeCertificate = null;
        return this;
    }

    /** @see IRecipientBuilder#withDeliveryStatusNotificationNotifyOptions(NotifyOption...) */
    @Override
    @NotNull
    public IRecipientBuilder withDeliveryStatusNotificationNotifyOptions(@NotNull final NotifyOption @NotNull ... notifyOptions) {
        deliveryStatusNotificationNotifyOptions = DsnNotifyOptions.copyOf(asList(notifyOptions));
        return this;
    }

    /** @see IRecipientBuilder#clearingDeliveryStatusNotificationNotifyOptions() */
    @Override
    @NotNull
    public IRecipientBuilder clearingDeliveryStatusNotificationNotifyOptions() {
        deliveryStatusNotificationNotifyOptions = emptySet();
        return this;
    }

    /** @see IRecipientBuilder#getDeliveryStatusNotificationNotifyOptions() */
    @Override
    @NotNull
    public Set<NotifyOption> getDeliveryStatusNotificationNotifyOptions() {
        return deliveryStatusNotificationNotifyOptions;
    }

    /**
     * @see IRecipientBuilder#getName()
     */
    @Override
    @Nullable
    public String getName() {
        return defaultTo(name, addressName);
    }

    /**
     * @see IRecipientBuilder#getAddress()
     */
    @Override
    @Nullable
    public String getAddress() {
        return address;
    }

    /**
     * @see IRecipientBuilder#getType()
     */
    @Override
    @Nullable
    public RecipientType getType() {
        return type;
    }

    /**
     * @see IRecipientBuilder#getSmimeCertificate()
     */
    @Override
    @Nullable
    public X509Certificate getSmimeCertificate() {
        return smimeCertificate;
    }
}
