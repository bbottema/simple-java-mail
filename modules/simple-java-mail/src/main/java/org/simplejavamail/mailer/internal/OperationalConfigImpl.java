package org.simplejavamail.mailer.internal;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.config.AsyncQueueConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolClusterConfig;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.api.mailer.config.MailSendRetryPolicy;
import org.simplejavamail.api.mailer.config.OAuth2AccessTokenProvider;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.SessionDebugConfig;
import org.simplejavamail.api.mailer.config.MailSendTelemetryConfig;
import org.simplejavamail.api.mailer.config.SendingRateLimit;
import org.simplejavamail.internal.util.PropertyDiagnosticText;

import java.io.PrintStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * @see OperationalConfig
 */
@AllArgsConstructor
@ToString
@Getter
class OperationalConfigImpl implements OperationalConfig {

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withProperties(Properties)
	 */
	@Getter(AccessLevel.NONE)
	@ToString.Exclude
	private final Properties properties;

	@NotNull private final ConnectionConflictPolicy connectionConflictPolicy;

	/**
	 * @see org.simplejavamail.api.mailer.config.ConnectionConfig.Builder#withConnectTimeout(Duration)
	 */
	@Nullable
	private final Duration connectTimeout;

	/** @see org.simplejavamail.api.mailer.config.ConnectionConfig.Builder#withReadTimeout(Duration) */
	@Nullable
	private final Duration readTimeout;

	/** @see org.simplejavamail.api.mailer.config.ConnectionConfig.Builder#withWriteTimeout(Duration) */
	@Nullable
	private final Duration writeTimeout;

	/** @see OperationalConfig#getSessionTimeout() */
	@Override
	@Nullable
	public Duration getSessionTimeout() {
		return connectTimeout != null && connectTimeout.equals(readTimeout) && connectTimeout.equals(writeTimeout) ? connectTimeout : null;
	}

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withLocalBindAddress(String)
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withLocalBindAddress(String, Integer)
	 */
	@Nullable
	private final String localBindAddress;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withLocalBindAddress(String, Integer)
	 */
	@Nullable
	private final Integer localBindPort;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withSmtpClientHostname(String)
	 */
	@Nullable
	private final String smtpClientHostname;

	/** @see OperationalConfig#isLegacySmtpContentSupportEnabled() */
	private final boolean legacySmtpContentSupportEnabled;

	/** @see OperationalConfig#getTelemetryConfig() */
	@NotNull private final MailSendTelemetryConfig telemetryConfig;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withThreadPoolSize(Integer)
	 */
	private final int threadPoolSize;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withThreadPoolKeepAliveTime(Duration)
	 */
	private final Duration threadPoolKeepAliveTime;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withClusterKey(UUID)
	 */
	@NotNull
	private final UUID clusterKey;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withConnectionPoolCoreSize(Integer)
	 */
	private final int connectionPoolCoreSize;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withConnectionPoolMaxSize(Integer)
	 */
	private final int connectionPoolMaxSize;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withConnectionPoolClaimTimeout(Duration)
	 */
	private final Duration connectionPoolClaimTimeout;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withConnectionPoolExpireAfter(Duration)
	 */
	private final Duration connectionPoolExpireAfter;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withConnectionPoolExpireAfterCreation(Duration)
	 */
	@Nullable
	private final Duration connectionPoolExpireAfterCreation;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withConnectionPoolLoadBalancingStrategy(LoadBalancingStrategy)
	 */
	@NotNull
	private final LoadBalancingStrategy connectionPoolLoadBalancingStrategy;

	/**
	 * @see OperationalConfig#getConnectionPoolClusterConfigs()
	 */
	@NotNull
	private final Map<UUID, ConnectionPoolClusterConfig> connectionPoolClusterConfigs;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withTransportModeLoggingOnly(Boolean)
	 */
	private final boolean transportModeLoggingOnly;
	
	/** @see OperationalConfig#getDebugConfig() */
	@NotNull private final SessionDebugConfig debugConfig;

	/**
	 * @see OperationalConfig#isClientValidationWarningsOnly()
	 */
	private final boolean clientValidationWarningsOnly;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#trustingSSLHosts(String...)
	 */
	@NotNull
	private final List<String> sslHostsToTrust;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#trustingAllHosts(boolean)
	 */
	private final boolean trustAllSSLHost;

	/**
	 * @see OperationalConfig#getVerifyingServerIdentity()
	 */
	@Nullable private final Boolean verifyingServerIdentity;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withExecutorService(ExecutorService)
	 */
	@NotNull
	private final ExecutorService executorService;

	/**
	 * @see InternalMailerBuilder#isExecutorServiceUserProvided()
	 */
	private final boolean executorServiceIsUserProvided;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withCustomMailer(CustomMailer)
	 */
	@Nullable
	private final CustomMailer customMailer;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withOAuth2AccessTokenProvider(OAuth2AccessTokenProvider)
	 */
	@Nullable
	@ToString.Exclude
	@Getter(AccessLevel.NONE)
	private final OAuth2AccessTokenProvider oauth2AccessTokenProvider;

	/** @see OperationalConfig#getAsyncQueueConfig() */
	@NotNull private final AsyncQueueConfig asyncQueueConfig;

	/** @see OperationalConfig#getMailSendTimeout() */
	@Nullable private final Duration mailSendTimeout;

	/** @see OperationalConfig#getMessageRateLimit() */
	@Nullable private final SendingRateLimit messageRateLimit;

	/** @see OperationalConfig#getRecipientRateLimit() */
	@Nullable private final SendingRateLimit recipientRateLimit;

	/** @see OperationalConfig#getRateLimitGroup() */
	@Nullable private final String rateLimitGroup;

	/** @see OperationalConfig#isRateLimitBurstsAllowed() */
	private final boolean rateLimitBurstsAllowed;

	/** @see OperationalConfig#getFailoverPolicy() */
	@Nullable private final FailoverPolicy failoverPolicy;
	/** @see OperationalConfig#getMailSendRetryPolicy() */
	@NotNull private final MailSendRetryPolicy mailSendRetryPolicy;

	/** @see OperationalConfig#getProperties() */
	@Override
	@NotNull
	public Properties getProperties() {
		final Properties copy = new Properties();
		copy.putAll(properties);
		return copy;
	}

	@ToString.Include(name = "properties")
	private String propertiesForDisplay() {
		return PropertyDiagnosticText.render(properties);
	}

	@Override
	@Nullable
	public OAuth2AccessTokenProvider getOAuth2AccessTokenProvider() {
		return oauth2AccessTokenProvider;
	}
}
