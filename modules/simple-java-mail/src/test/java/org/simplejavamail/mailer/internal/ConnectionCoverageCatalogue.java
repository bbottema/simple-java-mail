package org.simplejavamail.mailer.internal;

import org.simplejavamail.config.ConfigLoader.Property;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Independent inventory: an adopted alias must declare its grammar category and its additional interaction coverage. */
final class ConnectionCoverageCatalogue {
    enum Kind { TEXT, NUMBER, BOOLEAN, TRUST, FACTORY, PROTOCOL, TOKEN_PROVIDER }
    enum Access { SESSION_STRING, PROPERTY_INTEGER, PROPERTY_BOOLEAN, DIRECT_RESOURCE }
    enum Grammar { TEXT, INTEGER, BOOLEAN, SOCKS_ENDPOINT, TRUST_HOSTS, FACTORY_CLASS, BORROWED_IDENTITY, PROTOCOL }
    static final Map<String, Kind> MEMBERS = members();
    static final Map<Property, Kind> TYPED_MEMBERS = typedMembers();
    static final Map<String, Access> ACCESS = access();
    static final Map<String, Grammar> GRAMMAR = grammar();

    private ConnectionCoverageCatalogue() { }

    private static Map<String, Access> access() {
        final Map<String, Access> aliases = new LinkedHashMap<>();
        access(aliases, Access.SESSION_STRING, "host", "user", "socks.host", "localaddress", "localhost",
                "ssl.socketFactory.class", "socketFactory.class", "auth.mechanisms", "ssl.trust");
        access(aliases, Access.PROPERTY_INTEGER, "port", "socks.port", "localport", "connectiontimeout", "timeout", "writetimeout");
        access(aliases, Access.PROPERTY_BOOLEAN, "auth", "ssl.checkserveridentity", "starttls.enable", "starttls.required", "ssl.enable",
                "auth.login.disable", "auth.plain.disable", "auth.digest-md5.disable", "auth.ntlm.disable", "auth.xoauth2.disable");
        access(aliases, Access.DIRECT_RESOURCE, "ssl.socketFactory", "socketFactory");
        aliases.put("mail.transport.protocol", Access.SESSION_STRING);
        aliases.put("simplejavamail.oauth2.token", Access.SESSION_STRING);
        aliases.put("simplejavamail.oauth2.token.provider", Access.DIRECT_RESOURCE);
        return Collections.unmodifiableMap(aliases);
    }

    private static void access(final Map<String, Access> target, final Access access, final String... names) {
        for (String protocol : new String[] {"smtp", "smtps"}) {
            for (String name : names) { target.put("mail." + protocol + "." + name, access); }
        }
    }

    private static Map<String, Grammar> grammar() {
        final Map<String, Grammar> aliases = new LinkedHashMap<>();
        grammar(aliases, Grammar.TEXT, "host", "user", "localaddress", "localhost", "auth.mechanisms");
        grammar(aliases, Grammar.INTEGER, "port", "socks.port", "localport", "connectiontimeout", "timeout", "writetimeout");
        grammar(aliases, Grammar.BOOLEAN, "auth", "ssl.checkserveridentity", "starttls.enable", "starttls.required", "ssl.enable",
                "auth.login.disable", "auth.plain.disable", "auth.digest-md5.disable", "auth.ntlm.disable", "auth.xoauth2.disable");
        grammar(aliases, Grammar.SOCKS_ENDPOINT, "socks.host");
        grammar(aliases, Grammar.TRUST_HOSTS, "ssl.trust");
        grammar(aliases, Grammar.FACTORY_CLASS, "ssl.socketFactory.class", "socketFactory.class");
        grammar(aliases, Grammar.BORROWED_IDENTITY, "ssl.socketFactory", "socketFactory");
        aliases.put("mail.transport.protocol", Grammar.PROTOCOL);
        aliases.put("simplejavamail.oauth2.token", Grammar.TEXT);
        aliases.put("simplejavamail.oauth2.token.provider", Grammar.BORROWED_IDENTITY);
        return Collections.unmodifiableMap(aliases);
    }

    private static void grammar(final Map<String, Grammar> target, final Grammar grammar, final String... names) {
        for (String protocol : new String[] {"smtp", "smtps"}) {
            for (String name : names) { target.put("mail." + protocol + "." + name, grammar); }
        }
    }

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
