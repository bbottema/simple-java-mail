package org.simplejavamail.mailer.internal;

import org.simplejavamail.config.ConfigLoader.Property;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Independent inventory: an adopted alias must declare its grammar category and its additional interaction coverage. */
final class ConnectionCoverageCatalogue {
    enum Kind { TEXT, NUMBER, BOOLEAN, TRUST, FACTORY, PROTOCOL, TOKEN_PROVIDER }
    static final Map<String, Kind> MEMBERS = members();
    static final Map<Property, Kind> TYPED_MEMBERS = typedMembers();

    private ConnectionCoverageCatalogue() { }

    private static Map<Property, Kind> typedMembers() {
        final Map<Property, Kind> result = new EnumMap<>(Property.class);
        for (Property property : new Property[] {Property.SMTP_HOST, Property.SMTP_USERNAME, Property.SMTP_PASSWORD,
                Property.PROXY_HOST, Property.PROXY_USERNAME, Property.PROXY_PASSWORD, Property.SMTP_LOCAL_ADDRESS,
                Property.SMTP_CLIENT_HOSTNAME, Property.CUSTOM_SSLFACTORY_CLASS}) {
            result.put(property, Kind.TEXT);
        }
        for (Property property : new Property[] {Property.SMTP_PORT, Property.PROXY_PORT, Property.PROXY_SOCKS5BRIDGE_PORT,
                Property.SMTP_LOCAL_PORT, Property.DEFAULT_SESSION_TIMEOUT_MILLIS, Property.DEFAULT_CONNECT_TIMEOUT_MILLIS,
                Property.DEFAULT_READ_TIMEOUT_MILLIS, Property.DEFAULT_WRITE_TIMEOUT_MILLIS}) {
            result.put(property, Kind.NUMBER);
        }
        result.put(Property.DEFAULT_VERIFY_SERVER_IDENTITY, Kind.BOOLEAN);
        result.put(Property.OPPORTUNISTIC_TLS, Kind.BOOLEAN);
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Kind> members() {
        final Map<String, Kind> result = new LinkedHashMap<>();
        add(result, Kind.TEXT, "host", "user", "socks.host", "localaddress", "localhost", "ssl.socketFactory.class", "socketFactory.class", "auth.mechanisms");
        add(result, Kind.NUMBER, "port", "socks.port", "localport", "connectiontimeout", "timeout", "writetimeout");
        add(result, Kind.BOOLEAN, "auth", "ssl.checkserveridentity", "starttls.enable", "starttls.required", "ssl.enable",
                "auth.login.disable", "auth.plain.disable", "auth.digest-md5.disable", "auth.ntlm.disable", "auth.xoauth2.disable");
        add(result, Kind.TRUST, "ssl.trust");
        add(result, Kind.FACTORY, "ssl.socketFactory", "socketFactory");
        return Collections.unmodifiableMap(result);
    }

    private static void add(final Map<String, Kind> target, final Kind kind, final String... members) {
        for (String member : members) {
            if (target.put(member, kind) != null) { throw new AssertionError("Duplicate coverage member " + member); }
        }
    }
}
