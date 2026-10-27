package org.simplejavamail.mailer.internal;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.config.ConnectionPoolClusterConfig;
import org.simplejavamail.api.mailer.config.MailSendRetryPolicy;
import org.simplejavamail.api.mailer.config.MailSendTelemetryConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigKeys;
import org.simplejavamail.config.ConfigLoader.Property;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.internal.config.ConfigurationLocks;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.util.DurationMillis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.simplejavamail.internal.config.ConfigurationSnapshot.locksOf;

/** Detached facts for Mailer construction constraints; validation never reads a builder or invokes application callbacks. */
final class MailerConfigurationRequirements {
    private final SimpleJavaMailConfig config;
    private final Map<Property, Object> choices;
    private final Map<Property, Object> serverChoices;
    private final Map<Property, Object> explicitSessionSettings;
    private final Properties properties;
    private final List<ConnectionPoolClusterConfig> clusters;
    private final List<String> sslHostsToTrust;
    private final UUID clusterKey;
    @Getter(AccessLevel.PACKAGE) private final MailSendRetryPolicy retryPolicy;
    @Getter(AccessLevel.PACKAGE) private final MailSendTelemetryConfig telemetryConfig;
    @Nullable private final TransportStrategy strategy;
    private final boolean customMailer;
    private final boolean borrowedExecutor;
    private final boolean tokenProvider;
    private final boolean customFactoryInstance;

    @Builder(access = AccessLevel.PACKAGE)
    private MailerConfigurationRequirements(final SimpleJavaMailConfig config, final Map<Property, Object> choices,
            final Map<Property, Object> serverChoices, final Map<Property, Object> explicitSessionSettings, final Properties properties,
            final Collection<ConnectionPoolClusterConfig> clusters, final List<String> sslHostsToTrust, final UUID clusterKey,
            final MailSendRetryPolicy retryPolicy, final MailSendTelemetryConfig telemetryConfig,
            @Nullable final TransportStrategy strategy, final boolean customMailer, final boolean borrowedExecutor,
            final boolean tokenProvider, final boolean customFactoryInstance) {
        this.config = config;
        this.choices = CapturedConnectionInputs.immutable(choices);
        this.serverChoices = CapturedConnectionInputs.immutable(serverChoices);
        this.explicitSessionSettings = CapturedConnectionInputs.immutable(explicitSessionSettings);
        this.properties = SessionPropertyObservation.snapshot(properties);
        this.clusters = Collections.unmodifiableList(new ArrayList<>(clusters));
        this.sslHostsToTrust = Collections.unmodifiableList(new ArrayList<>(sslHostsToTrust));
        this.clusterKey = clusterKey;
        this.retryPolicy = retryPolicy;
        this.telemetryConfig = telemetryConfig;
        this.strategy = strategy;
        this.customMailer = customMailer;
        this.borrowedExecutor = borrowedExecutor;
        this.tokenProvider = tokenProvider;
        this.customFactoryInstance = customFactoryInstance;
    }

    void validate() {
        final ConfigurationLocks locks = locksOf(config);
        validateTelemetry(locks);
        if (locks.isEmpty()) {
            return;
        }
        ConfiguredMailSendRetry.verifyLocks(locks, retryPolicy);
        choices.forEach(locks::verify);
        validateSocketTimeouts(locks);
        validateTrustedHosts(locks);
        validateClusterKey(locks);
        validateExtraProperties(locks);
        validateExecutor(locks);
        validateCustomMailer(locks);
        LockedSmtpConnectionRequirements.verifyBuilderCustomizations(locks, explicitSessionSettings, strategy);
        validateClusterDefaults(locks);
        validateTokenProvider(locks);
        serverChoices.forEach(locks::verify);
        validateFactoryInstance(locks);
    }

    /** Validate the builder's resolved choices before requesting optional runtime resources. */
    private void validateTelemetry(final ConfigurationLocks locks) {
        locks.verify(Property.TELEMETRY_ENABLED, telemetryConfig.getEnabled());
        locks.verify(Property.TELEMETRY_SMTP_ENDPOINT_ATTRIBUTES, telemetryConfig.getSmtpEndpointAttributes());
        if (Boolean.TRUE.equals(telemetryConfig.getEnabled())) {
            ModuleLoader.loadOpenTelemetryModule();
        }
    }

