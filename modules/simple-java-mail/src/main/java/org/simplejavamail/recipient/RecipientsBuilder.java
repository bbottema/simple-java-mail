package org.simplejavamail.recipient;

import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.IRecipientsBuilder;
import org.simplejavamail.api.email.IRecipientsBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.Recipients;
import org.simplejavamail.internal.util.MiscUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static java.util.Arrays.asList;
import static org.simplejavamail.internal.util.MiscUtil.defaultTo;
import static org.simplejavamail.internal.util.MiscUtil.extractEmailAddresses;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;

/**
 * @see IRecipientsBuilder
 */
public abstract class RecipientsBuilder implements IRecipientsBuilder {

    @NotNull
    private final List<Recipient> recipients = new ArrayList<>();

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
            withRecipient(recipient.getName(), recipient.getAddress(), defaultTo(fixedRecipientType, recipient.getType()));
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
        recipients.add(new Recipient(recipient.getName(), recipient.getAddress(), recipient.getType(), null));
        return this;
    }
}