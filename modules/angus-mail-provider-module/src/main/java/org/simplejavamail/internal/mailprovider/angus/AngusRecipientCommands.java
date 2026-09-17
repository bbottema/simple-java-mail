package org.simplejavamail.internal.mailprovider.angus;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.ParseException;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption;
import org.simplejavamail.api.mailer.spi.DeliveryRecipient;
import org.simplejavamail.api.mailer.spi.MailTransportCompatibilityException;
import org.simplejavamail.api.mailer.spi.PreparedMail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptySet;
import static java.util.stream.Collectors.joining;

/**
 * Adds recipient DSN parameters at the managed command hook; Angus still owns its RCPT loop, replies and partial-send behavior.
 * Angus builds its exception command strings before this hook, so protocol debug output (not those strings) shows the augmented wire parameters.
 */
final class AngusRecipientCommands {

    private static final String RECIPIENT_COMMAND_PREFIX = "RCPT TO:<";
    // RFC 3461 section 4.2 bounds the entire ORCPT parameter, including "ORCPT=".
    private static final int MAX_ORCPT_LENGTH = 500;
    private final List<Set<NotifyOption>> recipientNotifyOptions;
    private final boolean supportsUtf8RecipientCommands;

    private AngusRecipientCommands(final List<Set<NotifyOption>> recipientNotifyOptions, final boolean supportsUtf8RecipientCommands) {
        this.recipientNotifyOptions = List.copyOf(recipientNotifyOptions);
        this.supportsUtf8RecipientCommands = supportsUtf8RecipientCommands;
    }

    @Nullable
    static AngusRecipientCommands prepare(final SMTPTransport transport, final PreparedMail mail, final boolean supportsDsn)
            throws MailTransportCompatibilityException {
        validateRecipientNotifySupport(transport, mail, supportsDsn);
        if (!(transport instanceof ManagedAngusTransport) || !supportsDsn) {
            return null;
        }
        final Address[] recipients = mail.getRecipients();
        final List<DeliveryRecipient> recipientPolicies = mail.getDeliveryEnvelope().getRecipientOptions();
        validateRecipientPolicyOrder(recipients, recipientPolicies);
        return new AngusRecipientCommands(expandPoliciesInAngusRecipientOrder(recipients, recipientPolicies),
                ((ManagedAngusTransport) transport).supportsUtf8RecipientCommands());
    }

    /** Explicit recipient preferences must be supported; automatic ORCPT is optional and may be omitted instead. */
    private static void validateRecipientNotifySupport(final SMTPTransport transport, final PreparedMail mail, final boolean supportsDsn)
            throws MailTransportCompatibilityException {
        final boolean recipientPreferences = mail.getDeliveryEnvelope().hasRecipientNotifyOptions();
        if (recipientPreferences && !(transport instanceof ManagedAngusTransport)) {
            throw new MailTransportCompatibilityException("This transport cannot apply recipient-specific delivery-notification settings. "
                    + "They require Simple Java Mail's managed Angus transport; a caller-owned Session or custom socket factory can bypass it. "
                    + "Create the Mailer with withSMTPServer(...), without supplying a Session or custom socket factory, "
                    + "or remove the recipient-specific settings and use best-effort Email-level settings instead. No message was submitted.",
                    mail.getRecipients());
        }
        if (recipientPreferences && !supportsDsn) {
            throw new MailTransportCompatibilityException("This SMTP connection does not advertise usable DSN support, so your recipient-specific "
                    + "notification settings cannot be sent. Use a server with DSN support, or remove the recipient-specific settings "
                    + "if sending without those notification requests is acceptable. No message was submitted.",
                    mail.getRecipients());
        }
    }

    /**
     * Normal preparation already pairs these lists. Reject inconsistent SPI input rather than applying a preference to the wrong recipient.
     * Compare occurrences in order: duplicate addresses may intentionally have different preferences. Never reorder or deduplicate them.
     */
    private static void validateRecipientPolicyOrder(final Address[] recipients, final List<DeliveryRecipient> recipientPolicies)
            throws MailTransportCompatibilityException {
        if (recipientPolicies.isEmpty()) {
            return;
        }
        if (recipientPolicies.size() != recipients.length) {
            throw mismatchedRecipients(recipients);
        }
        for (int index = 0; index < recipients.length; index++) {
            final Address recipient = recipients[index];
            final String address = recipient instanceof InternetAddress ? ((InternetAddress) recipient).getAddress() : recipient.toString();
            if (!recipientPolicies.get(index).getAddress().equals(address)) {
                throw mismatchedRecipients(recipients);
            }
        }
    }

    private static MailTransportCompatibilityException mismatchedRecipients(final Address[] recipients) {
        return new MailTransportCompatibilityException("The prepared recipient settings do not match the delivery recipients. "
                + "If your integration constructs PreparedMail, give DeliveryEnvelope one DeliveryRecipient per entry in PreparedMail.getRecipients(), "
                + "with matching addresses in the same order, including duplicates. Otherwise, please report this as a Simple Java Mail bug. "
                + "No message was submitted.", recipients);
    }

