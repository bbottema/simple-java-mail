package org.simplejavamail.mailer.internal;

import java.time.Duration;

import jakarta.mail.Session;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.internal.util.DurationMillis;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.SessionDebugConfig;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.api.mailer.config.OAuth2AccessTokenProvider;
import org.simplejavamail.config.ConfigLoader.Property;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_TRUST_ALL_HOSTS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_TRUSTED_HOSTS;
import static java.util.Objects.requireNonNull;

/** Resolves connection overrides before construction; applying them waits until the Mailer exclusively owns its Session. */
@Builder(access = AccessLevel.PACKAGE, toBuilder = true)
@Getter
final class MailerSessionSettings {

    @Nullable private final TransportStrategy transportStrategy;
    private final Map<String, Object> connectionOverrides;
    @Nullable private final String shadowedFactoryClassProperty;
    @Nullable private final ProxyConfig proxyConfig;
    @Getter(AccessLevel.NONE) private final Properties effectiveProperties;
    @Getter(AccessLevel.NONE) private final Properties additionalProperties;
    private final ConnectionConflictPolicy connectionConflictPolicy;
    @Nullable private final CustomMailer customMailer;
    @Nullable private final OAuth2AccessTokenProvider tokenProvider;
    @Nullable private final Duration connectTimeout;
    @Nullable private final Duration readTimeout;
    @Nullable private final Duration writeTimeout;
    @Nullable private final String localBindAddress;
    @Nullable private final Integer localBindPort;
    @Nullable private final String smtpClientHostname;
    private final List<String> sslHostsToTrust;
    private final boolean trustAllSSLHost;
    private final boolean verifyingServerIdentity;
    private final SessionDebugConfig debugConfig;
    @Getter(AccessLevel.NONE) private final boolean applyDebugEnabled;
    @Getter(AccessLevel.NONE) private final boolean applyDebugOutput;

    boolean shouldApplyDebugEnabled() { return applyDebugEnabled; }
    boolean shouldApplyDebugOutput() { return applyDebugOutput; }

    /** Keep inherited property values visible when validation creates a detached Session from this snapshot. */
    Properties copyEffectiveProperties() {
        return SessionPropertyObservation.snapshot(effectiveProperties);
    }

    Properties copyAdditionalProperties() { return SessionPropertyObservation.snapshot(additionalProperties); }

    Properties finalProperties(final Session baseline, final Properties additionalProperties) {
        final Properties copy = copyProperties(baseline);
        copy.putAll(additionalProperties);
        applyActivationOverrides(copy);
        return copy;
    }

    static MailerSessionSettings resolve(@Nullable final Session suppliedSession, @Nullable final TransportStrategy strategy,
            final Properties additionalProperties, final Map<Property, Object> connectionChoices, final Map<String, Object> lockedValues,
            final boolean applyDebugEnabled, final boolean applyDebugOutput) {
        final Properties effective = copyProperties(suppliedSession);
        effective.putAll(additionalProperties);
        final Map<String, Object> overrides = connectionOverrides(strategy, connectionChoices, effective);
        return resolve(suppliedSession, strategy, additionalProperties, overrides, lockedValues, null, null, applyDebugEnabled, applyDebugOutput);
    }

