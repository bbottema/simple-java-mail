package org.simplejavamail.internal.email;

import org.jetbrains.annotations.ApiStatus;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Shared semantic header ownership for represented-value comparison and reconstructed output. */
@ApiStatus.Internal
public final class HeaderRoles {
    public enum Role { TYPED, MIME_STRUCTURE, DELIVERY_HISTORY, EXPLICIT_AUTHORED, ADDITIONAL, PARSER_PLACEHOLDER }

    private static final Set<String> TYPED = Set.of("subject", "from", "to", "cc", "bcc", "reply-to", "date", "message-id",
            "disposition-notification-to", "return-receipt-to");
    private static final Set<String> STRUCTURE = Set.of("mime-version", "content-type", "content-transfer-encoding", "content-length", "content-md5",
            "content-disposition", "content-id", "content-description", "content-base", "content-location");
    private static final Set<String> HISTORY = Set.of("return-path", "received", "authentication-results", "received-spf", "dkim-signature",
            "domainkey-signature", "arc-authentication-results", "arc-message-signature", "arc-seal", "delivered-to", "x-original-to",
            "envelope-to", "x-envelope-to", "x-envelope-from", "resent-date", "resent-from", "resent-sender", "resent-to", "resent-cc",
            "resent-bcc", "resent-message-id", "status", "errors-to");
    private static final Set<String> EXPLICIT_AUTHORED = Set.of("sender", "comments", "keywords");

    private HeaderRoles() { }

    public static boolean isThreading(final String name) {
        return "References".equalsIgnoreCase(name) || "In-Reply-To".equalsIgnoreCase(name);
    }

    public static Role classify(final String name) {
        final String normalized = Objects.requireNonNull(name, "name").toLowerCase(Locale.ROOT);
        if (TYPED.contains(normalized)) return Role.TYPED;
        if (STRUCTURE.contains(normalized)) return Role.MIME_STRUCTURE;
        if (HISTORY.contains(normalized)) return Role.DELIVERY_HISTORY;
        if (EXPLICIT_AUTHORED.contains(normalized)) return Role.EXPLICIT_AUTHORED;
        if (normalized.equals(":")) return Role.PARSER_PLACEHOLDER;
        // Standalone name/filename/size fields are custom headers, not the parameters of a Content-* field.
        return Role.ADDITIONAL;
    }
}