    /**
     * Angus sends one RCPT command per member of an RFC address group, so repeat the group's preference for each member.
     * These are Jakarta Mail address groups, not Simple Java Mail's Recipients policy groups.
     */
    private static List<Set<NotifyOption>> expandPoliciesInAngusRecipientOrder(final Address[] recipients,
            final List<DeliveryRecipient> recipientPolicies) {
        final List<Set<NotifyOption>> expandedRecipientNotifyOptions = new ArrayList<>();
        for (int index = 0; index < recipients.length; index++) {
            final Set<NotifyOption> options = recipientPolicies.isEmpty() ? emptySet() : recipientPolicies.get(index).getNotifyOptions();
            final int memberCount = expandRecipients(new Address[]{recipients[index]}).length;
            expandedRecipientNotifyOptions.addAll(Collections.nCopies(memberCount, options));
        }
        return expandedRecipientNotifyOptions;
    }

    /** Mirrors Angus group expansion for both reporting and recipient policies; never changes the supplied addresses or MIME headers. */
    static Address[] expandRecipients(final Address[] addresses) {
        final List<Address> envelope = new ArrayList<>();
        for (final Address address : addresses) {
            if (address instanceof InternetAddress && ((InternetAddress) address).isGroup()) {
                try {
                    final InternetAddress[] members = ((InternetAddress) address).getGroup(true);
                    if (members != null) {
                        Collections.addAll(envelope, members);
                        continue;
                    }
                } catch (final ParseException ignored) {
                    // Angus retains an unparseable group and lets the original send report the failure.
                }
            }
            envelope.add(address);
        }
        return envelope.toArray(new Address[0]);
    }

    String applyToRecipientCommand(final String command, final int recipientIndex) throws MessagingException {
        if (recipientIndex >= recipientNotifyOptions.size()) {
            throw new MessagingException("Angus produced more SMTP recipient commands than expected, so Simple Java Mail stopped this send. "
                    + "Please report this provider-integration problem with your Simple Java Mail and Angus versions.");
        }
        final int addressEnd = requireRecipientAddressEnd(command);
        final String parameters = applyRecipientNotifyOverride(command.substring(addressEnd + 1), recipientNotifyOptions.get(recipientIndex));
        final String originalRecipient = originalRecipientParameter(command.substring(RECIPIENT_COMMAND_PREFIX.length(), addressEnd),
                supportsUtf8RecipientCommands);
        return command.substring(0, addressEnd + 1) + parameters + (originalRecipient == null ? "" : " " + originalRecipient);
    }

    private static int requireRecipientAddressEnd(final String command) throws MessagingException {
        final int addressEnd = command.lastIndexOf('>');
        if (!command.startsWith(RECIPIENT_COMMAND_PREFIX) || addressEnd < RECIPIENT_COMMAND_PREFIX.length()) {
            throw new MessagingException("Angus produced an SMTP recipient command in a format Simple Java Mail cannot safely update. "
                    + "This send was stopped. Please report this provider-integration problem with your Simple Java Mail and Angus versions.");
        }
        return addressEnd;
    }

    /**
     * Angus resolves one Email/Session NOTIFY value before its recipient loop and appends it to every RCPT command.
     * Replace that shared value only for an explicit recipient preference, before the command reaches the connection.
     * An absent preference leaves Angus's fallback unchanged; Angus still owns the loop and response handling.
     */
    private static String applyRecipientNotifyOverride(final String parameters, final Set<NotifyOption> options) {
        if (options.isEmpty()) {
            return parameters;
        }
        return parameters.replaceFirst("(?i) NOTIFY=[^ ]+", "")
                + " NOTIFY=" + options.stream().sorted().map(Enum::name).collect(joining(","));
    }

    /**
     * RFC 3461 xtext for ASCII mailboxes, RFC 6533 unitext when Angus can send internationalized addresses.
     * Omit automatic metadata if its address cannot be represented safely or its parameter exceeds 500 octets; never truncate an address.
     */
    @Nullable
    static String originalRecipientParameter(final String address, final boolean supportsUtf8RecipientCommands) {
        if (containsUnsafeAddressCharacters(address)) {
            return null;
        }
        final boolean internationalized = address.chars().anyMatch(character -> character > 127);
        if (internationalized && !supportsUtf8RecipientCommands) {
            return null;
        }
        final String parameter = internationalized
                ? "ORCPT=utf-8;" + encodeUnitext(address)
                : "ORCPT=rfc822;" + ManagedAngusTransport.encodeXtext(address);
        return parameter.getBytes(UTF_8).length <= MAX_ORCPT_LENGTH ? parameter : null;
    }

    private static boolean containsUnsafeAddressCharacters(final String address) {
        // codePoints combines valid surrogate pairs; a remaining surrogate means malformed Unicode input.
        return address.codePoints().anyMatch(character -> character < ' ' || character == 127
                || character >= Character.MIN_SURROGATE && character <= Character.MAX_SURROGATE);
    }

    /**
     * RFC 6533 section 3.1 uses Unicode escapes for ORCPT=utf-8, not the byte escapes produced by Angus's xtext encoder.
     * With unsafe characters already rejected, only these four ASCII characters need escaping; preserve native UTF-8.
     */
    private static String encodeUnitext(final String address) {
        // Escape original backslashes first so the escapes introduced afterward are not escaped again.
        return address.replace("\\", "\\x{5C}")
                .replace(" ", "\\x{20}")
                .replace("+", "\\x{2B}")
                .replace("=", "\\x{3D}");
    }
}