    static MailerSessionSettings resolve(@Nullable final Session suppliedSession, @Nullable final TransportStrategy strategy,
            final Properties additionalProperties, final Map<String, Object> resolvedOverrides, final Map<String, Object> lockedValues,
            @Nullable final ProxyConfig proxyConfig, @Nullable final String shadowedFactoryClassProperty,
            final boolean applyDebugEnabled, final boolean applyDebugOutput) {
        final Properties effective = copyProperties(suppliedSession);
        effective.putAll(additionalProperties);
        final Map<String, Object> applicationOverrides = new LinkedHashMap<>(resolvedOverrides);
        lockedValues.forEach((key, value) -> {
            if (key.startsWith("simplejavamail.extraproperties.")) {
                applicationOverrides.put(key.substring("simplejavamail.extraproperties.".length()), value);
            }
        });
        applyOverrides(effective, applicationOverrides);
        if (shadowedFactoryClassProperty != null) {
            effective.remove(shadowedFactoryClassProperty);
        }
        verifyRemovedSettings(effective, applicationOverrides);
        return snapshot(strategy, applicationOverrides, effective, suppliedSession, applyDebugEnabled, applyDebugOutput).toBuilder()
                .additionalProperties(SessionPropertyObservation.snapshot(additionalProperties))
                .proxyConfig(proxyConfig)
                .shadowedFactoryClassProperty(shadowedFactoryClassProperty)
                .build();
    }

    /** Package-private Mailer test constructors supply already-resolved configuration rather than a builder's customization history. */
    static MailerSessionSettings fromOperationalConfig(final OperationalConfig config, @Nullable final TransportStrategy strategy) {
        final Map<Property, Object> choices = new LinkedHashMap<>();
        if (strategy != null) {
            addTimeoutChoice(choices, Property.DEFAULT_CONNECT_TIMEOUT_MILLIS, config.getConnectTimeout(), config.getSessionTimeout());
            addTimeoutChoice(choices, Property.DEFAULT_READ_TIMEOUT_MILLIS, config.getReadTimeout(), config.getSessionTimeout());
            addTimeoutChoice(choices, Property.DEFAULT_WRITE_TIMEOUT_MILLIS, config.getWriteTimeout(), config.getSessionTimeout());
            if (config.getLocalBindAddress() != null) {
                choices.put(Property.SMTP_LOCAL_ADDRESS, config.getLocalBindAddress());
            }
            if (config.getLocalBindPort() != null) {
                choices.put(Property.SMTP_LOCAL_PORT, config.getLocalBindPort());
            }
            if (config.getSmtpClientHostname() != null) {
                choices.put(Property.SMTP_CLIENT_HOSTNAME, config.getSmtpClientHostname());
            }
            choices.put(DEFAULT_TRUSTED_HOSTS, String.join(" ", config.getSslHostsToTrust()));
            choices.put(DEFAULT_TRUST_ALL_HOSTS, config.isTrustAllSSLHost());
            choices.put(Property.DEFAULT_VERIFY_SERVER_IDENTITY, config.isVerifyingServerIdentity());
        }
        return resolve(null, strategy, config.getProperties(), choices, Collections.emptyMap(), true, true).toBuilder()
                .connectionConflictPolicy(config.getConnectionConflictPolicy()).customMailer(config.getCustomMailer())
                .tokenProvider(config.getOAuth2AccessTokenProvider()).build();
    }

    private static void addTimeoutChoice(final Map<Property, Object> choices, final Property property,
            @Nullable final Duration individual, @Nullable final Duration common) {
        final Duration timeout = individual == null ? common : individual;
        if (timeout != null) {
            choices.put(property, DurationMillis.toInt(timeout, property.key()));
        }
    }

    private static Properties copyProperties(@Nullable final Session suppliedSession) {
        if (suppliedSession == null) {
            return new Properties();
        }
        return SessionPropertyObservation.snapshot(suppliedSession.getProperties());
    }

    /** The protocol selects property names. A historical SJM marker never establishes ownership of a supplied Session. */
    @Nullable
    static TransportStrategy transportStrategy(final Properties properties) {
        final String protocol = properties.getProperty("mail.transport.protocol", "smtp");
        if ("smtps".equals(protocol)) {
            return TransportStrategy.SMTPS;
        }
        if (!"smtp".equals(protocol)) {
            return null;
        }
        return TransportStrategy.SMTP;
    }

