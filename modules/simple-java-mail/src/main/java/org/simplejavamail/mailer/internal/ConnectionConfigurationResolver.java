package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigDiagnosticGroup;
import org.simplejavamail.config.ConfigKeys;
import org.simplejavamail.config.ConfigLoader.Property;
import org.simplejavamail.config.ConfigPropertyDiagnostic;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.internal.config.ConfigurationLocks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import static org.simplejavamail.internal.config.ConfigurationSnapshot.locksOf;
import static org.simplejavamail.internal.util.StringUtil.escapeControlCharacters;

/** Resolves declarations before Session ownership, retaining origin independently of defaults and final local edits. */
final class ConnectionConfigurationResolver {

    private static final String EXTRA_PREFIX = "simplejavamail.extraproperties.";
    private static final String PASSWORD = "simplejavamail.smtp.password";
    private static final String PROXY_USERNAME = "simplejavamail.proxy.username";
    private static final String PROXY_PASSWORD = "simplejavamail.proxy.password";
    private static final String PROXY_BRIDGE_PORT = "simplejavamail.proxy.socks5bridge.port";

    private final SimpleJavaMailConfig config;
    @Nullable private final TransportStrategy strategy;
    private final boolean authenticatedProxy;
    private final Map<String, List<Declaration>> declarations = new LinkedHashMap<>();
    private final Map<String, String> sourceNames = new LinkedHashMap<>();

    private ConnectionConfigurationResolver(final SimpleJavaMailConfig config, @Nullable final TransportStrategy strategy, final boolean authenticatedProxy) {
        this.config = config;
        this.strategy = strategy;
        this.authenticatedProxy = authenticatedProxy;
        for (ConfigDiagnosticGroup group : config.getDiagnostics().getGroups()) {
            for (ConfigPropertyDiagnostic diagnostic : config.getDiagnostics().getProperties(group)) {
                sourceNames.put(diagnostic.getPropertyName(), diagnostic.getSourceName());
            }
        }
    }

