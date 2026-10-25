package org.simplejavamail.mailer.internal;

import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.config.TransportStrategy;

import java.util.List;
import java.util.Objects;

/** Shared interpretation for supported connection declarations and locks; opaque objects are never coerced into scalar choices. */
final class ConnectionPropertyValues {

    private ConnectionPropertyValues() {
    }

    static boolean isSupported(final String key) {
        return kindOf(key) != Kind.UNKNOWN;
    }

    static boolean equivalent(final String key, @Nullable final Object first, @Nullable final Object second) {
        final Kind kind = kindOf(key);
        if (hasSupportedType(kind, first) != hasSupportedType(kind, second)) {
            return false;
        }
        return Objects.equals(normalize(kind, first), normalize(kind, second));
    }

    private static Kind kindOf(final String key) {
        if (key.equals("mail.transport.protocol") || key.equals(TransportStrategy.OAUTH2_TOKEN_PROPERTY)) {
            return Kind.TEXT;
        }
        if (key.equals(TransportStrategy.OAUTH2_TOKEN_PROVIDER_PROPERTY)) {
            return Kind.OBJECT;
        }
        final String member;
        if (key.startsWith("mail.smtp.")) {
            member = key.substring("mail.smtp.".length());
        } else if (key.startsWith("mail.smtps.")) {
            member = key.substring("mail.smtps.".length());
        } else {
            return Kind.UNKNOWN;
        }
        switch (member) {
            case "port":
            case "socks.port":
            case "localport":
            case "connectiontimeout":
            case "timeout":
            case "writetimeout":
                return Kind.INTEGER;
            case "auth":
            case "ssl.checkserveridentity":
            case "starttls.enable":
            case "starttls.required":
            case "ssl.enable":
            case "auth.login.disable":
            case "auth.plain.disable":
            case "auth.digest-md5.disable":
            case "auth.ntlm.disable":
            case "auth.xoauth2.disable":
                return Kind.BOOLEAN;
            case "ssl.trust":
                return Kind.TRUST;
            case "host":
            case "user":
            case "socks.host":
            case "localaddress":
            case "localhost":
            case "ssl.socketFactory.class":
            case "socketFactory.class":
            case "auth.mechanisms":
                return Kind.TEXT;
            case "ssl.socketFactory":
            case "socketFactory":
                return Kind.OBJECT;
            default:
                return Kind.UNKNOWN;
        }
    }

    private static boolean hasSupportedType(final Kind kind, @Nullable final Object value) {
        if (value == null || value instanceof String) {
            return true;
        }
        switch (kind) {
            case INTEGER:
                return value instanceof Integer;
            case BOOLEAN:
                return value instanceof Boolean;
            case TEXT:
            case TRUST:
                return false;
            default:
                return true;
        }
    }

    @Nullable
    private static Object normalize(final Kind kind, @Nullable final Object value) {
        if (value == null || !hasSupportedType(kind, value)) {
            return value;
        }
        if (kind == Kind.TRUST) {
            final String providerText = (String) value;
            // Only exact "*" enables wildcard trust. A present empty host list remains distinct from absence.
            return "*".equals(providerText) ? WildcardTrust.INSTANCE : List.of(providerText.trim().split("\\s+"));
        }
        if (kind == Kind.INTEGER && value instanceof String) {
            try {
                return Integer.valueOf((String) value);
            } catch (NumberFormatException invalid) {
                return value;
            }
        }
        if (kind == Kind.BOOLEAN && value instanceof String) {
            if ("true".equalsIgnoreCase((String) value)) {
                return true;
            }
            if ("false".equalsIgnoreCase((String) value)) {
                return false;
            }
        }
        return kind == Kind.TEXT && "".equals(value) ? null : value;
    }

    private enum Kind {
        TEXT, INTEGER, BOOLEAN, TRUST, OBJECT, UNKNOWN
    }

    private enum WildcardTrust {
        INSTANCE
    }
}
