package org.simplejavamail.recipient.internal;

import jakarta.mail.Message.RecipientType;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Recipient;

import java.util.ArrayList;
import java.util.List;

import static java.util.Collections.singletonList;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/** Shared outgoing mailbox interpretation, separate from address validity and authoritative inbound parsing. */
@ApiStatus.Internal
public final class MailboxInterpretation {

    private MailboxInterpretation() { }

    /** Interprets all mailboxes before applying the supplied default or fixed name. Malformed source stays intact. */
    @NotNull
    public static List<Recipient> interpretList(@Nullable final String name, final boolean fixedName, @NotNull final String source,
                                               @Nullable final RecipientType type, @NotNull final String parameterName) {
        checkNonEmptyArgument(source, parameterName);
        try {
            final InternetAddress[] addresses = parseList(source);
            final List<Recipient> recipients = new ArrayList<>();
            appendMailboxes(recipients, addresses, name, fixedName, type);
            // A recognized empty group has no members; comments/whitespace alone still need normal validation.
            return addresses.length == 0 ? singletonList(new Recipient(name, source, type, null)) : recipients;
        } catch (final AddressException unparseable) {
            return singletonList(new Recipient(name, source, type, null));
        }
    }

    /** Retains the separately supplied personal name on ordinary typed mailboxes; group labels are not member names. */
    @NotNull
    public static List<Recipient> interpretList(@Nullable final String name, final boolean fixedName, @NotNull final InternetAddress source,
                                               @Nullable final RecipientType type, @NotNull final String parameterName) {
        checkNonEmptyArgument(source, parameterName);
        final String sourceName = source.isGroup() ? null : source.getPersonal();
        final String effectiveName = selectName(name, fixedName, sourceName);
        return interpretList(effectiveName, fixedName || sourceName != null, source.getAddress(), type, parameterName);
    }

    /** Uses Jakarta Mail grammar, retrying only historical top-level semicolon separators. */
    @NotNull
    public static InternetAddress[] parseList(@NotNull final String source) throws AddressException {
        try {
            return InternetAddress.parse(source, false);
        } catch (final AddressException notAnRfcList) {
            final String compatibleSource = replaceLegacySeparators(source);
            if (compatibleSource.equals(source)) {
                throw notAnRfcList;
            }
            return InternetAddress.parse(compatibleSource, false);
        }
    }

    /** Returns a single ordinary mailbox, or null when the source should remain one uninterpreted recipient value. */
    @Nullable
    public static InternetAddress singleMailbox(@NotNull final String source) {
        try {
            final InternetAddress[] addresses = parseList(source);
            return addresses.length == 1 && !addresses[0].isGroup() ? addresses[0] : null;
        } catch (final AddressException unparseable) {
            return null;
        }
    }

    /** Splits CLI envelope entries without normalizing their source, so strict exact-mailbox checks still see every original character. */
    @NotNull
    public static String[] splitEnvelopeEntries(@NotNull final String source) {
        checkNonEmptyArgument(source, "recipientAddresses");
        final List<String> entries = new ArrayList<>();
        int start = 0;
        for (final int delimiter : listDelimiters(source)) {
            entries.add(source.substring(start, delimiter));
            start = delimiter + 1;
        }
        // Preserve interior missing entries, while retaining the historical convenience of one trailing separator.
        if (start == 0 || !source.substring(start).isBlank()) {
            entries.add(source.substring(start));
        }
        return entries.toArray(new String[0]);
    }

    private static void appendMailboxes(final List<Recipient> target, final InternetAddress[] addresses,
                                        @Nullable final String name, final boolean fixedName, @Nullable final RecipientType type)
            throws AddressException {
        for (final InternetAddress address : addresses) {
            if (address.isGroup()) {
                appendMailboxes(target, address.getGroup(false), name, fixedName, type);
            } else {
                target.add(new Recipient(selectName(name, fixedName, address.getPersonal()), address.getAddress(), type, null));
            }
        }
    }

    @Nullable
    private static String selectName(@Nullable final String name, final boolean fixedName, @Nullable final String embeddedName) {
        final boolean suppliedNameWins = fixedName || embeddedName == null || embeddedName.isEmpty();
        return RecipientConfigurationResolver.select("recipient.name", suppliedNameWins ? name : null,
                embeddedName, suppliedNameWins ? null : name);
    }

    /** Tracks only delimiter context; Jakarta Mail remains responsible for interpreting the resulting grammar. */
    @NotNull
    private static String replaceLegacySeparators(@NotNull final String source) {
        final StringBuilder compatible = new StringBuilder(source);
        for (final int delimiter : listDelimiters(source)) {
            if (source.charAt(delimiter) == ';') {
                compatible.setCharAt(delimiter, ',');
            }
        }
        return compatible.toString();
    }

    @NotNull
    private static List<Integer> listDelimiters(@NotNull final String source) {
        final List<Integer> delimiters = new ArrayList<>();
        boolean quoted = false;
        boolean escaped = false;
        boolean group = false;
        int comments = 0;
        int angles = 0;
        int literals = 0;
        for (int i = 0; i < source.length(); i++) {
            final char character = source.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (comments > 0) {
                if (character == '(') comments++;
                if (character == ')') comments--;
            } else if (character == '"') {
                quoted = !quoted;
            } else if (!quoted) {
                if (character == '(') comments++;
                if (character == '<') angles++;
                if (character == '>') angles--;
                if (character == '[') literals++;
                if (character == ']') literals--;
                if (angles == 0 && literals == 0) {
                    if (character == ':') group = true;
                    if (character == ';') {
                        if (!group) {
                            delimiters.add(i);
                            continue;
                        }
                        group = false;
                    }
                    if (character == ',' && !group) delimiters.add(i);
                }
            }
        }
        return delimiters;
    }
}
