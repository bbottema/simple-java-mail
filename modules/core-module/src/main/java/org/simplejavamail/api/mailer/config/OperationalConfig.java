package org.simplejavamail.api.mailer.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.MailerRegularBuilder;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * Contains the effective configuration that affects how one built {@link Mailer} operates. This includes connection settings such as timeouts, debug mode
 * and which hosts to trust. Unlike factory configuration diagnostics, these values include Mailer builder overrides and generated defaults, but do not
 * describe where a property-backed value originally came from.
 * They describe construction-time configuration; later direct changes to the exposed Session are not tracked by this snapshot.
 * <p>
 * All of these settings are configured on the {@link MailerGenericBuilder}.
 *
 * @see org.simplejavamail.config.SimpleJavaMailConfig#getDiagnostics()
 */
public interface OperationalConfig {
	/**
	 * Returns the resolved connection conflict policy used when constructing this Mailer.
	 * @see MailerGenericBuilder#withConnectionConflictPolicy(ConnectionConflictPolicy)
	 */
	@NotNull ConnectionConflictPolicy getConnectionConflictPolicy();

	/**
	 * Returns the resolved settings, including resource limits even when retries are disabled.
	 * @see MailerGenericBuilder#withMailSendRetryPolicy(MailSendRetryPolicy)
	 */
	@NotNull MailSendRetryPolicy getMailSendRetryPolicy();

	/**
	 * The resolved policy for this Mailer's failover relationship, or null when none is configured. Contains no live Mailer or routing history.
	 *
	 * @see MailerGenericBuilder#withFailoverMailer(Mailer, FailoverPolicy)
	 */
	@Nullable
	FailoverPolicy getFailoverPolicy();
	/** @see org.simplejavamail.api.mailer.config.SendingLimitsConfig.Builder#withMessageRateLimit(int, Duration) */
	@Nullable SendingRateLimit getMessageRateLimit();

	/** @see org.simplejavamail.api.mailer.config.SendingLimitsConfig.Builder#withRecipientRateLimit(int, Duration) */
	@Nullable SendingRateLimit getRecipientRateLimit();

	/** @see org.simplejavamail.api.mailer.config.SendingLimitsConfig.Builder#withRateLimitGroup(String) */
	@Nullable String getRateLimitGroup();

	/** @see org.simplejavamail.api.mailer.config.SendingLimitsConfig.Builder#withRateLimitBurstsAllowed(boolean) */
	boolean isRateLimitBurstsAllowed();

	/** @see org.simplejavamail.api.mailer.config.AsyncExecutionConfig.Builder#withQueueCapacity(int) */
	@NotNull
	AsyncQueueConfig getAsyncQueueConfig();

	/**
	 * Returns the common configured connection, read and write timeout, or {@code null} when these settings differ or no common value is known.
	 * In particular, {@code null} does not mean unlimited: a caller-supplied Session may configure three different timeouts, omit some of them,
	 * or leave their interpretation to its provider. The snapshot does not guess provider defaults.
	 *
	 * @see org.simplejavamail.api.mailer.config.ConnectionConfig.Builder#withSessionTimeout(Duration)
	 */
	@Nullable
	Duration getSessionTimeout();

	/**
	 * Returns the configured socket connection timeout, or {@code null} when no usable value is known. Provider defaults are not guessed.
	 * @see ConnectionConfig.Builder#withConnectTimeout(Duration)
	 */
	@Nullable Duration getConnectTimeout();

	/**
	 * Returns the configured socket read timeout, or {@code null} when no usable value is known. Provider defaults are not guessed.
	 * @see ConnectionConfig.Builder#withReadTimeout(Duration)
	 */
	@Nullable Duration getReadTimeout();

	/**
	 * Returns the configured socket write timeout, or {@code null} when no usable value is known. Provider defaults are not guessed.
	 * @see ConnectionConfig.Builder#withWriteTimeout(Duration)
	 */
	@Nullable Duration getWriteTimeout();

	/**
	 * @return Total send budget, or {@code null} when disabled.
	 * @see MailerGenericBuilder#withMailSendTimeout(Duration)
	 */
	@Nullable Duration getMailSendTimeout();

	/**
	 * @see org.simplejavamail.api.mailer.config.ConnectionConfig.Builder#withLocalBindAddress(String)
	 * @see org.simplejavamail.api.mailer.config.ConnectionConfig.Builder#withLocalBindAddress(String, Integer)
	 */
	@Nullable
	String getLocalBindAddress();

	/**
	 * @see org.simplejavamail.api.mailer.config.ConnectionConfig.Builder#withLocalBindAddress(String, Integer)
	 */
	@Nullable
	Integer getLocalBindPort();

	/**
	 * @see org.simplejavamail.api.mailer.config.ConnectionConfig.Builder#withSmtpClientHostname(String)
	 */
	@Nullable
	String getSmtpClientHostname();

