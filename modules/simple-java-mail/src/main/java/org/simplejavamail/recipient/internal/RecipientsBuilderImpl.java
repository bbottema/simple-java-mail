package org.simplejavamail.recipient.internal;

import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.IRecipientsBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption;
import org.simplejavamail.internal.util.DsnNotifyOptions;
import org.simplejavamail.internal.configresolution.ConfigurationChoice;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static java.util.Arrays.asList;
import static java.util.Collections.emptySet;
import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * @see IRecipientsBuilder
 */
@ApiStatus.Internal
public class RecipientsBuilderImpl implements IRecipientsBuilder {

    private enum GroupPolicyMode {
        PRESERVE,
        DEFAULT,
        FIXED,
        CLEAR
    }

    @NotNull
    private final List<Recipient> recipients = new ArrayList<>();

    @Nullable
    private X509Certificate groupSmimeCertificate;

    @NotNull
    private GroupPolicyMode groupSmimeCertificateMode = GroupPolicyMode.PRESERVE;

    @NotNull
    private GroupPolicyMode groupNotifyMode = GroupPolicyMode.PRESERVE;

    @NotNull
    private Set<NotifyOption> groupNotifyOptions = emptySet();

    /**
     * @see IRecipientsBuilder#buildRecipients()
     */
    @Override
    @NotNull
    public Collection<Recipient> buildRecipients() {
        final List<Recipient> recipientsWithGroupPolicies = new ArrayList<>();
        for (final Recipient recipient : recipients) {
            recipientsWithGroupPolicies.add(copyRecipientApplyingGroupPolicies(recipient));
        }
        return unmodifiableList(recipientsWithGroupPolicies);
    }

    /**
     * @see IRecipientsBuilder#withDefaultSmimeCertificate(java.security.cert.X509Certificate)
     */
    @Override
    @NotNull
    public IRecipientsBuilder withDefaultSmimeCertificate(@NotNull final X509Certificate smimeCertificate) {
        this.groupSmimeCertificate = checkNonEmptyArgument(smimeCertificate, "smimeCertificate");
        this.groupSmimeCertificateMode = GroupPolicyMode.DEFAULT;
        return this;
    }

    /**
     * @see IRecipientsBuilder#withFixedSmimeCertificate(java.security.cert.X509Certificate)
     */
    @Override
    @NotNull
    public IRecipientsBuilder withFixedSmimeCertificate(@NotNull final X509Certificate smimeCertificate) {
        this.groupSmimeCertificate = checkNonEmptyArgument(smimeCertificate, "smimeCertificate");
        this.groupSmimeCertificateMode = GroupPolicyMode.FIXED;
        return this;
    }

    /**
     * @see IRecipientsBuilder#clearingSmimeCertificates()
     */
    @Override
    @NotNull
    public IRecipientsBuilder clearingSmimeCertificates() {
        this.groupSmimeCertificate = null;
        this.groupSmimeCertificateMode = GroupPolicyMode.CLEAR;
        return this;
    }

    /** @see IRecipientsBuilder#resetSmimeCertificatePolicy() */
    @Override
    @NotNull
    public IRecipientsBuilder resetSmimeCertificatePolicy() {
        groupSmimeCertificate = null;
        groupSmimeCertificateMode = GroupPolicyMode.PRESERVE;
        return this;
    }

    /** @see IRecipientsBuilder#withDefaultDeliveryStatusNotificationNotifyOptions(NotifyOption...) */
    @Override
    @NotNull
    public IRecipientsBuilder withDefaultDeliveryStatusNotificationNotifyOptions(@NotNull final NotifyOption @NotNull ... notifyOptions) {
        groupNotifyOptions = DsnNotifyOptions.copyOf(asList(notifyOptions));
        groupNotifyMode = GroupPolicyMode.DEFAULT;
        return this;
    }

    /** @see IRecipientsBuilder#withFixedDeliveryStatusNotificationNotifyOptions(NotifyOption...) */
    @Override
    @NotNull
    public IRecipientsBuilder withFixedDeliveryStatusNotificationNotifyOptions(@NotNull final NotifyOption @NotNull ... notifyOptions) {
        groupNotifyOptions = DsnNotifyOptions.copyOf(asList(notifyOptions));
        groupNotifyMode = GroupPolicyMode.FIXED;
        return this;
    }

    /** @see IRecipientsBuilder#clearingDeliveryStatusNotificationNotifyOptions() */
    @Override
    @NotNull
    public IRecipientsBuilder clearingDeliveryStatusNotificationNotifyOptions() {
        groupNotifyOptions = emptySet();
        groupNotifyMode = GroupPolicyMode.CLEAR;
        return this;
    }