    private void validateSocketTimeouts(final ConfigurationLocks locks) {
        for (Property property : List.of(Property.DEFAULT_CONNECT_TIMEOUT_MILLIS, Property.DEFAULT_READ_TIMEOUT_MILLIS,
                Property.DEFAULT_WRITE_TIMEOUT_MILLIS)) {
            // Ordinary inherited values yield to locks; only a deliberate builder choice may contradict one.
            if (explicitSessionSettings.containsKey(property)) {
                final Object value = explicitSessionSettings.get(property);
                locks.verify(Property.DEFAULT_SESSION_TIMEOUT_MILLIS, value);
                locks.verify(property, value);
            }
        }
    }

    private void validateTrustedHosts(final ConfigurationLocks locks) {
        if (locks.contains(Property.DEFAULT_TRUSTED_HOSTS)
                && !ConnectionPropertyValues.equivalent("mail.smtp.ssl.trust",
                        String.join(" ", MailerConfigurationResolver.configured(config, ConfigKeys.DEFAULT_TRUSTED_HOSTS,
                                null).split(";")), String.join(" ", sslHostsToTrust))) {
            throw locks.conflict(Property.DEFAULT_TRUSTED_HOSTS, "trustingSSLHosts(...) supplies a different trusted-host list from the one locked by this factory. "
                    + "Remove that builder call, use the locked list, or change this lock in its configuration source.");
        }
    }

    private void validateClusterKey(final ConfigurationLocks locks) {
        if (locks.contains(Property.DEFAULT_CONNECTIONPOOL_CLUSTER_KEY)
                && !clusterKey.equals(UUID.fromString(MailerConfigurationResolver.configured(config, ConfigKeys.DEFAULT_CONNECTIONPOOL_CLUSTER_KEY, null)))) {
            throw locks.conflict(Property.DEFAULT_CONNECTIONPOOL_CLUSTER_KEY,
                    "withClusterKey(...) selects a different pool cluster from the one locked by this factory. "
                    + "Remove that builder call, use the locked cluster identifier, or change this lock in its configuration source.");
        }
    }

    private void validateExtraProperties(final ConfigurationLocks locks) {
        for (String key : locks.getValues().keySet()) {
            if (key.startsWith("simplejavamail.extraproperties.")) {
                final String rawKey = key.substring("simplejavamail.extraproperties.".length());
                if (!ConnectionPropertyValues.isSupported(rawKey)
                        || !ConnectionPropertyValues.equivalent(rawKey, locks.getValues().get(key), properties.get(rawKey))) {
                    locks.verify(key, properties.get(rawKey));
                }
            }
        }
    }

    private void validateExecutor(final ConfigurationLocks locks) {
        if (borrowedExecutor) {
            for (Property property : new Property[] {Property.DEFAULT_POOL_SIZE, Property.DEFAULT_POOL_KEEP_ALIVE_TIME,
                    Property.DEFAULT_ASYNC_QUEUE_CAPACITY, Property.DEFAULT_ASYNC_QUEUE_OVERFLOW_POLICY,
                    Property.DEFAULT_ASYNC_QUEUE_WAIT_TIMEOUT_MILLIS}) {
                if (locks.contains(property)) {
                    throw locks.conflict(property, "withExecutorService(...) supplies an application-managed executor, but this factory locks worker or queue settings. "
                            + "Simple Java Mail cannot configure or verify those settings on your executor. "
                            + "Remove that builder call so the Mailer creates its executor, or remove the corresponding locks from the configuration source.");
                }
            }
        }
    }