    static Resolution resolve(final SimpleJavaMailConfig config, @Nullable final Session suppliedSession,
            @Nullable final TransportStrategy strategy, final ConnectionConflictPolicy policy, final Properties additionalProperties,
            final Map<String, Object> rawChoices, final Map<Property, Object> typedChoices, final Map<String, Object> defaults,
            final Map<String, Object> physicalChoices, final ProxyConfig proxyFallback, final boolean proxyCustomized, final boolean smtpActive) {
        final ConnectionConfigurationResolver resolver = new ConnectionConfigurationResolver(config, strategy, proxyFallback.requiresAuthentication());
        final Map<String, Object> baseline = visibleProperties(suppliedSession);
        additionalProperties.forEach((key, value) -> baseline.put(key.toString(), value));
        baseline.entrySet().removeIf(entry -> resolver.isMatchingPhysicalProxyLock(entry.getKey(), entry.getValue()));
        final Map<Property, Object> applicableTypedChoices = smtpActive ? typedChoices : inactiveRouteChoices(typedChoices);
        final Map<String, Object> typed = resolver.project(applicableTypedChoices, baseline);
        if (smtpActive) {
            typed.putAll(physicalChoices);
        }
        if (smtpActive && strategy != null) {
            resolver.collectFactoryDeclarations();
            resolver.collectRawDeclarations(suppliedSession, rawChoices);
            typed.forEach((key, value) -> resolver.declare(key, value, physicalChoices.containsKey(key)
                    ? "typed Java TLS setting (withCustomSSLFactoryInstance/withTlsConfig)" : typedLabel(key, typedChoices, strategy)));
            if (policy == ConnectionConflictPolicy.REJECT_CONFLICTS) {
                resolver.rejectConflictingDeclarations();
            }
        }

        final Map<String, Object> effective = new LinkedHashMap<>();
        if (suppliedSession == null) {
            defaults.forEach((key, value) -> effective.put(key, propertyValue(value)));
        }
        effective.putAll(baseline);
        // A raw removal suppresses a captured overlay, but clearProperties never erases untouched caller Session entries.
        rawChoices.forEach((key, value) -> {
            if (value != null && !resolver.isMatchingPhysicalProxyLock(key, value)) {
                effective.put(key, value);
            }
        });
        typed.forEach((key, value) -> {
            if (value != null && value != TrustAllDisabled.INSTANCE && baseline.containsKey(key) && sameValue(key, value, baseline.get(key))) {
                effective.put(key, baseline.get(key));
            } else {
                putOrRemove(effective, key, propertyValue(value));
            }
        });
        if (strategy != null && applicableTypedChoices.containsKey(Property.SMTP_PORT) && applicableTypedChoices.get(Property.SMTP_PORT) == null) {
            effective.put(strategy.propertyNamePort(), String.valueOf(strategy.getDefaultServerPort()));
        }
        if (strategy == TransportStrategy.SMTP && Boolean.FALSE.equals(applicableTypedChoices.get(Property.OPPORTUNISTIC_TLS))
                && !baseline.containsKey("mail.smtp.starttls.enable")) {
            effective.remove("mail.smtp.starttls.enable");
        }
        final ProxyConfig proxy = smtpActive && strategy != null
                ? resolver.resolveProxy(effective, typed, proxyFallback, suppliedSession == null || proxyCustomized)
                : proxyFallback;
        final Map<String, Object> overrides = new LinkedHashMap<>();
        effective.forEach((key, value) -> {
            if (resolver.isSupported(key)) {
                overrides.put(key, value);
            }
        });
        typed.forEach((key, value) -> {
            if ((value == null || value == TrustAllDisabled.INSTANCE) && resolver.isSupported(key) && !effective.containsKey(key)) {
                overrides.put(key, null);
            }
        });
        rawChoices.forEach((key, value) -> {
            if (value == null && resolver.isSupported(key) && !effective.containsKey(key)) {
                overrides.put(key, null);
            }
        });
        final Map<String, Object> explicitOverrides = new LinkedHashMap<>(typed);
        explicitOverrides.values().removeIf(value -> value == TrustAllDisabled.INSTANCE);
        if (strategy != null && proxy.requiresProxy() && proxy.requiresAuthentication()) {
            // The selected remote route is checked separately from literal locks on the derived local bridge endpoint.
            explicitOverrides.remove(strategy.propertyNameSocksHost());
            explicitOverrides.remove(strategy.propertyNameSocksPort());
        }
        return new Resolution(overrides, proxy, explicitOverrides);
    }

    private static Map<Property, Object> inactiveRouteChoices(final Map<Property, Object> choices) {
        final Map<Property, Object> applicable = new LinkedHashMap<>(choices);
        for (Property property : List.of(Property.SMTP_HOST, Property.SMTP_PORT, Property.SMTP_USERNAME, Property.SMTP_PASSWORD,
                Property.TRANSPORT_STRATEGY, Property.PROXY_HOST, Property.PROXY_PORT, Property.PROXY_USERNAME, Property.PROXY_PASSWORD,
                Property.PROXY_SOCKS5BRIDGE_PORT, Property.CUSTOM_SSLFACTORY_CLASS, Property.OPPORTUNISTIC_TLS)) {
            applicable.remove(property);
        }
        return applicable;
    }

    private void collectFactoryDeclarations() {
        final Map<Property, Object> factory = new LinkedHashMap<>(config.asMap());
        // Common and individual timeout properties form one configured channel, with the individual member taking precedence.
        if (factory.containsKey(Property.DEFAULT_SESSION_TIMEOUT_MILLIS)) {
            for (Property timeout : List.of(Property.DEFAULT_CONNECT_TIMEOUT_MILLIS, Property.DEFAULT_READ_TIMEOUT_MILLIS, Property.DEFAULT_WRITE_TIMEOUT_MILLIS)) {
                factory.putIfAbsent(timeout, factory.get(Property.DEFAULT_SESSION_TIMEOUT_MILLIS));
            }
            factory.remove(Property.DEFAULT_SESSION_TIMEOUT_MILLIS);
        }
        final Map<String, Object> projected = project(factory, Collections.emptyMap());
        projected.forEach((key, value) -> {
            final Property property = propertyForKey(key, factory);
            final String name = property == null ? key : locksOf(config).contains(property) ? ConfigurationLocks.lockedName(property.key()) : property.key();
            declare(key, value, name + sourceSuffix(name));
        });
    }

