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
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.config.ConfigLoader.Property;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_TRUST_ALL_HOSTS;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_TRUSTED_HOSTS;
import static java.util.Objects.requireNonNull;

/** Resolves connection overrides before construction; applying them waits until the Mailer exclusively owns its Session. */
@Builder(access = AccessLevel.PACKAGE)
@Getter
final class MailerSessionSettings {

    @Nullable private final TransportStrategy transportStrategy;
    private final Map<String, Object> connectionOverrides;
    @Nullable private final Duration sessionTimeout;
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

    static MailerSessionSettings resolve(@Nullable final Session suppliedSession, @Nullable final TransportStrategy strategy,
            final Properties additionalProperties, final Map<Property, Object> connectionChoices, final Map<String, Object> lockedValues,
            final boolean applyDebugEnabled, final boolean applyDebugOutput) {
        final Properties effective = copyProperties(suppliedSession);
        effective.putAll(additionalProperties);
        final Map<String, Object> overrides = connectionOverrides(strategy, connectionChoices, effective);
        applyOverrides(effective, overrides);
        verifyRemovedSettings(effective, overrides);
        applyLockedExtraProperties(effective, lockedValues);
        return snapshot(strategy, overrides, effective, suppliedSession, applyDebugEnabled, applyDebugOutput);
    }

    /** Package-private Mailer test constructors supply already-resolved configuration rather than a builder's customization history. */
    static MailerSessionSettings fromOperationalConfig(final OperationalConfig config, @Nullable final TransportStrategy strategy) {
        final Map<Property, Object> choices = new LinkedHashMap<>();
        if (strategy != null) {
            if (config.getSessionTimeout() != null) {
                choices.put(Property.DEFAULT_SESSION_TIMEOUT_MILLIS, DurationMillis.toInt(config.getSessionTimeout(), "sessionTimeout"));
            }
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
        return resolve(null, strategy, config.getProperties(), choices, Collections.emptyMap(), true, true);
    }

    private static Properties copyProperties(@Nullable final Session suppliedSession) {
        if (suppliedSession == null) {
            return new Properties();
        }
        final Properties source = suppliedSession.getProperties();
        final Properties inherited = (Properties) source.clone();
        // Keep defaults hidden behind direct entries, but never call a custom Properties.putAll/clear before claiming Session ownership.
        inherited.entrySet().clear();
        final Properties copy = new Properties(inherited);
        copy.putAll(source);
        return copy;
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

    private static Map<String, Object> connectionOverrides(@Nullable final TransportStrategy strategy,
            final Map<Property, Object> choices, final Properties baseline) {
        final Map<String, Object> overrides = new LinkedHashMap<>();
        if (strategy == null) {
            if (!choices.isEmpty()) {
                throw new IllegalArgumentException("The supplied Session selects a custom mail.transport.protocol, so Simple Java Mail cannot apply "
                        + "connection options such as withSessionTimeout(...), withLocalBindAddress(...), trustingSSLHosts(...) or "
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
            overrides.put(trustKey, hosts == null || hosts.isEmpty() || "*".equals(hosts) ? null : hosts);
        }
    }

    private static void applyLockedExtraProperties(final Properties effective, final Map<String, Object> lockedValues) {
        lockedValues.forEach((key, value) -> {
            if (key.startsWith("simplejavamail.extraproperties.")) {
                effective.put(key.substring("simplejavamail.extraproperties.".length()), value);
            }
        });
    }

    private static MailerSessionSettings snapshot(@Nullable final TransportStrategy strategy, final Map<String, Object> overrides,
            final Properties effective, @Nullable final Session suppliedSession, final boolean applyDebugEnabled, final boolean applyDebugOutput) {
        final String trusted = strategy == null ? null : effective.getProperty(strategy.propertyNameSSLTrust());
        return MailerSessionSettings.builder()
                .transportStrategy(strategy)
                .debugConfig(debugBaseline(suppliedSession))
                .applyDebugEnabled(applyDebugEnabled)
                .applyDebugOutput(applyDebugOutput)
                .connectionOverrides(Collections.unmodifiableMap(new LinkedHashMap<>(overrides)))
                .sessionTimeout(commonTimeout(effective, strategy))
                .localBindAddress(strategy == null ? null : effective.getProperty(strategy.propertyNameLocalAddress()))
                .localBindPort(strategy == null ? null : integerValue(propertyValue(effective, strategy.propertyNameLocalPort())))
                .smtpClientHostname(strategy == null ? null : effective.getProperty(strategy.propertyNameLocalHost()))
                .sslHostsToTrust(trustedHosts(trusted))
                .trustAllSSLHost("*".equals(trusted))
                .verifyingServerIdentity(strategy != null && booleanValue(propertyValue(effective, strategy.propertyNameCheckServerIdentity())))
                .build();
    }

    private static SessionDebugConfig debugBaseline(@Nullable final Session suppliedSession) {
        return suppliedSession == null
                ? SessionDebugConfig.builder().withEnabled(MailerGenericBuilder.DEFAULT_JAVAXMAIL_DEBUG).withOutput(MailerGenericBuilder.DEFAULT_JAVAXMAIL_DEBUG_OUTPUT).build()
                : SessionDebugConfig.builder().withEnabled(suppliedSession.getDebug()).withPrinter(suppliedSession.getDebugOut()).build();
    }

    private static List<String> trustedHosts(@Nullable final String trusted) {
        return trusted == null || trusted.trim().isEmpty() || "*".equals(trusted)
                ? Collections.emptyList() : Collections.unmodifiableList(Arrays.asList(trusted.trim().split("\\s+")));
    }

    private static boolean booleanValue(@Nullable final Object value) {
        return Boolean.TRUE.equals(value) || value instanceof String && "true".equalsIgnoreCase((String) value);
    }

    @Nullable
    private static Duration commonTimeout(final Properties effective, @Nullable final TransportStrategy strategy) {
        if (strategy == null) {
            return null;
        }
        final Integer connection = integerValue(propertyValue(effective, strategy.propertyNameConnectionTimeout()));
        final Integer read = integerValue(propertyValue(effective, strategy.propertyNameTimeout()));
        final Integer write = integerValue(propertyValue(effective, strategy.propertyNameWriteTimeout()));
        return connection != null && connection.equals(read) && connection.equals(write) ? Duration.ofMillis(connection) : null;
    }

    @Nullable
    private static Integer integerValue(@Nullable final Object value) {
        if (value instanceof Integer) {
            return (Integer) value;
        }
        try {
            return value instanceof String ? Integer.valueOf((String) value) : null;
        } catch (NumberFormatException unusableValue) {
            return null; // Leave provider-owned behavior intact, but do not claim a configured common value.
        }
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
        applyOverrides(session.getProperties(), connectionOverrides);
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