	/** @see org.simplejavamail.api.mailer.config.ConnectionConfig.Builder#withLegacySmtpContentSupport(boolean) */
	boolean isLegacySmtpContentSupportEnabled();

	/**
	 * Resolved telemetry settings, including activation and endpoint permission.
	 * @see MailerGenericBuilder#withTelemetryConfig(MailSendTelemetryConfig)
	 */
	@NotNull MailSendTelemetryConfig getTelemetryConfig();

	/**
	 * @see org.simplejavamail.api.mailer.config.AsyncExecutionConfig.Builder#withThreadPoolSize(Integer)
	 */
	int getThreadPoolSize();

	/**
	 * @see org.simplejavamail.api.mailer.config.AsyncExecutionConfig.Builder#withKeepAliveTime(Duration)
	 */
	Duration getThreadPoolKeepAliveTime();

	/**
	 * @see org.simplejavamail.api.mailer.config.ConnectionPoolConfig.Builder#withCoreSize(Integer)
	 */
	int getConnectionPoolCoreSize();

	/**
	 * @see org.simplejavamail.api.mailer.config.ConnectionPoolConfig.Builder#withMaxSize(Integer)
	 */
	int getConnectionPoolMaxSize();

	/**
	 * @see org.simplejavamail.api.mailer.config.ConnectionPoolConfig.Builder#withClaimTimeout(Duration)
	 */
	Duration getConnectionPoolClaimTimeout();

	/**
	 * @see org.simplejavamail.api.mailer.config.ConnectionPoolConfig.Builder#withExpireAfter(Duration)
	 */
	Duration getConnectionPoolExpireAfter();

	/**
	 * @see org.simplejavamail.api.mailer.config.ConnectionPoolConfig.Builder#withExpireAfterCreation(Duration)
	 */
	@Nullable
	Duration getConnectionPoolExpireAfterCreation();

	/**
	 * @see org.simplejavamail.api.mailer.config.ConnectionPoolConfig.Builder#withLoadBalancingStrategy(LoadBalancingStrategy)
	 */
	@NotNull
	LoadBalancingStrategy getConnectionPoolLoadBalancingStrategy();

	/**
	 * @return Cluster-specific connection pool settings loaded from properties, keyed by cluster key.
	 */
	@NotNull
	Map<UUID, ConnectionPoolClusterConfig> getConnectionPoolClusterConfigs();
	
	/**
	 * @see MailerGenericBuilder#withTransportModeLoggingOnly(Boolean)
	 */
	boolean isTransportModeLoggingOnly();

	/**
	 * Resolved debug settings, including the caller-Session baseline where configuration was absent.
	 * @see MailerGenericBuilder#withDebugConfig(SessionDebugConfig)
	 */
	@NotNull SessionDebugConfig getDebugConfig();

	/**
	 * @see org.simplejavamail.api.mailer.config.ValidationConfig.Builder#withClientValidationWarningsOnly(boolean)
	 */
	boolean isClientValidationWarningsOnly();

	/**
	 * Returns the immutable list of host names captured when this Mailer was built.
	 * @see org.simplejavamail.api.mailer.config.TlsConfig.Builder#trustingSSLHosts(String...)
	 */
	@NotNull
	List<String> getSslHostsToTrust();

	/**
	 * @see org.simplejavamail.api.mailer.config.TlsConfig.Builder#trustingAllHosts(boolean)
	 */
	boolean isTrustAllSSLHost();

	/**
	 * @see org.simplejavamail.api.mailer.config.TlsConfig.Builder#verifyingServerIdentity(boolean)
	 */
	boolean isVerifyingServerIdentity();
	
	/**
	 * Returns a detached copy of the additional properties captured when this Mailer was built. Changing it does not change the Mailer or its Session.
	 * Property values remain available to explicit callers, including secrets and supplied provider objects; automatic descriptions redact known secrets.
	 * @see MailerGenericBuilder#withProperties(Properties)
	 */
	@NotNull
	Properties getProperties();

	/**
	 * @see org.simplejavamail.api.mailer.config.ConnectionPoolConfig.Builder#withClusterKey(UUID)
	 */
	@NotNull
	UUID getClusterKey();

	/**
	 * @see org.simplejavamail.api.mailer.config.AsyncExecutionConfig.Builder#withExecutorService(ExecutorService)
	 */
	@NotNull
	ExecutorService getExecutorService();

	/**
	 * Indicates whether the executor service was provided by the user or internally. Used to determine if Simple Java Mail should shut down the executor
	 * when the connection pool is shut down.
	 */
	boolean isExecutorServiceIsUserProvided();

	/**
	 * @see MailerGenericBuilder#withCustomMailer(CustomMailer)
	 */
	@Nullable
	CustomMailer getCustomMailer();

	/**
	 * @see MailerGenericBuilder#withOAuth2AccessTokenProvider(OAuth2AccessTokenProvider)
	 */
	@Nullable
	OAuth2AccessTokenProvider getOAuth2AccessTokenProvider();
}