    private void collectRawDeclarations(@Nullable final Session suppliedSession, final Map<String, Object> rawChoices) {
        final Map<String, String> captured = config.get(ConfigKeys.EXTRA_PROPERTIES);
        if (captured != null) {
            captured.forEach((key, value) -> {
                if (!isMatchingPhysicalProxyLock(key, value)) {
                    declare(key, value, EXTRA_PREFIX + key + sourceSuffix(EXTRA_PREFIX + key));
                }
            });
        }
        visibleProperties(suppliedSession).forEach((key, value) -> {
            if (!isMatchingPhysicalProxyLock(key, value)) {
                declare(key, value, key + " from supplied Session");
            }
        });
        rawChoices.forEach((key, value) -> {
            if (!isMatchingPhysicalProxyLock(key, value) && (value != null || captured != null && captured.containsKey(key))) {
                declare(key, value, "withProperty/withProperties(" + key + ")");
            }
        });
    }

    private boolean isMatchingPhysicalProxyLock(final String key, @Nullable final Object value) {
        return authenticatedProxy && strategy != null && (key.equals(strategy.propertyNameSocksHost()) || key.equals(strategy.propertyNameSocksPort()))
                && locksOf(config).getValues().containsKey(EXTRA_PREFIX + key)
                && sameValue(key, value, locksOf(config).getValues().get(EXTRA_PREFIX + key));
    }

    private String sourceSuffix(final String property) {
        final String source = sourceNames.get(property);
        return source == null ? " from captured factory configuration" : " from " + source;
    }