    /** @see IRecipientsBuilder#resetDeliveryStatusNotificationNotifyPolicy() */
    @Override
    @NotNull
    public IRecipientsBuilder resetDeliveryStatusNotificationNotifyPolicy() {
        groupNotifyOptions = emptySet();
        groupNotifyMode = GroupPolicyMode.PRESERVE;
        return this;
    }

    /**
     * @see IRecipientsBuilder#withRecipientsWithDefaultName(String, Collection, Message.RecipientType)
     */
    @Override
    @NotNull
    public IRecipientsBuilder withRecipientsWithDefaultName(@Nullable final String defaultName, @NotNull Collection<String> oneOrMoreAddressesEach, @Nullable Message.RecipientType recipientType) {
        return withRecipients(defaultName, false, oneOrMoreAddressesEach, recipientType);
    }

    /**
     * @see IRecipientsBuilder#withRecipientsWithFixedName(String, Collection, Message.RecipientType)
     */
    @Override
    @NotNull
    public IRecipientsBuilder withRecipientsWithFixedName(@Nullable final String fixedName, @NotNull Collection<String> oneOrMoreAddressesEach, @Nullable Message.RecipientType recipientType) {
        return withRecipients(fixedName, true, oneOrMoreAddressesEach, recipientType);
    }

    /**
     * @see IRecipientsBuilder#withRecipientsWithDefaultName(String, Message.RecipientType, String...)
     */
    @Override
    @NotNull
    public IRecipientsBuilder withRecipientsWithDefaultName(@Nullable String name, @Nullable Message.RecipientType recipientType, @NotNull String @NotNull ... oneOrMoreAddressesEach) {
        return withRecipients(name, false, asList(oneOrMoreAddressesEach), recipientType);
    }

    /**
     * @see IRecipientsBuilder#withRecipientsWithFixedName(String, Message.RecipientType, String...)
     */
    @Override
    @NotNull
    public IRecipientsBuilder withRecipientsWithFixedName(@Nullable String name, @Nullable Message.RecipientType recipientType, @NotNull String @NotNull ... oneOrMoreAddressesEach) {
        return withRecipients(name, true, asList(oneOrMoreAddressesEach), recipientType);
    }

    /**
     * @see IRecipientsBuilder#withRecipients(String, boolean, Message.RecipientType, String...)
     */
    @Override
    @NotNull
    public IRecipientsBuilder withRecipients(@Nullable String name, boolean fixedName, @Nullable Message.RecipientType recipientType, @NotNull String @NotNull ... oneOrMoreAddressesEach) {
        return withRecipients(name, fixedName, asList(oneOrMoreAddressesEach), recipientType);
    }

    /**
     * @see IRecipientsBuilder#withRecipients(String, boolean, Collection, Message.RecipientType)
     */
    @Override
    @NotNull
    public IRecipientsBuilder withRecipients(@Nullable String name, boolean fixedName, @NotNull Collection<String> oneOrMoreAddressesEach, @Nullable Message.RecipientType recipientType) {
        for (String oneOrMoreAddresses : oneOrMoreAddressesEach) {
            recipients.addAll(MailboxInterpretation.interpretList(name, fixedName, oneOrMoreAddresses, recipientType, "oneOrMoreAddresses"));
        }
        return this;
    }

    /**
     * @see IRecipientsBuilder#withRecipientsFromAddressesWithDefaultName(String, Collection, Message.RecipientType)
     */
    @Override
    @NotNull
    public IRecipientsBuilder withRecipientsFromAddressesWithDefaultName(@Nullable final String defaultName, @NotNull Collection<InternetAddress> addresses, @Nullable Message.RecipientType recipientType) {
        return withRecipientsFromAddresses(defaultName, false, addresses, recipientType);
    }

    /**
     * @see IRecipientsBuilder#withRecipientsFromAddressesWithFixedName(String, Collection, Message.RecipientType)
     */
    @Override
    @NotNull
    public IRecipientsBuilder withRecipientsFromAddressesWithFixedName(@Nullable final String fixedName, @NotNull Collection<InternetAddress> addresses, @Nullable Message.RecipientType recipientType) {
        return withRecipientsFromAddresses(fixedName, true, addresses, recipientType);
    }

    /**
     * @see IRecipientsBuilder#withRecipientsFromAddresses(String, boolean, Collection, Message.RecipientType)
     */
    @Override
    @NotNull
    public IRecipientsBuilder withRecipientsFromAddresses(@Nullable String name, boolean fixedName, @NotNull Collection<InternetAddress> addresses, @Nullable Message.RecipientType recipientType) {
        for (InternetAddress address : addresses) {
            recipients.addAll(MailboxInterpretation.interpretList(name, fixedName, address, recipientType, "address"));
        }
        return this;
    }