    static Map<String, Object> connectionOverrides(@Nullable final TransportStrategy strategy,
            final Map<Property, Object> choices, final Properties baseline) {
        final Map<String, Object> overrides = new LinkedHashMap<>();
        if (strategy == null) {
            if (!choices.isEmpty()) {
                throw new IllegalArgumentException("The supplied Session selects a custom mail.transport.protocol, so Simple Java Mail cannot apply "
                        + "connection options such as withSessionTimeout(...), withConnectTimeout(...), withReadTimeout(...), withWriteTimeout(...), withLocalBindAddress(...), trustingSSLHosts(...) or "
                        + "verifyingServerIdentity(...) reliably. Configure those settings on the Session with your provider's property names "
                        + "or through withProperty(...), or let the factory create an SMTP Session without supplying one.");
            }
            return overrides;
        }
        choices.forEach((property, value) -> addConnectionOverride(overrides, strategy, property, value));
        addTrustOverride(overrides, strategy, choices, baseline);
        return overrides;
    }

    private static void addConnectionOverride(final Map<String, Object> overrides, final TransportStrategy strategy,
            final Property property, @Nullable final Object value) {
        switch (property) {
            case DEFAULT_SESSION_TIMEOUT_MILLIS:
                final String timeout = requireNonNull(value, "sessionTimeout").toString();
                overrides.put(strategy.propertyNameConnectionTimeout(), timeout);
                overrides.put(strategy.propertyNameTimeout(), timeout);
                overrides.put(strategy.propertyNameWriteTimeout(), timeout);
                break;
            case DEFAULT_CONNECT_TIMEOUT_MILLIS: overrides.put(strategy.propertyNameConnectionTimeout(), requireNonNull(value, "connectTimeout").toString()); break;
            case DEFAULT_READ_TIMEOUT_MILLIS: overrides.put(strategy.propertyNameTimeout(), requireNonNull(value, "readTimeout").toString()); break;
            case DEFAULT_WRITE_TIMEOUT_MILLIS: overrides.put(strategy.propertyNameWriteTimeout(), requireNonNull(value, "writeTimeout").toString()); break;
            case SMTP_LOCAL_ADDRESS: overrides.put(strategy.propertyNameLocalAddress(), stringValue(value)); break;
            case SMTP_LOCAL_PORT: overrides.put(strategy.propertyNameLocalPort(), stringValue(value)); break;
            case SMTP_CLIENT_HOSTNAME: overrides.put(strategy.propertyNameLocalHost(), stringValue(value)); break;
            case DEFAULT_VERIFY_SERVER_IDENTITY: overrides.put(strategy.propertyNameCheckServerIdentity(), stringValue(value)); break;
            default: break; // Certificate trust has two cooperating settings, resolved together below.
        }
    }

    private static void addTrustOverride(final Map<String, Object> overrides, final TransportStrategy strategy,
            final Map<Property, Object> choices, final Properties baseline) {
        if (!choices.containsKey(DEFAULT_TRUST_ALL_HOSTS) && !choices.containsKey(DEFAULT_TRUSTED_HOSTS)) {
            return;
        }
        final String trustKey = strategy.propertyNameSSLTrust();
		final boolean trustAll = Boolean.TRUE.equals(choices.get(DEFAULT_TRUST_ALL_HOSTS));
        if (trustAll) {
            overrides.put(trustKey, "*");
        } else {
            final String hosts = choices.containsKey(DEFAULT_TRUSTED_HOSTS)
                    ? stringValue(choices.get(DEFAULT_TRUSTED_HOSTS)) : baseline.getProperty(trustKey);
            overrides.put(trustKey, ConnectionPropertyValues.boundedTrustValue(hosts));
        }
    }

