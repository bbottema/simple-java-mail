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
import org.simplejavamail.internal.configresolution.ConfigurationChoice;
import org.simplejavamail.internal.configresolution.ConfigurationDeclaration;
import org.simplejavamail.internal.configresolution.ConfigurationOrigin;
import org.simplejavamail.internal.configresolution.ConfigurationResolution;
import org.simplejavamail.internal.configresolution.ConfigurationResolver;
import org.simplejavamail.internal.configresolution.ConfigurationSetting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.net.InetAddress;
import java.util.function.Function;

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
    private final Map<ConfigurationSetting<Object>, List<ConfigurationDeclaration<Object>>> declarations = new LinkedHashMap<>();
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

    static ResolvedConnection resolve(final CapturedConnectionInputs inputs) {
        final SimpleJavaMailConfig config = inputs.getConfig();
        final Session suppliedSession = inputs.getSuppliedSession();
        final TransportStrategy strategy = inputs.getStrategy();
        final Properties additionalProperties = inputs.copyAdditionalProperties();
        final Map<String, Object> rawChoices = inputs.getRawChoices();
        final Map<Property, Object> typedChoices = inputs.getTypedChoices();
        final Map<String, Object> defaults = inputs.getDefaults();
        final Map<String, Object> physicalChoices = inputs.getPhysicalChoices();
        final Map<String, Object> requiredOverrides = inputs.getRequiredOverrides();
        final ProxyConfig proxyFallback = inputs.getProxyFallback();
        final boolean proxyCustomized = inputs.isProxyCustomized();
        final boolean smtpActive = inputs.isSmtpActive();
        final ConnectionConfigurationResolver resolver = new ConnectionConfigurationResolver(config, strategy, proxyFallback.requiresAuthentication());
        final Map<String, Object> baseline = resolver.captureConnectionBaseline(suppliedSession, additionalProperties);
        final Map<Property, Object> applicableTypedChoices = smtpActive ? typedChoices : inactiveRouteChoices(typedChoices);
        final Map<String, Object> typed = resolver.projectTypedChoices(applicableTypedChoices, baseline, physicalChoices, smtpActive, suppliedSession == null);
        resolver.collectDeclarations(suppliedSession, rawChoices, typedChoices, typed, physicalChoices, smtpActive);

        final Map<String, Object> effective = resolver.resolveConnectionValues(suppliedSession, additionalProperties, defaults, baseline, rawChoices, typed);
        resolver.applyTransportResets(effective, baseline, applicableTypedChoices);
        requiredOverrides.forEach((key, value) -> putOrRemove(effective, key, value));
        final ProxyConfig proxy;
        if (smtpActive && strategy != null) {
            proxy = resolver.resolveRemoteProxy(effective, typed, proxyFallback, suppliedSession == null || proxyCustomized);
            resolver.applyRemoteProxy(effective, typed, proxy);
        } else {
            proxy = proxyFallback;
        }
        final Map<String, Object> overrides = resolver.collectSessionOverrides(effective, typed, rawChoices);
        requiredOverrides.forEach((key, value) -> {
            if ((value == null || value == TrustAllDisabled.INSTANCE) && !effective.containsKey(key)) {
                overrides.put(key, null);
            }
        });
        if (suppliedSession != null) {
            resolver.retainRequestedSessionChanges(overrides, typed, additionalProperties, requiredOverrides, proxyCustomized);
            if (!proxyCustomized && !locksOf(config).contains(Property.PROXY_PORT)
                    && strategy != null && !baseline.containsKey(strategy.propertyNameSocksPort())) {
                overrides.remove(strategy.propertyNameSocksPort());
            }
        }
        return new ResolvedConnection(inputs, overrides, proxy,
                resolver.collectExplicitPhysicalChoices(typed, effective, proxy),
                resolver.shadowedFactoryClassProperty(suppliedSession, additionalProperties, typed, effective), resolver.declarations);
    }

    private void retainRequestedSessionChanges(final Map<String, Object> overrides, final Map<String, Object> typed,
            final Properties additional, final Map<String, Object> required, final boolean proxyCustomized) {
        final boolean proxyRequested = proxyCustomized || locksOf(config).contains(Property.PROXY_HOST) || locksOf(config).contains(Property.PROXY_PORT);
        overrides.entrySet().removeIf(entry -> {
            final String key = entry.getKey();
            if (strategy != null && (key.equals(strategy.propertyNameSocksHost()) || key.equals(strategy.propertyNameSocksPort()))) {
                // Raw overlays already retain their provider endpoint syntax; only a typed route or requirement needs an additional projection.
                return !proxyRequested;
            }
            return !typed.containsKey(key) && !required.containsKey(key) && !additional.containsKey(key);
        });
    }

    static void validateDeclarations(final ResolvedConnection resolved) {
        final CapturedConnectionInputs inputs = resolved.inputs;
        if (inputs.isSmtpActive() && inputs.getStrategy() != null) {
            final ConnectionConfigurationResolver validator = new ConnectionConfigurationResolver(inputs.getConfig(), inputs.getStrategy(),
                    inputs.getProxyFallback().requiresAuthentication());
            validator.declarations.putAll(resolved.declarations);
            validator.verifyCompatibleLocks();
            if (inputs.getPolicy() == ConnectionConflictPolicy.REJECT_CONFLICTS) {
                validator.declarations.forEach(validator::verifyEquivalentDeclarations);
            }
        }
    }

    private Map<String, Object> captureConnectionBaseline(@Nullable final Session suppliedSession, final Properties additionalProperties) {
        final Properties properties = suppliedSession == null ? new Properties() : SessionPropertyObservation.snapshot(suppliedSession.getProperties());
        properties.putAll(additionalProperties);
        final Map<String, Object> baseline = visibleProperties(properties);
        normalizeRawProxyEndpoint(baseline);
        baseline.entrySet().removeIf(entry -> isMatchingPhysicalProxyLock(entry.getKey(), entry.getValue()));
        return baseline;
    }

    private Map<String, Object> projectTypedChoices(final Map<Property, Object> typedChoices, final Map<String, Object> baseline,
            final Map<String, Object> physicalChoices, final boolean smtpActive, final boolean ownsSession) {
        final Map<Property, Object> completedChoices = new LinkedHashMap<>(typedChoices);
        if (ownsSession && completedChoices.containsKey(Property.DEFAULT_TRUSTED_HOSTS)
                && !completedChoices.containsKey(Property.DEFAULT_TRUST_ALL_HOSTS)
                && Boolean.TRUE.equals(config.get(ConfigKeys.DEFAULT_TRUST_ALL_HOSTS))) {
            completedChoices.put(Property.DEFAULT_TRUST_ALL_HOSTS, true);
        }
        final Map<String, Object> typed = project(completedChoices, baseline);
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

    private void collectDeclarations(@Nullable final Session suppliedSession, final Map<String, Object> rawChoices,
            final Map<Property, Object> typedChoices, final Map<String, Object> typed, final Map<String, Object> physicalChoices,
            final boolean smtpActive) {
        if (smtpActive && strategy != null) {
            collectFactoryDeclarations();
            collectRawDeclarations(suppliedSession, rawChoices);
            typed.forEach((key, value) -> declare(key, value, ConfigurationOrigin.of(ConfigurationOrigin.Kind.JAVA, key,
                    physicalChoices.containsKey(key) ? "typed Java TLS setting (withCustomSSLFactoryInstance/withTlsConfig)"
                            : typedLabel(key, typedChoices, strategy))));
        }
    }

    private Map<String, Object> resolveConnectionValues(@Nullable final Session suppliedSession, final Properties additionalProperties, final Map<String, Object> defaults,
            final Map<String, Object> baseline, final Map<String, Object> rawChoices, final Map<String, Object> typed) {
        final Map<String, Object> effective = new LinkedHashMap<>();
        if (suppliedSession == null) {
            defaults.forEach((key, value) -> effective.put(key, propertyValue(value)));
        }
        final Set<String> keys = new LinkedHashSet<>(effective.keySet());
        keys.addAll(baseline.keySet());
        keys.addAll(typed.keySet());
        for (String key : keys) {
            final List<ConfigurationDeclaration<Object>> candidates = new ArrayList<>();
            addCandidate(candidates, effective, key, ConfigurationOrigin.Kind.DERIVED);
            final ConfigurationOrigin.Kind baselineKind = rawChoices.get(key) != null ? ConfigurationOrigin.Kind.JAVA_RAW
                    : additionalProperties.containsKey(key) ? ConfigurationOrigin.Kind.FACTORY_RAW : ConfigurationOrigin.Kind.SESSION;
            addCandidate(candidates, baseline, key, baselineKind);
            addCandidate(candidates, typed, key, ConfigurationOrigin.Kind.JAVA);
            final ConfigurationResolution<Object> choice = ConfigurationResolver.resolve(
                    ConnectionSettings.projectedProperty(key), candidates, ConnectionRules.PROJECTED_MEMBER);
            final Object value = choice.valueOrNull();
            if (choice.getSelected() != null && choice.getSelected().getOrigin().getKind() == ConfigurationOrigin.Kind.JAVA
                    && value != null && value != TrustAllDisabled.INSTANCE && baseline.containsKey(key) && sameValue(key, value, baseline.get(key))) {
                effective.put(key, baseline.get(key));
            } else {
                // A conditional trust request is completed against the selected lower-priority representation.
                if (value == TrustAllDisabled.INSTANCE && baseline.containsKey(key)) { effective.put(key, baseline.get(key)); }
                final ConfigurationOrigin.Kind selectedKind = choice.getSelected() == null ? null : choice.getSelected().getOrigin().getKind();
                final boolean typedRepresentation = selectedKind == ConfigurationOrigin.Kind.JAVA || selectedKind == ConfigurationOrigin.Kind.DERIVED;
                putOrRemove(effective, key, typedRepresentation ? propertyValue(value) : value);
            }
        }
        return effective;
    }

    private static void addCandidate(final List<ConfigurationDeclaration<Object>> candidates, final Map<String, Object> values,
            final String key, final ConfigurationOrigin.Kind origin) {
        if (values.containsKey(key)) {
            final Object value = values.get(key);
            candidates.add(ConfigurationDeclaration.of(ConnectionSettings.projectedProperty(key),
                    value == null ? ConfigurationChoice.remove() : ConfigurationChoice.set(value),
                    ConfigurationOrigin.of(origin, key, null)));
        }
    }

    private static final class ConnectionRules {
        private static final List<ConfigurationOrigin.Kind> PROJECTED_MEMBER = List.of(
                ConfigurationOrigin.Kind.JAVA, ConfigurationOrigin.Kind.JAVA_RAW, ConfigurationOrigin.Kind.FACTORY_RAW,
                ConfigurationOrigin.Kind.SESSION, ConfigurationOrigin.Kind.DERIVED);
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

    private Map<String, Object> collectExplicitPhysicalChoices(final Map<String, Object> typed, final Map<String, Object> effective, final ProxyConfig proxy) {
        final Map<String, Object> explicitOverrides = new LinkedHashMap<>(typed);
        explicitOverrides.replaceAll((key, value) -> value == TrustAllDisabled.INSTANCE ? effective.get(key) : value);
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
            declare(key, value, factoryOrigin(name, ConfigurationOrigin.Kind.FACTORY));
        });
    }

    private void collectRawDeclarations(@Nullable final Session suppliedSession, final Map<String, Object> rawChoices) {
        final Map<String, String> captured = config.get(ConfigKeys.EXTRA_PROPERTIES);
        if (captured != null) {
            collectRawSource(captured, key -> factoryOrigin(EXTRA_PREFIX + key, ConfigurationOrigin.Kind.FACTORY_RAW));
        }
        collectRawSource(visibleProperties(suppliedSession), key -> ConfigurationOrigin.of(ConfigurationOrigin.Kind.SESSION, key,
                suppliedSession != null && ConnectionPropertyValues.readsString(key)
                        && !(suppliedSession.getProperties().get(key) instanceof String) && suppliedSession.getProperty(key) != null
                        ? "supplied Session defaults" : "supplied Session"));
        final Map<String, Object> finalRawChoices = new LinkedHashMap<>();
        rawChoices.forEach((key, value) -> {
            if (value != null || captured != null && captured.containsKey(key)) {
                finalRawChoices.put(key, value);
            }
        });
        collectRawSource(finalRawChoices, key -> ConfigurationOrigin.of(ConfigurationOrigin.Kind.JAVA_RAW, key,
                "withProperty/withProperties(" + key + ")"));
    }

    private void collectRawSource(final Map<String, ?> source, final Function<String, ConfigurationOrigin> origin) {
        final Map<String, Object> normalized = new LinkedHashMap<>(source);
        normalized.entrySet().removeIf(entry -> isMatchingPhysicalProxyLock(entry.getKey(), entry.getValue()));
        normalizeRawProxyEndpoint(normalized);
        normalized.forEach((key, value) -> {
            final String originalKey = strategy != null && key.equals(strategy.propertyNameSocksPort()) && !source.containsKey(key)
                    ? strategy.propertyNameSocksHost() : key;
            declare(key, value, origin.apply(originalKey));
        });
    }

    private boolean isMatchingPhysicalProxyLock(final String key, @Nullable final Object value) {
        return authenticatedProxy && strategy != null && (key.equals(strategy.propertyNameSocksHost()) || key.equals(strategy.propertyNameSocksPort()))
                && locksOf(config).getValues().containsKey(EXTRA_PREFIX + key)
                && sameValue(key, value, locksOf(config).getValues().get(EXTRA_PREFIX + key));
    }

    private ConfigurationOrigin factoryOrigin(final String property, final ConfigurationOrigin.Kind kind) {
        final String source = sourceNames.get(property);
        return ConfigurationOrigin.of(kind, property, source == null ? "captured factory configuration" : source);
    }

    private void declare(final String key, @Nullable final Object value, final ConfigurationOrigin origin) {
        if (isSupported(key) || key.equals(PASSWORD) || key.equals(PROXY_USERNAME) || key.equals(PROXY_PASSWORD) || key.equals(PROXY_BRIDGE_PORT)) {
            final ConfigurationSetting<Object> setting = ConnectionSettings.projectedProperty(key);
            declarations.computeIfAbsent(setting, ignored -> new ArrayList<>()).add(ConfigurationDeclaration.of(setting,
                    value == null ? ConfigurationChoice.remove() : ConfigurationChoice.set(value), origin));
        }
    }

    private void verifyEquivalentDeclarations(final ConfigurationSetting<Object> setting, final List<ConfigurationDeclaration<Object>> choices) {
        final String key = ConnectionSettings.propertyName(setting);
        for (int first = 0; first < choices.size(); first++) {
            for (int second = first + 1; second < choices.size(); second++) {
                if (!sameValue(key, choices.get(first).getChoice().valueOrNull(), choices.get(second).getChoice().valueOrNull())) {
                    throw conflictingDeclarations(key, choices.get(first), choices.get(second));
                }
            }
        }
    }

    private IllegalArgumentException conflictingDeclarations(final String key, final ConfigurationDeclaration<Object> first,
            final ConfigurationDeclaration<Object> second) {
        final String lockedProperty = lockedPropertyFor(key);
        if (lockedProperty != null) {
            return locksOf(config).conflict(lockedProperty,
                    "The supplied or selected SMTP configuration has a different value for Jakarta Mail property "
                            + escapeControlCharacters(key) + ", so it does not satisfy this lock. "
                            + "Correct the supplied Session, additional properties, or builder setting to agree with the lock. "
                            + "The connection conflict policy cannot override locks.");
        }
        return new IllegalArgumentException("Conflicting connection configuration for " + escapeControlCharacters(key)
                + ": " + first.getOrigin().description() + " disagrees with " + second.getOrigin().description()
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
                projected.put(key, ConnectionPropertyValues.boundedTrustValue((String) hosts));
            } else {
                // Disabling wildcard trust is a constraint on the completed choice, not a new declaration of its inherited host list.
                projected.put(key, TrustAllDisabled.INSTANCE);
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
                applyCapturedProxy || portLocked ? fallback.getRemoteProxyPort()
                        : host != null ? org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_PROXY_PORT : null);
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
        return ConnectionPropertyValues.isSupported(key);
    }

    static Map<String, Object> visibleProperties(@Nullable final Session session) {
        return session == null ? new LinkedHashMap<>() : visibleProperties(session.getProperties());
    }

    private static Map<String, Object> visibleProperties(final Properties properties) {
        final Map<String, Object> values = new LinkedHashMap<>();
        final Set<String> keys = new LinkedHashSet<>(properties.stringPropertyNames());
        properties.keySet().forEach(key -> { if (key instanceof String) { keys.add((String) key); } });
        for (String key : keys) {
            final SessionPropertyObservation<?> observed = ConnectionPropertyValues.readsString(key)
                    ? SessionPropertyObservation.readString(properties, key) : SessionPropertyObservation.readValue(properties, key);
            final Object visible = observed.providerValueOrNull();
            values.put(key, visible == null && observed.hasDirectEntry() ? observed.directValueOrNull() : visible);
        }
        return values;
    }

    private void normalizeRawProxyEndpoint(final Map<String, Object> values) {
        if (strategy == null) { return; }
        final Object rawHost = values.get(strategy.propertyNameSocksHost());
        if (!(rawHost instanceof String)) { return; }
        final String host = (String) rawHost;
        final int separator = host.indexOf(':');
        final String hostname = separator < 0 ? host : host.substring(0, separator);
        values.put(strategy.propertyNameSocksHost(), hostname.isEmpty() ? InetAddress.getLoopbackAddress().getHostAddress() : hostname);
        if (separator >= 0 && !values.containsKey(strategy.propertyNameSocksPort())) {
            final Integer inlinePort = ConnectionPropertyValues.inlineSocksPort(host.substring(separator + 1));
            if (inlinePort != null) { values.put(strategy.propertyNameSocksPort(), inlinePort.toString()); }
        }
    }

    private static boolean sameValue(final String key, @Nullable final Object first, @Nullable final Object second) {
        if (key.endsWith(".ssl.trust") && (first == TrustAllDisabled.INSTANCE || second == TrustAllDisabled.INSTANCE)) {
            return !"*".equals(first) && !"*".equals(second);
        }
        return ConnectionPropertyValues.equivalent(key, first, second);
    }

    @Nullable
    static Integer integer(@Nullable final Object value, final String key) {
        if (value == null) {
            return null;
        }
        final Integer parsed = ConnectionPropertyValues.integerValue(value);
        if (parsed != null) { return parsed; }
        throw new IllegalArgumentException("Invalid integer connection setting " + key + ". Configure a numeric port.");
    }

    @Nullable
    private static String text(@Nullable final Object value) {
        return value instanceof String && !((String) value).isEmpty() ? (String) value : null;
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

    enum TrustAllDisabled {
        INSTANCE
    }

}
