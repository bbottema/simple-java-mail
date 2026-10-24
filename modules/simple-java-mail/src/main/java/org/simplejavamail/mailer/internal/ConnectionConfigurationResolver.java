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
        final Map<String, Object> baseline = resolver.captureConnectionBaseline(suppliedSession, additionalProperties);
        final Map<Property, Object> applicableTypedChoices = smtpActive ? typedChoices : inactiveRouteChoices(typedChoices);
        final Map<String, Object> typed = resolver.projectTypedChoices(applicableTypedChoices, baseline, physicalChoices, smtpActive, suppliedSession == null);
        resolver.verifyDeclarations(suppliedSession, rawChoices, typedChoices, typed, physicalChoices, policy, smtpActive);

        final Map<String, Object> effective = resolver.resolveConnectionValues(suppliedSession, defaults, baseline, rawChoices, typed);
        resolver.applyTransportResets(effective, baseline, applicableTypedChoices);
        final ProxyConfig proxy;
        if (smtpActive && strategy != null) {
            proxy = resolver.resolveRemoteProxy(effective, typed, proxyFallback, suppliedSession == null || proxyCustomized);
            resolver.applyRemoteProxy(effective, typed, proxy);
        } else {
            proxy = proxyFallback;
        }
        return new Resolution(resolver.collectSessionOverrides(effective, typed, rawChoices), proxy,
                resolver.collectExplicitPhysicalChoices(typed, proxy),
                resolver.shadowedFactoryClassProperty(suppliedSession, additionalProperties, typed, effective));
    }

    private Map<String, Object> captureConnectionBaseline(@Nullable final Session suppliedSession, final Properties additionalProperties) {
        final Map<String, Object> baseline = visibleProperties(suppliedSession);
        additionalProperties.forEach((key, value) -> baseline.put(key.toString(), value));
        baseline.entrySet().removeIf(entry -> isMatchingPhysicalProxyLock(entry.getKey(), entry.getValue()));
        return baseline;
    }

    private Map<String, Object> projectTypedChoices(final Map<Property, Object> typedChoices, final Map<String, Object> baseline,
            final Map<String, Object> physicalChoices, final boolean smtpActive, final boolean ownsSession) {
        final Map<String, Object> typed = project(typedChoices, baseline);
        if (ownsSession && strategy != null) {
            preserveRawBaselineForOwnedEmptyText(typed, typedChoices, baseline);
        }
        if (smtpActive) {
            typed.putAll(physicalChoices);
        }
        return typed;
    }

    private void preserveRawBaselineForOwnedEmptyText(final Map<String, Object> projected, final Map<Property, Object> typedChoices,
            final Map<String, Object> baseline) {
        // Owned Session construction historically omitted empty Java text without restoring captured typed defaults.
        // Supplied Sessions instead receive the literal empty text through their explicit overlay.
        for (Property property : List.of(Property.SMTP_LOCAL_ADDRESS, Property.SMTP_CLIENT_HOSTNAME)) {
            if ("".equals(typedChoices.get(property))) {
                for (String key : sessionKeys(property, strategy)) {
                    projected.put(key, baseline.get(key));
                }
            }
        }
    }

    @Nullable
    private String shadowedFactoryClassProperty(@Nullable final Session suppliedSession, final Properties additionalProperties,
            final Map<String, Object> typed, final Map<String, Object> effective) {
        if (suppliedSession != null || strategy == null || typed.get(strategy.propertyNameSSLSocketFactory()) == null) {
            return null;
        }
        final String classProperty = strategy.propertyNameSSLSocketFactoryClass();
        // Retain the class choice for inspection/restoration, but activate the Java instance. Explicit raw class entries remain intact.
        return effective.get(classProperty) != null && !additionalProperties.containsKey(classProperty) ? classProperty : null;
    }

    private void verifyDeclarations(@Nullable final Session suppliedSession, final Map<String, Object> rawChoices,
            final Map<Property, Object> typedChoices, final Map<String, Object> typed, final Map<String, Object> physicalChoices,
            final ConnectionConflictPolicy policy, final boolean smtpActive) {
        if (smtpActive && strategy != null) {
            collectFactoryDeclarations();
            collectRawDeclarations(suppliedSession, rawChoices);
            typed.forEach((key, value) -> declare(key, value, physicalChoices.containsKey(key)
                    ? "typed Java TLS setting (withCustomSSLFactoryInstance/withTlsConfig)" : typedLabel(key, typedChoices, strategy)));
            if (policy == ConnectionConflictPolicy.REJECT_CONFLICTS) {
                rejectConflictingDeclarations();
            }
        }
    }

    private Map<String, Object> resolveConnectionValues(@Nullable final Session suppliedSession, final Map<String, Object> defaults,
            final Map<String, Object> baseline, final Map<String, Object> rawChoices, final Map<String, Object> typed) {
        final Map<String, Object> effective = new LinkedHashMap<>();
        if (suppliedSession == null) {
            defaults.forEach((key, value) -> effective.put(key, propertyValue(value)));
        }
        effective.putAll(baseline);
        applyRawChoices(effective, rawChoices);
        applyTypedChoices(effective, baseline, typed);
        return effective;
    }

    private void applyRawChoices(final Map<String, Object> effective, final Map<String, Object> rawChoices) {
        // A raw removal suppresses a captured overlay, but clearProperties never erases untouched caller Session entries.
        rawChoices.forEach((key, value) -> {
            if (value != null && !isMatchingPhysicalProxyLock(key, value)) {
                effective.put(key, value);
            }
        });
    }

    private static void applyTypedChoices(final Map<String, Object> effective, final Map<String, Object> baseline,
            final Map<String, Object> typed) {
        typed.forEach((key, value) -> {
            if (value != null && value != TrustAllDisabled.INSTANCE && baseline.containsKey(key) && sameValue(key, value, baseline.get(key))) {
                effective.put(key, baseline.get(key));
            } else {
                putOrRemove(effective, key, propertyValue(value));
            }
        });
    }

    private void applyTransportResets(final Map<String, Object> effective, final Map<String, Object> baseline,
            final Map<Property, Object> applicableTypedChoices) {
        if (strategy != null && applicableTypedChoices.containsKey(Property.SMTP_PORT) && applicableTypedChoices.get(Property.SMTP_PORT) == null) {
            effective.put(strategy.propertyNamePort(), String.valueOf(strategy.getDefaultServerPort()));
        }
        if (strategy == TransportStrategy.SMTP && Boolean.FALSE.equals(applicableTypedChoices.get(Property.OPPORTUNISTIC_TLS))
                && !baseline.containsKey("mail.smtp.starttls.enable")) {
            effective.remove("mail.smtp.starttls.enable");
        }
    }

    private Map<String, Object> collectSessionOverrides(final Map<String, Object> effective, final Map<String, Object> typed,
            final Map<String, Object> rawChoices) {
        final Map<String, Object> overrides = new LinkedHashMap<>();
        effective.forEach((key, value) -> {
            if (isSupported(key)) {
                overrides.put(key, value);
            }
        });
        typed.forEach((key, value) -> {
            if ((value == null || value == TrustAllDisabled.INSTANCE) && isSupported(key) && !effective.containsKey(key)) {
                overrides.put(key, null);
            }
        });
        rawChoices.forEach((key, value) -> {
            if (value == null && isSupported(key) && !effective.containsKey(key)) {
                overrides.put(key, null);
            }
        });
        return overrides;
    }

    private Map<String, Object> collectExplicitPhysicalChoices(final Map<String, Object> typed, final ProxyConfig proxy) {
        final Map<String, Object> explicitOverrides = new LinkedHashMap<>(typed);
        explicitOverrides.values().removeIf(value -> value == TrustAllDisabled.INSTANCE);
        if (strategy != null && proxy.requiresProxy() && proxy.requiresAuthentication()) {
            // The selected remote route is checked separately from literal locks on the derived local bridge endpoint.
            explicitOverrides.remove(strategy.propertyNameSocksHost());
            explicitOverrides.remove(strategy.propertyNameSocksPort());
        }
        return explicitOverrides;
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
        final Map<String, Object> projected = projectCapturedSettings(factory);
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
        declarations.forEach(this::verifyEquivalentDeclarations);
    }

    private void verifyEquivalentDeclarations(final String key, final List<Declaration> choices) {
        for (int first = 0; first < choices.size(); first++) {
            for (int second = first + 1; second < choices.size(); second++) {
                if (!sameValue(key, choices.get(first).value, choices.get(second).value)) {
                    throw conflictingDeclarations(key, choices.get(first), choices.get(second));
                }
            }
        }
    }

    private IllegalArgumentException conflictingDeclarations(final String key, final Declaration first, final Declaration second) {
        final String lockedProperty = lockedPropertyFor(key);
        if (lockedProperty != null) {
            return locksOf(config).conflict(lockedProperty,
                    "The supplied or selected SMTP configuration has a different value for Jakarta Mail property "
                            + escapeControlCharacters(key) + ", so it does not satisfy this lock. "
                            + "Correct the supplied Session, additional properties, or builder setting to agree with the lock. "
                            + "The connection conflict policy cannot override locks.");
        }
        return new IllegalArgumentException("Conflicting connection configuration for " + escapeControlCharacters(key)
                + ": " + escapeControlCharacters(first.origin) + " disagrees with " + escapeControlCharacters(second.origin)
                + ". Align or remove the conflicting declaration, or deliberately select "
                + "ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES. Configuration locks and mandatory transport rules still apply.");
    }

    private void verifyCompatibleLocks() {
        final Map<Property, Object> typedLocks = new LinkedHashMap<>();
        config.asMap().forEach((property, value) -> {
            if (locksOf(config).contains(property)) {
                typedLocks.put(property, value);
            }
        });
        final Map<String, Object> projected = projectCapturedSettings(typedLocks);
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

    private Map<String, Object> projectCapturedSettings(final Map<Property, Object> capturedChoices) {
        final Map<Property, Object> providerChoices = new LinkedHashMap<>(capturedChoices);
        final Object configuredHosts = capturedChoices.get(Property.DEFAULT_TRUSTED_HOSTS);
        if (configuredHosts != null) {
            // Only the SJM property uses semicolon-separated hosts; Java choices already contain provider text.
            providerChoices.put(Property.DEFAULT_TRUSTED_HOSTS, String.join(" ", configuredHosts.toString().split(";")));
        }
        return project(providerChoices, Collections.emptyMap());
    }

    private Map<String, Object> project(final Map<Property, Object> choices, final Map<String, Object> baseline) {
        final Map<String, Object> projected = new LinkedHashMap<>();
        if (strategy == null) {
            return projected;
        }
        choices.forEach((property, value) -> {
            for (String key : sessionKeys(property, strategy)) {
                projected.put(key, value);
            }
        });
        if (passwordEnablesAuthentication(choices, strategy)) {
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
                    ? text(choices.get(Property.DEFAULT_TRUSTED_HOSTS)) : baseline.get(key);
            if (Boolean.TRUE.equals(choices.get(Property.DEFAULT_TRUST_ALL_HOSTS))) {
                projected.put(key, "*");
            } else if (choices.containsKey(Property.DEFAULT_TRUSTED_HOSTS)) {
                // Preserve the existing host-list contract: wildcard trust requires the dedicated trust-all choice.
                projected.put(key, "*".equals(hosts) ? null : hosts);
            } else {
                // False means retain a bounded host list but remove wildcard trust; it is not a declaration that no list may exist.
                projected.put(key, hosts == null || "*".equals(hosts) ? TrustAllDisabled.INSTANCE : hosts);
            }
        }
        return projected;
    }

    private static boolean passwordEnablesAuthentication(final Map<Property, Object> choices, final TransportStrategy strategy) {
        return choices.get(Property.SMTP_PASSWORD) != null && !OAuth2AccessTokenResolver.isOAuth2Transport(strategy);
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
        if (key.equals(strategy.propertyNameAuthenticate()) && passwordEnablesAuthentication(choices, strategy)) {
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
            if (Boolean.TRUE.equals(choices.get(Property.DEFAULT_TRUST_ALL_HOSTS)) || !choices.containsKey(Property.DEFAULT_TRUSTED_HOSTS)) {
                return Property.DEFAULT_TRUST_ALL_HOSTS;
            }
            return Property.DEFAULT_TRUSTED_HOSTS;
        }
        return choices.containsKey(Property.TRANSPORT_STRATEGY) ? Property.TRANSPORT_STRATEGY : null;
    }

    private static String typedLabel(final String key, final Map<Property, Object> choices, final TransportStrategy strategy) {
        if (key.equals(strategy.propertyNameAuthenticate()) && passwordEnablesAuthentication(choices, strategy)) {
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

    private ProxyConfig resolveRemoteProxy(final Map<String, Object> effective, final Map<String, Object> typed,
            final ProxyConfig fallback, final boolean applyCapturedProxy) {
        final String hostKey = strategy.propertyNameSocksHost();
        final String portKey = strategy.propertyNameSocksPort();
        if ("".equals(typed.get(hostKey))) {
            throw new IllegalArgumentException("Proxy host cannot be empty. Use clearHost() to remove the proxy endpoint.");
        }
        final boolean hostLocked = locksOf(config).contains(Property.PROXY_HOST);
        final boolean portLocked = locksOf(config).contains(Property.PROXY_PORT);
        final String host = text(selectProxyMember(hostKey, typed, effective,
                applyCapturedProxy || hostLocked ? fallback.getRemoteProxyHost() : null));
        final Object portChoice = selectProxyMember(portKey, typed, effective,
                applyCapturedProxy || portLocked || host != null ? fallback.getRemoteProxyPort() : null);
        final Integer port = integer(portChoice, portKey);
        final ProxyConfig resolved = new ProxyConfigImpl(host, port, fallback.getUsername(), fallback.getPassword(), fallback.getProxyBridgePort());
        locksOf(config).verify(Property.PROXY_HOST, host);
        locksOf(config).verify(Property.PROXY_PORT, port);
        validateActiveProxy(resolved);
        return resolved;
    }

    @Nullable
    private static Object selectProxyMember(final String key, final Map<String, Object> typed, final Map<String, Object> effective,
            @Nullable final Object fallback) {
        if (typed.containsKey(key)) {
            return typed.get(key);
        }
        if (effective.containsKey(key)) {
            return effective.get(key);
        }
        return fallback;
    }

    private static void validateActiveProxy(final ProxyConfig resolved) {
        if (resolved.getRemoteProxyHost() != null) {
            if (resolved.getRemoteProxyPort() == null) {
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
        }
    }

    private void applyRemoteProxy(final Map<String, Object> effective, final Map<String, Object> typed, final ProxyConfig proxy) {
        final String hostKey = strategy.propertyNameSocksHost();
        final String portKey = strategy.propertyNameSocksPort();
        final String host = proxy.getRemoteProxyHost();
        final Integer port = proxy.getRemoteProxyPort();
        if (host != null) {
            effective.put(hostKey, host);
            if (!effective.containsKey(portKey) || !sameValue(portKey, effective.get(portKey), port)) {
                effective.put(portKey, String.valueOf(port));
            }
        } else if (typed.containsKey(hostKey)) {
            effective.remove(hostKey);
        }
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
        if (key.endsWith(".ssl.trust") && (first instanceof String) != (second instanceof String)) {
            // Provider text cannot equal an opaque object just because it resembles the normalized host-list representation.
            return false;
        }
        return Objects.equals(normalize(key, first), normalize(key, second));
    }

    @Nullable
    private static Object normalize(final String key, @Nullable final Object value) {
        if (value == null) {
            return null;
        }
        if (key.endsWith(".ssl.trust")) {
            return normalizeProviderTrust(value);
        }
        if (value instanceof String && ((String) value).isEmpty()) {
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
        return value instanceof String || value instanceof Number || value instanceof Boolean ? value.toString() : value;
    }

    private static Object normalizeProviderTrust(final Object value) {
        if (!(value instanceof String)) {
            return value;
        }
        final String providerText = (String) value;
        // The provider recognizes only exact "*" as wildcard trust. Present empty lists remain distinct from absence.
        return "*".equals(providerText) ? WildcardTrust.INSTANCE : List.of(providerText.trim().split("\\s+"));
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

    @Nullable
    private static String text(@Nullable final Object value) {
        return value == null || value.toString().isEmpty() ? null : value.toString();
    }

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

    private enum TrustAllDisabled {
        INSTANCE
    }

    private enum WildcardTrust {
        INSTANCE
    }

    private static final class Declaration {
        @Nullable final Object value;
        final String origin;
        Declaration(@Nullable final Object value, final String origin) {
            this.value = value;
            this.origin = origin;
        }
    }

    static final class Resolution {
        /** Resolved connection choices retained for inspection, including null entries for deliberate removals. */
        final Map<String, Object> overrides;
        final ProxyConfig proxy;
        /** Explicit choices checked against literal Session locks after separating remote proxy routing. */
        final Map<String, Object> explicitOverrides;
        /** A retained class choice omitted only when activating its preferred Java instance on an owned Session. */
        @Nullable final String shadowedFactoryClassProperty;
        Resolution(final Map<String, Object> overrides, final ProxyConfig proxy, final Map<String, Object> explicitOverrides,
                @Nullable final String shadowedFactoryClassProperty) {
            this.overrides = overrides;
            this.proxy = proxy;
            this.explicitOverrides = explicitOverrides;
            this.shadowedFactoryClassProperty = shadowedFactoryClassProperty;
        }
    }
}