    private static MailerSessionSettings snapshot(@Nullable final TransportStrategy strategy, final Map<String, Object> overrides,
            final Properties effective, @Nullable final Session suppliedSession, final boolean applyDebugEnabled, final boolean applyDebugOutput) {
        final String trusted = strategy == null ? null : effective.getProperty(strategy.propertyNameSSLTrust());
        return MailerSessionSettings.builder()
                .transportStrategy(strategy)
                .effectiveProperties(effective)
                .debugConfig(debugBaseline(suppliedSession))
                .applyDebugEnabled(applyDebugEnabled)
                .applyDebugOutput(applyDebugOutput)
                .connectionOverrides(Collections.unmodifiableMap(new LinkedHashMap<>(overrides)))
                .connectTimeout(strategy == null ? null : timeoutValue(effective, strategy.propertyNameConnectionTimeout()))
                .readTimeout(strategy == null ? null : timeoutValue(effective, strategy.propertyNameTimeout()))
                .writeTimeout(strategy == null ? null : timeoutValue(effective, strategy.propertyNameWriteTimeout()))
                .localBindAddress(strategy == null ? null : effective.getProperty(strategy.propertyNameLocalAddress()))
                .localBindPort(strategy == null ? null : integerValue(propertyValue(effective, strategy.propertyNameLocalPort())))
                .smtpClientHostname(strategy == null ? null : effective.getProperty(strategy.propertyNameLocalHost()))
                .sslHostsToTrust(ConnectionPropertyValues.trustedHosts(trusted))
                .trustAllSSLHost("*".equals(trusted))
                .verifyingServerIdentity(strategy != null && booleanValue(propertyValue(effective, strategy.propertyNameCheckServerIdentity())))
                .build();
    }

    private static SessionDebugConfig debugBaseline(@Nullable final Session suppliedSession) {
        return suppliedSession == null
                ? SessionDebugConfig.builder().withEnabled(MailerGenericBuilder.DEFAULT_JAVAXMAIL_DEBUG).withOutput(MailerGenericBuilder.DEFAULT_JAVAXMAIL_DEBUG_OUTPUT).build()
                : SessionDebugConfig.builder().withEnabled(suppliedSession.getDebug()).withPrinter(suppliedSession.getDebugOut()).build();
    }

    private static boolean booleanValue(@Nullable final Object value) {
        return Boolean.TRUE.equals(value) || value instanceof String && "true".equalsIgnoreCase((String) value);
    }

    @Nullable
    private static Duration timeoutValue(final Properties effective, final String key) {
        final Integer timeout = integerValue(propertyValue(effective, key));
        return timeout == null ? null : Duration.ofMillis(timeout);
    }

    @Nullable
    private static Integer integerValue(@Nullable final Object value) {
        return ConnectionPropertyValues.integerValue(value);
    }

    @Nullable
    private static String stringValue(@Nullable final Object value) {
        return value == null ? null : value.toString();
    }

    @Nullable
    static Object propertyValue(final Properties properties, final String name) {
        final Object direct = properties.get(name);
        return direct == null ? properties.getProperty(name) : direct;
    }

    private static void verifyRemovedSettings(final Properties effective, final Map<String, Object> overrides) {
        overrides.forEach((key, value) -> {
            if (value == null && propertyValue(effective, key) != null) {
                throw new IllegalArgumentException("Cannot remove Jakarta Mail property " + key + " from the supplied Session: its Properties object "
                        + "inherits a default for that key, so removing the direct value would make the default active again. "
                        + "Create a new Session with independent Properties containing only the settings you want, "
                        + "or choose an explicit replacement value instead of clearing it.");
            }
        });
    }

    void applyConnectionOverrides(final Session session) {
        applyActivationOverrides(session.getProperties());
    }

    private void applyActivationOverrides(final Properties target) {
        applyOverrides(target, connectionOverrides);
        if (shadowedFactoryClassProperty != null) {
            target.remove(shadowedFactoryClassProperty);
        }
    }

    private static void applyOverrides(final Properties target, final Map<String, Object> overrides) {
        overrides.forEach((key, value) -> {
            if (value == null) {
                target.remove(key);
            } else {
                target.put(key, value);
            }
        });
    }
}
