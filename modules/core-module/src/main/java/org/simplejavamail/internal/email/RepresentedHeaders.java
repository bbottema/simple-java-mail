package org.simplejavamail.internal.email;

import jakarta.mail.internet.InternetAddress;
import org.jetbrains.annotations.ApiStatus;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;

import java.io.UnsupportedEncodingException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** One immutable read authority for typed and additional represented fields, without promising original wire formatting. */
@ApiStatus.Internal
public final class RepresentedHeaders {
    private RepresentedHeaders() { }

    public static Map<String, Collection<String>> snapshot(final Email email, final Map<String, Collection<String>> additional,
            final Set<String> capturedReceiptGroups) {
        final Map<String, Collection<String>> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        additional.forEach((name, values) -> result.put(name, Collections.unmodifiableList(new ArrayList<>(values))));
        put(result, "Subject", email.getSubject());
        if (email.getId() != null) result.put("Message-ID", List.of(email.getId()));
        put(result, "From", email.getFromRecipient() == null ? null : address(email.getFromRecipient()));
        addresses(result, "To", email.getToRecipients());
        addresses(result, "Cc", email.getCcRecipients());
        addresses(result, "Bcc", email.getBccRecipients());
        addresses(result, "Reply-To", email.getReplyToRecipients());
        notifications(result, "Disposition-Notification-To", email.getUseDispositionNotificationTo(), email.getDispositionNotificationToRecipients(), capturedReceiptGroups);
        notifications(result, "Return-Receipt-To", email.getUseReturnReceiptTo(), email.getReturnReceiptToRecipients(), capturedReceiptGroups);
        if (email.getSentDate() != null) {
            final SimpleDateFormat format = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            put(result, "Date", format.format(email.getSentDate()));
        }
        return Collections.unmodifiableMap(result);
    }

    private static void addresses(final Map<String, Collection<String>> values, final String name, final List<Recipient> recipients) {
        if (!recipients.isEmpty()) put(values, name, recipients.stream().map(RepresentedHeaders::address).collect(Collectors.joining(", ")));
    }

    private static void notifications(final Map<String, Collection<String>> values, final String name, final Boolean requested,
            final List<Recipient> recipients, final Set<String> capturedReceiptGroups) {
        if (Boolean.FALSE.equals(requested)) values.remove(name);
        else if (Boolean.TRUE.equals(requested) && !(capturedReceiptGroups.contains(name) && values.containsKey(name))) {
            values.remove(name);
            addresses(values, name, recipients);
        }
    }

    private static String address(final Recipient recipient) {
        if (recipient.getName() == null) return recipient.getAddress();
        try {
            return new InternetAddress(recipient.getAddress(), recipient.getName()).toUnicodeString();
        } catch (final UnsupportedEncodingException unavailable) {
            // UTF-8 is mandatory in the JDK; preserve the already represented address if an alternate provider cannot encode its name.
            return recipient.getAddress();
        }
    }

    private static void put(final Map<String, Collection<String>> values, final String name, final String value) {
        if (value != null) values.put(name, List.of(value));
    }
}