    /**
     * @see IRecipientsBuilder#withRecipients(Collection)
     */
    @Override
    public IRecipientsBuilder withRecipients(@NotNull final Collection<Recipient> recipients) {
        return withRecipients(recipients, null);
    }

    /**
     * @see IRecipientsBuilder#withRecipients(Recipient...)
     */
    @Override
    public IRecipientsBuilder withRecipients(@NotNull final Recipient @NotNull ... recipients) {
        return withRecipients(asList(recipients), null);
    }

    /**
     * @see IRecipientsBuilder#withRecipients(Collection, Message.RecipientType)
     */
    @Override
    @NotNull
    public IRecipientsBuilder withRecipients(@NotNull Collection<Recipient> recipients, @Nullable Message.RecipientType fixedRecipientType) {
        for (Recipient recipient : recipients) {
            withRecipient(new Recipient(recipient.getName(), recipient.getAddress(), RecipientConfigurationResolver.select("recipient.type", fixedRecipientType, recipient.getType(), null),
                    recipient.getSmimeCertificate(), recipient.getDeliveryStatusNotificationNotifyOptions()));
        }
        return this;
    }

    /**
     * @see IRecipientsBuilder#withRecipient(String, Message.RecipientType)
     */
    @Override
    public IRecipientsBuilder withRecipient(@NotNull final String singleAddress, @Nullable final Message.RecipientType recipientType) {
        return withRecipient(null, singleAddress, recipientType);
    }

    /**
     * @see IRecipientsBuilder#withRecipient(String, String, Message.RecipientType)
     */
    @Override
    public IRecipientsBuilder withRecipient(@Nullable final String name, @NotNull final String singleAddress, @Nullable final Message.RecipientType recipientType) {
        return withRecipient(name, true, singleAddress, recipientType);
    }

    /**
     * @see IRecipientsBuilder#withRecipient(String, boolean, String, Message.RecipientType)
     */
    @Override
    public IRecipientsBuilder withRecipient(@Nullable final String name, boolean fixedName, @NotNull final String singleAddress, @Nullable final Message.RecipientType recipientType) {
        final Recipient interpreted = new RecipientBuilderImpl().withAddressAndNameOrDefault(singleAddress, name).build();
        recipients.add(new Recipient(RecipientConfigurationResolver.select("recipient.name", fixedName ? name : null, interpreted.getName(), null), interpreted.getAddress(), recipientType, null));
        return this;
    }

    /**
     * @see IRecipientsBuilder#withRecipient(Recipient)
     */
    @Override
    public IRecipientsBuilder withRecipient(@NotNull final Recipient recipient) {
        recipients.add(requireNonNull(recipient, "recipient"));
        return this;
    }

    @NotNull
    private Recipient copyRecipientApplyingGroupPolicies(@NotNull final Recipient recipient) {
        return new Recipient(recipient.getName(), recipient.getAddress(), recipient.getType(), resolveSmimeCertificate(recipient),
                resolveNotifyOptions(recipient));
    }

    @NotNull
    private Set<NotifyOption> resolveNotifyOptions(@NotNull final Recipient recipient) {
        final ConfigurationChoice<Set<NotifyOption>> fixed = groupNotifyMode == GroupPolicyMode.CLEAR ? ConfigurationChoice.remove()
                : groupNotifyMode == GroupPolicyMode.FIXED ? ConfigurationChoice.set(groupNotifyOptions) : ConfigurationChoice.inherit();
        final Set<NotifyOption> selected = RecipientConfigurationResolver.select("recipient.notify", fixed,
                recipient.getDeliveryStatusNotificationNotifyOptions(),
                groupNotifyMode != GroupPolicyMode.DEFAULT || !recipient.getDeliveryStatusNotificationNotifyOptions().isEmpty(),
                groupNotifyMode == GroupPolicyMode.DEFAULT ? groupNotifyOptions : null);
        return selected == null ? emptySet() : selected;
    }

    @Nullable
    private X509Certificate resolveSmimeCertificate(@NotNull final Recipient recipient) {
        final ConfigurationChoice<X509Certificate> fixed = groupSmimeCertificateMode == GroupPolicyMode.CLEAR ? ConfigurationChoice.remove()
                : groupSmimeCertificateMode == GroupPolicyMode.FIXED ? ConfigurationChoice.set(groupSmimeCertificate) : ConfigurationChoice.inherit();
        return RecipientConfigurationResolver.select("recipient.smimeCertificate", fixed, recipient.getSmimeCertificate(), true,
                groupSmimeCertificateMode == GroupPolicyMode.DEFAULT ? groupSmimeCertificate : null);
    }
}
