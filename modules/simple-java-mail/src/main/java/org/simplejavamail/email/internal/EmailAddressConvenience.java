package org.simplejavamail.email.internal;

import jakarta.mail.Message.RecipientType;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Recipient;

import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/** Single-mailbox interpretation for Email conveniences; independent of the Recipient builders' parsing contracts. */
final class EmailAddressConvenience {

    private EmailAddressConvenience() { }

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
