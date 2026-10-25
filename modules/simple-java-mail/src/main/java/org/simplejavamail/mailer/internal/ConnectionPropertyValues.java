package org.simplejavamail.mailer.internal;

import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.config.TransportStrategy;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/** One interpretation of supported connection properties for declaration comparison, locks and inspection. */
final class ConnectionPropertyValues {
    private enum Kind { TEXT, INTEGER, BOOLEAN, TRUST, OBJECT, UNKNOWN }
    private enum WildcardTrust { INSTANCE }
    private static final Map<String, Kind> MEMBERS = members();

    private ConnectionPropertyValues() { }

    private static Map<String, Kind> members() {
        final Map<String, Kind> members = new LinkedHashMap<>();
        register(members, Kind.INTEGER, "port", "socks.port", "localport", "connectiontimeout", "timeout", "writetimeout");
        register(members, Kind.BOOLEAN, "auth", "ssl.checkserveridentity", "starttls.enable", "starttls.required", "ssl.enable",
                "auth.login.disable", "auth.plain.disable", "auth.digest-md5.disable", "auth.ntlm.disable", "auth.xoauth2.disable");
        register(members, Kind.TRUST, "ssl.trust");
        register(members, Kind.TEXT, "host", "user", "socks.host", "localaddress", "localhost", "ssl.socketFactory.class",
                "socketFactory.class", "auth.mechanisms");
        register(members, Kind.OBJECT, "ssl.socketFactory", "socketFactory");
        return Collections.unmodifiableMap(members);
    }

    private static void register(final Map<String, Kind> members, final Kind kind, final String... keys) {
        for (String key : keys) {
            if (members.put(key, kind) != null) {
                throw new IllegalStateException("Duplicate connection property: " + key);
            }
        }
    }

    static Set<String> supportedNames(final TransportStrategy strategy) {
        final String prefix = strategy.propertyNameHost().substring(0, strategy.propertyNameHost().length() - "host".length());
        final Set<String> result = new LinkedHashSet<>();
        MEMBERS.keySet().forEach(member -> result.add(prefix + member));
        result.add("mail.transport.protocol");
        result.add(TransportStrategy.OAUTH2_TOKEN_PROPERTY);
        result.add(TransportStrategy.OAUTH2_TOKEN_PROVIDER_PROPERTY);
        return Collections.unmodifiableSet(result);
    }

    static boolean isSupported(final String key) {
        return (key.startsWith("mail.smtp.") || key.startsWith("mail.smtps.") || key.equals("mail.transport.protocol")
                || key.equals(TransportStrategy.OAUTH2_TOKEN_PROPERTY) || key.equals(TransportStrategy.OAUTH2_TOKEN_PROVIDER_PROPERTY))
                && kindOf(key) != Kind.UNKNOWN;
    }

    static boolean readsString(final String key) {
        final Kind kind = kindOf(key);
        return kind == Kind.TEXT || kind == Kind.TRUST;
    }

    @Nullable
    static Object visibleValue(final Properties properties, final String key) {
        if (readsString(key)) {
            final String visible = properties.getProperty(key);
            return visible != null ? visible : properties.get(key);
        }
        final Object direct = properties.get(key);
        return direct != null ? direct : properties.getProperty(key);
    }

    static boolean equivalent(final String key, @Nullable final Object first, @Nullable final Object second) {
        if (first == second) { return true; }
        final Kind kind = kindOf(key);
        if (kind == Kind.OBJECT || !hasSupportedType(kind, first) || !hasSupportedType(kind, second)) {
            return false;
        }
        return Objects.equals(normalize(kind, first), normalize(kind, second));
    }

    private static Kind kindOf(final String key) {
        if (key.equals("mail.transport.protocol") || key.equals(TransportStrategy.OAUTH2_TOKEN_PROPERTY)
                || key.equals("simplejavamail.smtp.password") || key.equals("simplejavamail.proxy.username")
                || key.equals("simplejavamail.proxy.password")) {
            return Kind.TEXT;
        }
        if (key.equals("simplejavamail.proxy.socks5bridge.port")) { return Kind.INTEGER; }
        if (key.equals(TransportStrategy.OAUTH2_TOKEN_PROVIDER_PROPERTY)) { return Kind.OBJECT; }
        final String prefix = key.startsWith("mail.smtp.") ? "mail.smtp." : key.startsWith("mail.smtps.") ? "mail.smtps." : null;
        return prefix == null ? Kind.UNKNOWN : MEMBERS.getOrDefault(key.substring(prefix.length()), Kind.UNKNOWN);
    }

    private static boolean hasSupportedType(final Kind kind, @Nullable final Object value) {
        if (value == null || value instanceof String) { return true; }
        return kind == Kind.INTEGER && value instanceof Integer || kind == Kind.BOOLEAN && value instanceof Boolean;
    }

    @Nullable
    private static Object normalize(final Kind kind, @Nullable final Object value) {
        if (value == null) { return null; }
        if (kind == Kind.TRUST) {
            final String text = (String) value;
            // A bounded list containing "*" is different from the provider's exact wildcard token.
            return "*".equals(text) ? WildcardTrust.INSTANCE : new LinkedHashSet<>(Arrays.asList(text.trim().split("\\s+")));
        }
        if (kind == Kind.INTEGER) {
            final Integer parsed = integerValue(value);
            return parsed == null ? value : parsed;
        }
        if (kind == Kind.BOOLEAN && value instanceof String) {
            if ("true".equalsIgnoreCase((String) value)) { return true; }
            if ("false".equalsIgnoreCase((String) value)) { return false; }
        }
        return value;
    }

    @Nullable
    static Integer integerValue(@Nullable final Object value) {
        if (value instanceof Integer) { return (Integer) value; }
        if (!(value instanceof String)) { return null; }
        final String text = (String) value;
        try {
            return text.startsWith("0x") ? Integer.valueOf(text.substring(2), 16) : Integer.valueOf(text);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    /** Typed host-list choices require the separate trust-all flag to enable wildcard trust. */
    @Nullable
    static String boundedTrustValue(@Nullable final String hosts) {
        return hosts == null || hosts.isEmpty() || "*".equals(hosts) ? null : hosts;
    }
}