    private void declare(final String key, @Nullable final Object value, final String origin) {
        if (isSupported(key) || key.equals(PASSWORD) || key.equals(PROXY_USERNAME) || key.equals(PROXY_PASSWORD) || key.equals(PROXY_BRIDGE_PORT)) {
            declarations.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new Declaration(value, origin));
        }
    }

    private void rejectConflictingDeclarations() {
        verifyCompatibleLocks();
        declarations.forEach((key, choices) -> {
            for (int first = 0; first < choices.size(); first++) {
                for (int second = first + 1; second < choices.size(); second++) {
                    if (!sameValue(key, choices.get(first).value, choices.get(second).value)) {
                        final String lockedProperty = lockedPropertyFor(key);
                        if (lockedProperty != null) {
                            throw locksOf(config).conflict(lockedProperty,
                                    "The supplied or selected SMTP configuration has a different value for Jakarta Mail property "
                                            + escapeControlCharacters(key) + ", so it does not satisfy this lock. "
                                            + "Correct the supplied Session, additional properties, or builder setting to agree with the lock. "
                                            + "The connection conflict policy cannot override locks.");
                        }
                        throw new IllegalArgumentException("Conflicting connection configuration for " + escapeControlCharacters(key)
                                + ": " + escapeControlCharacters(choices.get(first).origin) + " disagrees with "
                                + escapeControlCharacters(choices.get(second).origin)
                                + ". Align or remove the conflicting declaration, or deliberately select "
                                + "ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES. Configuration locks and mandatory transport rules still apply.");
                    }
                }
            }
        });
    }

    private void verifyCompatibleLocks() {
        final Map<Property, Object> typedLocks = new LinkedHashMap<>();
        config.asMap().forEach((property, value) -> {
            if (locksOf(config).contains(property)) {
                typedLocks.put(property, value);
            }
        });
        final Map<String, Object> projected = project(typedLocks, Collections.emptyMap());
        locksOf(config).getValues().forEach((key, value) -> {
            if (key.startsWith(EXTRA_PREFIX)) {
                final String sessionKey = key.substring(EXTRA_PREFIX.length());
                if (projected.containsKey(sessionKey) && !isMatchingPhysicalProxyLock(sessionKey, value)
                        && !sameValue(sessionKey, value, projected.get(sessionKey))) {
                    final Property typedProperty = propertyForKey(sessionKey, typedLocks);
                    throw locksOf(config).conflict(key, "This locked Jakarta Mail property and "
                            + ConfigurationLocks.lockedName(typedProperty.key()) + " configure the same SMTP setting, but their values differ. "
                            + "Make the two locked values agree, or remove one of these locks from the configuration source.");
                }
            }
        });
    }

    private Map<String, Object> project(final Map<Property, Object> choices, final Map<String, Object> baseline) {
        final Map<String, Object> projected = new LinkedHashMap<>();
        if (strategy == null) {
            return projected;
        }
        choices.forEach((property, value) -> {
            for (String key : sessionKeys(property, strategy)) {
                projected.put(key, (property == Property.SMTP_LOCAL_ADDRESS || property == Property.SMTP_CLIENT_HOSTNAME)
                        && "".equals(value) ? null : value);
            }
        });
        if (choices.get(Property.SMTP_PASSWORD) != null && !OAuth2AccessTokenResolver.isOAuth2Transport(strategy)) {
            projected.put(strategy.propertyNameAuthenticate(), true);
        }
        if (choices.containsKey(Property.TRANSPORT_STRATEGY)) {
            final TransportStrategy requested = (TransportStrategy) choices.get(Property.TRANSPORT_STRATEGY);
            if (requested != null) {
                projected.putAll(transportRequirements(requested));
            }
        }
        if (choices.containsKey(Property.DEFAULT_TRUST_ALL_HOSTS) || choices.containsKey(Property.DEFAULT_TRUSTED_HOSTS)) {
            final String key = strategy.propertyNameSSLTrust();
            final Object hosts = choices.containsKey(Property.DEFAULT_TRUSTED_HOSTS)
                    ? normalizedHosts(choices.get(Property.DEFAULT_TRUSTED_HOSTS)) : baseline.get(key);
            if (Boolean.TRUE.equals(choices.get(Property.DEFAULT_TRUST_ALL_HOSTS))) {
                projected.put(key, "*");
            } else if (choices.containsKey(Property.DEFAULT_TRUSTED_HOSTS)) {
                projected.put(key, hosts);
            } else {
                // False means retain a bounded host list but remove wildcard trust; it is not a declaration that no list may exist.
                projected.put(key, hosts == null || "*".equals(hosts) ? TrustAllDisabled.INSTANCE : hosts);
            }
        }
        return projected;
    }

    @Nullable
    private String lockedPropertyFor(final String key) {
        if (locksOf(config).getValues().containsKey(EXTRA_PREFIX + key)) {
            return EXTRA_PREFIX + key;
        }
        for (Property property : Property.values()) {
            if (locksOf(config).contains(property) && (sessionKeys(property, strategy).contains(key)
                    || key.equals(strategy.propertyNameSSLTrust()) && (property == Property.DEFAULT_TRUST_ALL_HOSTS || property == Property.DEFAULT_TRUSTED_HOSTS)
                    || property == Property.TRANSPORT_STRATEGY && transportRequirements(config.get(ConfigKeys.TRANSPORT_STRATEGY)).containsKey(key))) {
                return property.key();
            }
        }
        return null;
    }

    static List<String> sessionKeys(final Property property, final TransportStrategy strategy) {
        switch (property) {
            case SMTP_HOST: return List.of(strategy.propertyNameHost());
            case SMTP_PORT: return List.of(strategy.propertyNamePort());
            case SMTP_USERNAME: return List.of(strategy.propertyNameUsername());
            case SMTP_PASSWORD: return List.of(OAuth2AccessTokenResolver.isOAuth2Transport(strategy) ? TransportStrategy.OAUTH2_TOKEN_PROPERTY : PASSWORD);
            case PROXY_HOST: return List.of(strategy.propertyNameSocksHost());
            case PROXY_PORT: return List.of(strategy.propertyNameSocksPort());
            case PROXY_USERNAME: return List.of(PROXY_USERNAME);
            case PROXY_PASSWORD: return List.of(PROXY_PASSWORD);
            case PROXY_SOCKS5BRIDGE_PORT: return List.of(PROXY_BRIDGE_PORT);
            case SMTP_LOCAL_ADDRESS: return List.of(strategy.propertyNameLocalAddress());
            case SMTP_LOCAL_PORT: return List.of(strategy.propertyNameLocalPort());
            case SMTP_CLIENT_HOSTNAME: return List.of(strategy.propertyNameLocalHost());
            case DEFAULT_SESSION_TIMEOUT_MILLIS:
                return List.of(strategy.propertyNameConnectionTimeout(), strategy.propertyNameTimeout(), strategy.propertyNameWriteTimeout());
            case DEFAULT_CONNECT_TIMEOUT_MILLIS: return List.of(strategy.propertyNameConnectionTimeout());
            case DEFAULT_READ_TIMEOUT_MILLIS: return List.of(strategy.propertyNameTimeout());
            case DEFAULT_WRITE_TIMEOUT_MILLIS: return List.of(strategy.propertyNameWriteTimeout());
            case DEFAULT_VERIFY_SERVER_IDENTITY: return List.of(strategy.propertyNameCheckServerIdentity());
            case CUSTOM_SSLFACTORY_CLASS: return List.of(strategy.propertyNameSSLSocketFactoryClass());
            case OPPORTUNISTIC_TLS: return strategy == TransportStrategy.SMTP ? List.of("mail.smtp.starttls.enable") : List.of();
            default: return List.of();
        }
    }

    private static Map<String, Object> transportRequirements(final TransportStrategy strategy) {
        final Map<String, Object> requirements = new LinkedHashMap<>();
        strategy.generateProperties().forEach((key, value) -> requirements.put(key.toString(), value));
        requirements.remove(Property.TRANSPORT_STRATEGY.key());
        requirements.remove("mail.smtps.quitwait");
        if (strategy == TransportStrategy.SMTP) {
            requirements.remove("mail.smtp.starttls.enable");
            requirements.remove("mail.smtp.starttls.required");
        }
        return requirements;
    }

    @Nullable
    private Property propertyForKey(final String key, final Map<Property, Object> choices) {
        if (key.equals(strategy.propertyNameAuthenticate()) && choices.get(Property.SMTP_PASSWORD) != null) {
            return Property.SMTP_PASSWORD;
        }
        for (Property property : choices.keySet()) {
            if (sessionKeys(property, strategy).contains(key)) {
                if (isTimeout(property) && !config.asMap().containsKey(property)) {
                    return Property.DEFAULT_SESSION_TIMEOUT_MILLIS;
                }
                return property;
            }
        }
        if (key.equals(strategy.propertyNameSSLTrust())) {
            return choices.containsKey(Property.DEFAULT_TRUST_ALL_HOSTS) ? Property.DEFAULT_TRUST_ALL_HOSTS : Property.DEFAULT_TRUSTED_HOSTS;
        }
        return choices.containsKey(Property.TRANSPORT_STRATEGY) ? Property.TRANSPORT_STRATEGY : null;
    }

    private static String typedLabel(final String key, final Map<Property, Object> choices, final TransportStrategy strategy) {
        if (key.equals(strategy.propertyNameAuthenticate()) && choices.get(Property.SMTP_PASSWORD) != null) {
            return "typed Java authentication setting (withSMTPServerPassword/withSMTPServer)";
        }
        for (Property property : choices.keySet()) {
            if (sessionKeys(property, strategy).contains(key)) {
                return "typed Java setting " + property.key() + " (" + setterName(property) + ")";
            }
        }
        return key.equals(strategy.propertyNameSSLTrust()) ? "typed Java TLS setting (withTlsConfig/trustingSSLHosts/trustingAllHosts)"
                : "typed Java transport setting (withTransportStrategy)";
    }

    private static String setterName(final Property property) {
        switch (property) {
            case SMTP_HOST: return "withSMTPServerHost/withSMTPServer";
            case SMTP_PORT: return "withSMTPServerPort/withSMTPServer";
            case SMTP_USERNAME: return "withSMTPServerUsername/withSMTPServer";
            case SMTP_PASSWORD: return "withSMTPServerPassword/withSMTPServer";
            case PROXY_HOST: case PROXY_PORT: case PROXY_USERNAME: case PROXY_PASSWORD: case PROXY_SOCKS5BRIDGE_PORT:
                return "withProxyConfig";
            case DEFAULT_CONNECT_TIMEOUT_MILLIS: return "withConnectionConfig/withConnectTimeout/withSessionTimeout";
            case DEFAULT_READ_TIMEOUT_MILLIS: return "withConnectionConfig/withReadTimeout/withSessionTimeout";
            case DEFAULT_WRITE_TIMEOUT_MILLIS: return "withConnectionConfig/withWriteTimeout/withSessionTimeout";
            case SMTP_LOCAL_ADDRESS: case SMTP_LOCAL_PORT: return "withConnectionConfig/withLocalBindAddress";
            case SMTP_CLIENT_HOSTNAME: return "withConnectionConfig/withSmtpClientHostname";
            default: return "withTlsConfig";
        }
    }

    private ProxyConfig resolveProxy(final Map<String, Object> effective, final Map<String, Object> typed,
            final ProxyConfig fallback, final boolean applyCapturedProxy) {
        final String hostKey = strategy.propertyNameSocksHost();
        final String portKey = strategy.propertyNameSocksPort();
        if ("".equals(typed.get(hostKey))) {
            throw new IllegalArgumentException("Proxy host cannot be empty. Use clearHost() to remove the proxy endpoint.");
        }
        final boolean hostLocked = locksOf(config).contains(Property.PROXY_HOST);
        final boolean portLocked = locksOf(config).contains(Property.PROXY_PORT);
        final String host = text(typed.containsKey(hostKey) ? typed.get(hostKey) : effective.containsKey(hostKey) ? effective.get(hostKey)
                : applyCapturedProxy || hostLocked ? fallback.getRemoteProxyHost() : null);
        final Object portChoice = typed.containsKey(portKey) ? typed.get(portKey) : effective.containsKey(portKey) ? effective.get(portKey)
                : applyCapturedProxy || portLocked || host != null ? fallback.getRemoteProxyPort() : null;
        final Integer port = integer(portChoice, portKey);
        final ProxyConfig resolved = new ProxyConfigImpl(host, port, fallback.getUsername(), fallback.getPassword(), fallback.getProxyBridgePort());
        locksOf(config).verify(Property.PROXY_HOST, host);
        locksOf(config).verify(Property.PROXY_PORT, port);
        if (host != null) {
            if (port == null) {
                throw new IllegalArgumentException("proxyHost provided, but not a proxyPort");
            }
            if ("".equals(resolved.getUsername()) || "".equals(resolved.getPassword())) {
                throw new IllegalArgumentException("Proxy credentials cannot be empty. Supply both username and password, or clear both choices.");
            }
            if ((resolved.getUsername() == null) != (resolved.getPassword() == null)) {
                throw new IllegalArgumentException(resolved.getUsername() == null ? "Proxy password provided but not a username" : "Proxy username provided but not a password");
            }
            if (resolved.requiresAuthentication() && resolved.getProxyBridgePort() == null) {
                throw new IllegalArgumentException("Cannot authenticate with proxy if no proxy bridge port is configured");
            }
            effective.put(hostKey, host);
            if (!effective.containsKey(portKey) || !sameValue(portKey, effective.get(portKey), port)) {
                effective.put(portKey, String.valueOf(port));
            }
        } else if (typed.containsKey(hostKey)) {
            effective.remove(hostKey);
        }
        return resolved;
    }

    private boolean isSupported(final String key) {
        if (key.equals("mail.transport.protocol") || key.equals(TransportStrategy.OAUTH2_TOKEN_PROPERTY)
                || key.equals(TransportStrategy.OAUTH2_TOKEN_PROVIDER_PROPERTY)) {
            return true;
        }
        if (strategy == null) {
            return false;
        }
        final String prefix = strategy.propertyNameHost().substring(0, strategy.propertyNameHost().length() - "host".length());
        if (!key.startsWith(prefix)) {
            return false;
        }
        final String member = key.substring(prefix.length());
        return List.of("host", "port", "user", "auth", "socks.host", "socks.port", "localaddress", "localport", "localhost",
                "connectiontimeout", "timeout", "writetimeout", "ssl.trust", "ssl.checkserveridentity", "ssl.socketFactory",
                "ssl.socketFactory.class", "socketFactory", "socketFactory.class", "starttls.enable", "starttls.required", "ssl.enable",
                "auth.mechanisms", "auth.login.disable", "auth.plain.disable", "auth.digest-md5.disable", "auth.ntlm.disable", "auth.xoauth2.disable").contains(member);
    }

    static Map<String, Object> visibleProperties(@Nullable final Session session) {
        final Map<String, Object> values = new LinkedHashMap<>();
        if (session != null) {
            final Properties properties = session.getProperties();
            properties.stringPropertyNames().forEach(key -> values.put(key, properties.getProperty(key)));
            properties.forEach((key, value) -> values.put(key.toString(), value));
        }
        return values;
    }

    private static boolean sameValue(final String key, @Nullable final Object first, @Nullable final Object second) {
        if (key.endsWith(".ssl.trust") && (first == TrustAllDisabled.INSTANCE || second == TrustAllDisabled.INSTANCE)) {
            return !"*".equals(first) && !"*".equals(second);
        }
        return Objects.equals(normalize(key, first), normalize(key, second));
    }

    @Nullable
    private static Object normalize(final String key, @Nullable final Object value) {
        if (value == null || value instanceof String && ((String) value).isEmpty()) {
            return null;
        }
        if (key.endsWith("port") || key.endsWith("timeout") || key.endsWith(".socks5bridge.port")) {
            try {
                return Integer.valueOf(value.toString());
            } catch (NumberFormatException invalid) {
                return value;
            }
        }
        if (key.endsWith(".enable") || key.endsWith(".required") || key.endsWith(".disable")
                || key.endsWith(".auth") || key.endsWith(".checkserveridentity")) {
            if (Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(value.toString())) {
                return true;
            }
            if (Boolean.FALSE.equals(value) || "false".equalsIgnoreCase(value.toString())) {
                return false;
            }
        }
        if (key.endsWith(".ssl.trust")) {
            return normalizedHosts(value);
        }
        return value instanceof String || value instanceof Number || value instanceof Boolean ? value.toString() : value;
    }

    @Nullable
    private static String normalizedHosts(@Nullable final Object value) {
        if (value == null || value.toString().trim().isEmpty()) {
            return null;
        }
        return String.join(" ", value.toString().trim().split("[;\\s]+"));
    }

    @Nullable
    static Integer integer(@Nullable final Object value, final String key) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid integer connection setting " + key + ". Configure a numeric port.");
        }
    }

    @Nullable private static String text(@Nullable final Object value) { return value == null || value.toString().isEmpty() ? null : value.toString(); }

    private static boolean isTimeout(final Property property) {
        return property == Property.DEFAULT_CONNECT_TIMEOUT_MILLIS || property == Property.DEFAULT_READ_TIMEOUT_MILLIS || property == Property.DEFAULT_WRITE_TIMEOUT_MILLIS;
    }

    private static void putOrRemove(final Map<String, Object> values, final String key, @Nullable final Object value) {
        if (value == TrustAllDisabled.INSTANCE && !"*".equals(values.get(key))) {
            return;
        }
        if (value == null || value == TrustAllDisabled.INSTANCE) {
            values.remove(key);
        } else {
            values.put(key, value);
        }
    }

    private static Object propertyValue(final Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean ? value.toString() : value;
    }

    private enum TrustAllDisabled { INSTANCE }

    private static final class Declaration {
        @Nullable final Object value;
        final String origin;
        Declaration(@Nullable final Object value, final String origin) { this.value = value; this.origin = origin; }
    }

    static final class Resolution {
        final Map<String, Object> overrides;
        final ProxyConfig proxy;
        final Map<String, Object> explicitOverrides;
        Resolution(final Map<String, Object> overrides, final ProxyConfig proxy, final Map<String, Object> explicitOverrides) {
            this.overrides = overrides;
            this.proxy = proxy;
            this.explicitOverrides = explicitOverrides;
        }
    }
}