    private void validateCustomMailer(final ConfigurationLocks locks) {
        if (!customMailer) {
            return;
        }
        for (String key : locks.getValues().keySet()) {
            final boolean smtpSetting = key.startsWith("simplejavamail.smtp.") && !key.startsWith("simplejavamail.smtp.ratelimit.");
            final boolean requiresMailerControlledEnvelope = LockedEmailConfiguration.requiresMailerControlledEnvelope(key);
            if (smtpSetting || requiresMailerControlledEnvelope || key.startsWith("simplejavamail.proxy.") || key.startsWith("simplejavamail.extraproperties.")
                    || key.equals(Property.TRANSPORT_STRATEGY.key()) || key.equals(Property.CUSTOM_SSLFACTORY_CLASS.key())
                    || key.equals(Property.DEFAULT_TRUST_ALL_HOSTS.key()) || key.equals(Property.DEFAULT_TRUSTED_HOSTS.key())
                    || key.equals(Property.DEFAULT_VERIFY_SERVER_IDENTITY.key()) || key.equals(Property.OPPORTUNISTIC_TLS.key())
                    || key.equals(Property.DEFAULT_SESSION_TIMEOUT_MILLIS.key()) || key.equals(Property.DEFAULT_CONNECT_TIMEOUT_MILLIS.key())
                    || key.equals(Property.DEFAULT_READ_TIMEOUT_MILLIS.key()) || key.equals(Property.DEFAULT_WRITE_TIMEOUT_MILLIS.key())) {
                throw locks.conflict(key, "withCustomMailer(...) hands transport and delivery envelope handling to your callback, "
                        + "but this factory locks a setting that must be applied there. Simple Java Mail cannot check what the callback does with it. "
                        + "Remove withCustomMailer(...) to use this factory's SMTP transport, "
                        + "or remove the corresponding locks from the configuration used for that callback.");
            }
        }
    }

    private void validateClusterDefaults(final ConfigurationLocks locks) {
        for (ConnectionPoolClusterConfig cluster : clusters) {
            verifyClusterOverride(locks, Property.DEFAULT_CONNECTIONPOOL_CORE_SIZE, cluster.getCoreSize());
            verifyClusterOverride(locks, Property.DEFAULT_CONNECTIONPOOL_MAX_SIZE, cluster.getMaxSize());
            verifyClusterOverride(locks, Property.DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT_MILLIS, DurationMillis.toNullableInt(cluster.getClaimTimeout(), "clusterClaimTimeout"));
            verifyClusterOverride(locks, Property.DEFAULT_CONNECTIONPOOL_EXPIREAFTER_MILLIS, DurationMillis.toNullableInt(cluster.getExpireAfter(), "clusterExpireAfter"));
            verifyClusterOverride(locks, Property.DEFAULT_CONNECTIONPOOL_EXPIREAFTERCREATION_MILLIS, DurationMillis.toNullableInt(cluster.getExpireAfterCreation(), "clusterExpireAfterCreation"));
            verifyClusterOverride(locks, Property.DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY, cluster.getLoadBalancingStrategy());
        }
    }

    private static void verifyClusterOverride(final ConfigurationLocks locks, final Property property, @Nullable final Object override) {
        if (override != null) {
            locks.verify(property, override, "A pool-cluster setting overrides this factory's locked connection-pool default. "
                    + "Make the cluster's setting agree with the locked value or remove that cluster override, "
                    + "or change the lock in its configuration source.");
        }
    }

    private void validateTokenProvider(final ConfigurationLocks locks) {
        if (tokenProvider && locks.contains(Property.SMTP_PASSWORD)) {
            throw locks.conflict(Property.SMTP_PASSWORD, "withOAuth2AccessTokenProvider(...) supplies credentials at send time, "
                    + "but this factory locks the SMTP credential. "
                    + "Runtime tokens can replace that locked value, so these settings cannot be combined. Remove that builder call, "
                    + "or remove the SMTP credential lock from the configuration source if credentials should be supplied dynamically.");
        }
    }

    private void validateFactoryInstance(final ConfigurationLocks locks) {
        if (customFactoryInstance && locks.contains(Property.CUSTOM_SSLFACTORY_CLASS)) {
            throw locks.conflict(Property.CUSTOM_SSLFACTORY_CLASS,
                    "withCustomSSLFactoryInstance(...) supplies a factory object, but this configuration locks the factory class to instantiate. "
                    + "The supplied object would replace that locked class choice. Remove the builder call, "
                    + "or remove the factory-class lock from the configuration source if your application should supply the instance.");
        }
    }
}
