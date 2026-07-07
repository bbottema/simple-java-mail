package org.simplejavamail.recipient;

import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.IRecipientsBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.internal.util.MiscUtil;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableList;
import static org.simplejavamail.internal.util.MiscUtil.defaultTo;
import static org.simplejavamail.internal.util.MiscUtil.extractEmailAddresses;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;

/**
 * @see IRecipientsBuilder
 */
public class RecipientsBuilder implements IRecipientsBuilder {

    private enum GroupSmimeCertificateMode {
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
    private GroupSmimeCertificateMode groupSmimeCertificateMode = GroupSmimeCertificateMode.PRESERVE;

    /**
     * @see IRecipientsBuilder#buildRecipients()
     */
    @Override
    @NotNull
    public Collection<Recipient> buildRecipients() {
        List<Recipient> recipientsWithGroupDefaults = new ArrayList<>();
        for (Recipient recipient : recipients) {
            recipientsWithGroupDefaults.add(copyRecipientApplyingGroupSmimeCertificate(recipient));
        }
        return unmodifiableList(recipientsWithGroupDefaults);
    }

    /**
     * @see IRecipientsBuilder#withDefaultSmimeCertificate(X509Certificate)
     */
    @Override
    @NotNull
    public IRecipientsBuilder withDefaultSmimeCertificate(@NotNull final X509Certificate smimeCertificate) {
        this.groupSmimeCertificate = smimeCertificate;
        this.groupSmimeCertificateMode = GroupSmimeCertificateMode.DEFAULT;
        return this;
    }

    /**
     * @see IRecipientsBuilder#withFixedSmimeCertificate(X509Certificate)
     */
    @Override
    @NotNull
    public IRecipientsBuilder withFixedSmimeCertificate(@NotNull final X509Certificate smimeCertificate) {
        this.groupSmimeCertificate = smimeCertificate;
        this.groupSmimeCertificateMode = GroupSmimeCertificateMode.FIXED;
        return this;
    }

    /**
     * @see IRecipientsBuilder#clearingSmimeCertificates()
     */
    @Override
    @NotNull
    public IRecipientsBuilder clearingSmimeCertificates() {
        this.groupSmimeCertificate = null;
        this.groupSmimeCertificateMode = GroupSmimeCertificateMode.CLEAR;
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
            for (String emailAddress : extractEmailAddresses(oneOrMoreAddresses)) {
                withRecipient(name, fixedName, emailAddress, recipientType);
            }
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
            String effectiveName = (fixedName || valueNullOrEmpty(address.getPersonal())) ? name : address.getPersonal();
            withRecipient(effectiveName, address.getAddress(), recipientType);
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
            withRecipient(new Recipient(recipient.getName(), recipient.getAddress(), defaultTo(fixedRecipientType, recipient.getType()), recipient.getSmimeCertificate()));
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
        try {
            recipients.add(MiscUtil.interpretRecipient(name, fixedName, singleAddress, recipientType));
        } catch (Exception e){
            // assume recipient was malformed and simply ignore it
        }
        return this;
    }

    /**
     * @see IRecipientsBuilder#withRecipient(Recipient)
     */
    @Override
    public IRecipientsBuilder withRecipient(@NotNull final Recipient recipient) {
        recipients.add(new Recipient(recipient.getName(), recipient.getAddress(), recipient.getType(), recipient.getSmimeCertificate()));
        return this;
    }

    @NotNull
    private Recipient copyRecipientApplyingGroupSmimeCertificate(@NotNull final Recipient recipient) {
        return new Recipient(recipient.getName(), recipient.getAddress(), recipient.getType(), resolveSmimeCertificate(recipient));
    }

    @Nullable
    private X509Certificate resolveSmimeCertificate(@NotNull final Recipient recipient) {
        switch (groupSmimeCertificateMode) {
            case DEFAULT:
                return defaultTo(recipient.getSmimeCertificate(), groupSmimeCertificate);
            case FIXED:
                return groupSmimeCertificate;
            case CLEAR:
                return null;
            case PRESERVE:
            default:
                return recipient.getSmimeCertificate();
        }
    }
}
