package org.simplejavamail.email.internal;

import jakarta.mail.Message.RecipientType;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Recipient;

import java.util.ArrayList;
import java.util.List;

import static java.util.Collections.singletonList;
import static org.simplejavamail.internal.util.MiscUtil.extractEmailAddresses;

import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/** Mailbox interpretation for Email conveniences; independent of the Recipient builders' parsing contracts. */
final class EmailAddressConvenience {

    private EmailAddressConvenience() { }

    @NotNull
    static List<Recipient> interpretList(@Nullable final String name, @NotNull final String source,
                                         @Nullable final RecipientType type, @NotNull final String parameterName) {
        checkNonEmptyArgument(source, parameterName);
        final String explicitName = nonblank(name);
        try {
            final List<Recipient> recipients = new ArrayList<>();
            final InternetAddress[] addresses = parseList(source);
            appendMailboxes(recipients, addresses, explicitName, type);
            // Whitespace/comments without a mailbox remain available to the configured validator.
            return addresses.length == 0
                    ? singletonList(new Recipient(explicitName, source, type, null)) : recipients;
        } catch (final AddressException unparseable) {
            return singletonList(new Recipient(explicitName, source, type, null));
        }
    }

    @NotNull
    static List<Recipient> interpretList(@Nullable final String name, @NotNull final InternetAddress source,
                                         @Nullable final RecipientType type, @NotNull final String parameterName) {
        checkNonEmptyArgument(source, parameterName);
        final String explicitName = nonblank(name);
        return interpretList(explicitName != null ? explicitName : source.getPersonal(), source.getAddress(), type, parameterName);
    }

    private static InternetAddress[] parseList(final String source) throws AddressException {
        try {
            return InternetAddress.parse(source, false);
        } catch (final AddressException notAnRfcList) {
            // Retain the established semicolon convenience without splitting punctuation inside display names.
            return InternetAddress.parse(String.join(",", extractEmailAddresses(source)), false);
        }
    }

    private static void appendMailboxes(final List<Recipient> target, final InternetAddress[] addresses,
                                         @Nullable final String explicitName, @Nullable final RecipientType type) throws AddressException {
        for (final InternetAddress address : addresses) {
            if (address.isGroup()) {
                appendMailboxes(target, address.getGroup(false), explicitName, type);
            } else {
                target.add(new Recipient(explicitName != null ? explicitName : nonblank(address.getPersonal()), address.getAddress(), type, null));
            }
        }
    }

    @NotNull
    static Recipient interpret(@Nullable final String name, @NotNull final String source,
                               @Nullable final RecipientType type, @NotNull final String parameterName) {
        checkNonEmptyArgument(source, parameterName);
        final String explicitName = nonblank(name);
        try {
            final InternetAddress[] parsed = InternetAddress.parse(source, false);
            if (parsed.length > 1 || (parsed.length == 1 && parsed[0].isGroup())) {
                throw new IllegalArgumentException(parameterName
                        + " must contain one mailbox; use mail.recipientsBuilder() for multiple addresses or address groups");
            }
            if (parsed.length == 1) {
                return new Recipient(explicitName != null ? explicitName : nonblank(parsed[0].getPersonal()),
                        parsed[0].getAddress(), type, null);
            }
        } catch (final AddressException unparseable) {
            // Retain malformed nonempty input for the configured send-time validator, as with zero parsed mailboxes.
        }
        return new Recipient(explicitName, source, type, null);
    }

    @NotNull
    static Recipient interpret(@Nullable final String name, @NotNull final InternetAddress source,
                               @Nullable final RecipientType type, @NotNull final String parameterName) {
        checkNonEmptyArgument(source, parameterName);
        final String explicitName = nonblank(name);
        return interpret(explicitName != null ? explicitName : source.getPersonal(), source.getAddress(), type, parameterName);
    }

    @Nullable
    private static String nonblank(@Nullable final String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
