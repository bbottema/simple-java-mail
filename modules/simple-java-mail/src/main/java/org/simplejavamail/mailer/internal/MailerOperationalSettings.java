package org.simplejavamail.mailer.internal;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.config.AsyncQueueConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolClusterConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.api.mailer.config.MailSendRetryPolicy;
import org.simplejavamail.api.mailer.config.MailSendTelemetryConfig;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.SendingRateLimit;
import org.simplejavamail.api.mailer.config.SessionDebugConfig;
import org.simplejavamail.internal.util.DurationMillis;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static java.util.Objects.requireNonNull;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_ASYNC_QUEUE_CAPACITY;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_ASYNC_QUEUE_OVERFLOW_POLICY;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_ASYNC_QUEUE_WAIT_TIMEOUT;

/** Detached operational choices; runtime executor creation belongs to the accepted Mailer construction plan. */
@Getter(AccessLevel.PACKAGE)
final class MailerOperationalSettings {
    private static final AsyncQueueConfig DEFAULT_QUEUE = new AsyncQueueConfig(
            DEFAULT_ASYNC_QUEUE_CAPACITY, DEFAULT_ASYNC_QUEUE_OVERFLOW_POLICY, DEFAULT_ASYNC_QUEUE_WAIT_TIMEOUT);

    private final boolean legacySmtpContentSupportEnabled;
    private final MailSendTelemetryConfig telemetryConfig;
    private final int threadPoolSize;
    private final Duration threadPoolKeepAliveTime;
    private final UUID clusterKey;
    private final int connectionPoolCoreSize;
    private final int connectionPoolMaxSize;
    private final Duration connectionPoolClaimTimeout;
    private final Duration connectionPoolExpireAfter;
    @Nullable private final Duration connectionPoolExpireAfterCreation;
    private final LoadBalancingStrategy connectionPoolLoadBalancingStrategy;
    private final Map<UUID, ConnectionPoolClusterConfig> connectionPoolClusterConfigs;
    private final boolean transportModeLoggingOnly;
    private final SessionDebugConfig debugConfig;
    private final boolean clientValidationWarningsOnly;
    @Nullable private final ExecutorService executorService;
    private final AsyncQueueConfig asyncQueueConfig;
    @Nullable private final Duration mailSendTimeout;
    @Nullable private final SendingRateLimit messageRateLimit;
    @Nullable private final SendingRateLimit recipientRateLimit;
    @Nullable private final String rateLimitGroup;
    private final boolean rateLimitBurstsAllowed;
    @Nullable private final FailoverPolicy failoverPolicy;
    private final MailSendRetryPolicy mailSendRetryPolicy;

    @Builder(access = AccessLevel.PACKAGE, toBuilder = true)
    private MailerOperationalSettings(final boolean legacySmtpContentSupportEnabled, final MailSendTelemetryConfig telemetryConfig,
            final int threadPoolSize, final Duration threadPoolKeepAliveTime, final UUID clusterKey,
            final int connectionPoolCoreSize, final int connectionPoolMaxSize, final Duration connectionPoolClaimTimeout,
            final Duration connectionPoolExpireAfter, @Nullable final Duration connectionPoolExpireAfterCreation,
            final LoadBalancingStrategy connectionPoolLoadBalancingStrategy,
            final Map<UUID, ConnectionPoolClusterConfig> connectionPoolClusterConfigs, final boolean transportModeLoggingOnly,
            final SessionDebugConfig debugConfig, final boolean clientValidationWarningsOnly,
            @Nullable final ExecutorService executorService, final AsyncQueueConfig asyncQueueConfig,
            @Nullable final Duration mailSendTimeout, @Nullable final SendingRateLimit messageRateLimit,
            @Nullable final SendingRateLimit recipientRateLimit, @Nullable final String rateLimitGroup,
            final boolean rateLimitBurstsAllowed, @Nullable final FailoverPolicy failoverPolicy,
            final MailSendRetryPolicy mailSendRetryPolicy) {
        this.legacySmtpContentSupportEnabled = legacySmtpContentSupportEnabled;
        this.telemetryConfig = requireNonNull(telemetryConfig, "telemetryConfig");
        this.threadPoolSize = threadPoolSize;
        this.threadPoolKeepAliveTime = requireNonNull(threadPoolKeepAliveTime, "threadPoolKeepAliveTime");
        this.clusterKey = requireNonNull(clusterKey, "clusterKey");
        this.connectionPoolCoreSize = connectionPoolCoreSize;
        this.connectionPoolMaxSize = connectionPoolMaxSize;
        this.connectionPoolClaimTimeout = requireNonNull(connectionPoolClaimTimeout, "connectionPoolClaimTimeout");
        this.connectionPoolExpireAfter = requireNonNull(connectionPoolExpireAfter, "connectionPoolExpireAfter");
        this.connectionPoolExpireAfterCreation = connectionPoolExpireAfterCreation;
        this.connectionPoolLoadBalancingStrategy = requireNonNull(connectionPoolLoadBalancingStrategy, "connectionPoolLoadBalancingStrategy");
        this.connectionPoolClusterConfigs = Collections.unmodifiableMap(new LinkedHashMap<>(connectionPoolClusterConfigs));
        this.transportModeLoggingOnly = transportModeLoggingOnly;
        this.debugConfig = requireNonNull(debugConfig, "debugConfig");
        this.clientValidationWarningsOnly = clientValidationWarningsOnly;
        this.executorService = executorService;
        this.asyncQueueConfig = requireNonNull(asyncQueueConfig, "asyncQueueConfig");
        this.mailSendTimeout = mailSendTimeout;
        this.messageRateLimit = messageRateLimit;
        this.recipientRateLimit = recipientRateLimit;
        this.rateLimitGroup = rateLimitGroup;
        this.rateLimitBurstsAllowed = rateLimitBurstsAllowed;
        this.failoverPolicy = failoverPolicy;
        this.mailSendRetryPolicy = requireNonNull(mailSendRetryPolicy, "mailSendRetryPolicy");
    }

    void validateExecution() {
        if (executorService != null) {
            if (!asyncQueueConfig.equals(DEFAULT_QUEUE)) {
                throw new IllegalArgumentException("Configure queue capacity and overflow on the caller-owned executor; built-in async queue settings cannot be combined with withExecutorService");
            }
            return;
        }
        if (threadPoolSize <= 0) {
            throw new IllegalArgumentException("threadPoolSize must be positive");
        }
        if (DurationMillis.toInt(threadPoolKeepAliveTime, "threadPoolKeepAliveTime") < 0) {
            throw new IllegalArgumentException("threadPoolKeepAliveTime must not be negative");
        }
    }

    /** Allocation is explicit and happens only after the complete owned graph passes configuration validation. */
    ExecutorService createOwnedExecutor() {
        if (executorService != null) {
            throw new IllegalStateException("A caller-owned executor must be reused without allocating an owned executor");
        }
        return new MailSendExecutor(threadPoolSize, DurationMillis.toInt(threadPoolKeepAliveTime, "threadPoolKeepAliveTime"), asyncQueueConfig);
    }

    /** Assemble supplied configuration; default resolution and caller-Session ownership have already been settled by the builder. */
    OperationalConfig createOperationalConfig(final MailerSessionSettings sessionSettings, final ExecutorService runtimeExecutor) {
        return new OperationalConfigImpl(
                sessionSettings.copyAdditionalProperties(), sessionSettings.getConnectionConflictPolicy(),
                sessionSettings.getConnectTimeout(), sessionSettings.getReadTimeout(), sessionSettings.getWriteTimeout(),
                sessionSettings.getLocalBindAddress(), sessionSettings.getLocalBindPort(), sessionSettings.getSmtpClientHostname(),
                legacySmtpContentSupportEnabled, telemetryConfig, threadPoolSize, threadPoolKeepAliveTime, clusterKey,
                connectionPoolCoreSize, connectionPoolMaxSize, connectionPoolClaimTimeout, connectionPoolExpireAfter,
                connectionPoolExpireAfterCreation, connectionPoolLoadBalancingStrategy, connectionPoolClusterConfigs,
                transportModeLoggingOnly, debugConfig, clientValidationWarningsOnly,
                Collections.unmodifiableList(new ArrayList<>(sessionSettings.getSslHostsToTrust())),
                sessionSettings.isTrustAllSSLHost(), sessionSettings.isVerifyingServerIdentity(), runtimeExecutor,
                executorService != null, sessionSettings.getCustomMailer(), sessionSettings.getTokenProvider(), asyncQueueConfig,
                mailSendTimeout, messageRateLimit, recipientRateLimit, rateLimitGroup, rateLimitBurstsAllowed, failoverPolicy,
                mailSendRetryPolicy);
    }

    ConnectionPoolConfig poolDefaults() {
        final ConnectionPoolConfig.Builder pool = ConnectionPoolConfig.builder()
                .withCoreSize(connectionPoolCoreSize).withMaxSize(connectionPoolMaxSize)
                .withClaimTimeout(connectionPoolClaimTimeout).withExpireAfter(connectionPoolExpireAfter)
                .withClusterKey(clusterKey).withLoadBalancingStrategy(connectionPoolLoadBalancingStrategy);
        if (connectionPoolExpireAfterCreation == null) {
            pool.clearExpireAfterCreation();
        } else {
            pool.withExpireAfterCreation(connectionPoolExpireAfterCreation);
        }
        return pool.build();
    }
}
