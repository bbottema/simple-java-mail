package org.simplejavamail.mailer.internal;

import com.sanctionco.jmail.EmailValidator;
import com.sanctionco.jmail.JMail;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.MailSendObserver;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.config.AsyncQueueConfig;
import org.simplejavamail.api.mailer.config.AsyncQueueOverflowPolicy;
import org.simplejavamail.api.mailer.config.ConnectionPoolClusterConfig;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.api.mailer.config.OAuth2AccessTokenProvider;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.api.mailer.config.SessionDebugOutput;
import org.simplejavamail.api.mailer.config.SendingRateLimit;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader.Property;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.email.internal.EmailStartingBuilderImpl;
import org.simplejavamail.internal.config.ConfigurationLocks;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.util.concurrent.MailSendControl;
import org.simplejavamail.mailer.internal.ratelimit.FactorySendingLimits;

import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

import static java.util.Objects.requireNonNull;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_CONNECTIONPOOL_CLUSTER_KEY;
import static org.simplejavamail.config.ConfigLoader.Property.EXTRA_PROPERTIES;
import static org.simplejavamail.config.ConfigLoader.Property.PROXY_HOST;
import static org.simplejavamail.config.ConfigLoader.Property.PROXY_PASSWORD;
import static org.simplejavamail.config.ConfigLoader.Property.PROXY_USERNAME;
import static org.simplejavamail.internal.util.MiscUtil.checkArgumentNotEmpty;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;
import static org.simplejavamail.internal.util.Preconditions.verifyNonnullOrEmpty;

/**
 * @see MailerGenericBuilder
 */
@SuppressWarnings({"UnusedReturnValue", "unchecked"})
abstract class MailerGenericBuilderImpl<T extends MailerGenericBuilderImpl<?>> implements InternalMailerBuilder<T> {

	private static final AsyncQueueConfig DEFAULT_ASYNC_QUEUE_CONFIG = new AsyncQueueConfig(
			DEFAULT_ASYNC_QUEUE_CAPACITY, DEFAULT_ASYNC_QUEUE_OVERFLOW_POLICY, DEFAULT_ASYNC_QUEUE_WAIT_TIMEOUT_MILLIS);

	@NotNull
	private final SimpleJavaMailConfig config;
	/** Factory runtime state stays separate from the immutable property snapshot and built OperationalConfig. */
	private final FactorySendingLimits sendingLimits;
	private final Map<Property, Object> explicitSessionSettings = new LinkedHashMap<>();
	/** @see MailerGenericBuilder#withMessageRateLimit(int, Duration) */
	@Nullable private SendingRateLimit messageRateLimit;
	/** @see MailerGenericBuilder#withRecipientRateLimit(int, Duration) */
	@Nullable private SendingRateLimit recipientRateLimit;
	/** @see MailerGenericBuilder#withRateLimitGroup(String) */
	@Nullable private String rateLimitGroup;
	/** @see MailerGenericBuilder#withRateLimitBurstsAllowed(boolean) */
	private boolean rateLimitBurstsAllowed;

	/**
	 * @see MailerGenericBuilder#withProxyHost(String)
	 */
	private String proxyHost;

	/**
	 * @see MailerGenericBuilder#withProxyPort(Integer)
	 */
	private Integer proxyPort;

	/**
	 * @see MailerGenericBuilder#withProxyUsername(String)
	 */
	private String proxyUsername;

	/**
	 * @see MailerGenericBuilder#withProxyPassword(String)
	 */
	private String proxyPassword;

	/**
	 * @see MailerGenericBuilder#withProxyBridgePort(Integer)
	 */
	@NotNull
	private Integer proxyBridgePort;

	/**
	 * @see MailerGenericBuilder#withDebugLogging(Boolean)
	 */
	private boolean debugLogging;

	/**
	 * @see MailerGenericBuilder#withDebugPrinter(PrintStream)
	 */
	@Nullable
	private PrintStream debugPrinter;
	@Nullable private SessionDebugOutput debugOutput;

	/**
	 * @see #disablingAllClientValidation(Boolean)
	 */
	private boolean disableAllClientValidation;

	/**
	 * @see MailerGenericBuilder#withSessionTimeout(Integer)
	 */
	@NotNull
	private Integer sessionTimeout;

	/**
	 * @see MailerGenericBuilder#withLocalBindAddress(String)
	 * @see MailerGenericBuilder#withLocalBindAddress(String, Integer)
	 */
	@Nullable
	private String localBindAddress;

	/**
	 * @see MailerGenericBuilder#withLocalBindAddress(String, Integer)
	 */
	@Nullable
	private Integer localBindPort;

	/**
	 * @see MailerGenericBuilder#withSmtpClientHostname(String)
	 */
	@Nullable
	private String smtpClientHostname;

	/** @see MailerGenericBuilder#withLegacySmtpContentSupport(boolean) */
	private boolean legacySmtpContentSupportEnabled;

	/**
	 * @see MailerGenericBuilder#withEmailValidator(EmailValidator)
	 */
	@Nullable
	private EmailValidator emailValidator;

	/**
	 * @see MailerGenericBuilder#withEmailDefaults(Email)
	 */
	@Nullable
	private Email emailDefaults;

	/**
	 * @see MailerGenericBuilder#withEmailOverrides(Email)
	 */
	@Nullable
	private Email emailOverrides;

	/**
	 * @see MailerGenericBuilder#withMaximumEmailSize(int)
	 */
	@Nullable
	private Integer maximumEmailSize;

	/**
	 * @see MailerGenericBuilder#withExecutorService(ExecutorService)
	 */
	@Nullable
	private ExecutorService executorService;

	/**
	 * @see MailerGenericBuilder#withThreadPoolSize(Integer)
	 */
	@NotNull
	private Integer threadPoolSize;

	/**
	 * @see MailerGenericBuilder#withThreadPoolKeepAliveTime(Integer)
	 */
	@NotNull
	private Integer threadPoolKeepAliveTime;

	/**
	 * @see MailerGenericBuilder#withAsyncQueueCapacity(int)
	 */
	@NotNull private AsyncQueueConfig asyncQueueConfig;

	/**
	 * @see MailerGenericBuilder#withClusterKey(UUID)
	 */
	@NotNull
	private UUID clusterKey;

	/**
	 * @see MailerGenericBuilder#withConnectionPoolCoreSize(Integer)
	 */
	@NotNull
	private Integer connectionPoolCoreSize;

	/**
	 * @see MailerGenericBuilder#withConnectionPoolMaxSize(Integer)
	 */
	@NotNull
	private Integer connectionPoolMaxSize;

	/**
	 * @see MailerGenericBuilder#withConnectionPoolClaimTimeoutMillis(Integer)
	 */
	@NotNull
	private Integer connectionPoolClaimTimeoutMillis;

	/**
	 * @see MailerGenericBuilder#withConnectionPoolExpireAfterMillis(Integer)
	 */
	@NotNull
	private Integer connectionPoolExpireAfterMillis;

	/**
	 * @see MailerGenericBuilder#withConnectionPoolExpireAfterCreationMillis(Integer)
	 */
	@Nullable
	private Integer connectionPoolExpireAfterCreationMillis;

	/**
	 * @see MailerGenericBuilder#withConnectionPoolLoadBalancingStrategy(LoadBalancingStrategy loadBalancingStrategy)
	 */
	@NotNull
	private LoadBalancingStrategy connectionPoolLoadBalancingStrategy;

	/**
	 * @see OperationalConfig#getConnectionPoolClusterConfigs()
	 */
	@NotNull
	private Map<UUID, ConnectionPoolClusterConfig> connectionPoolClusterConfigs;

	/**
	 * @see MailerGenericBuilder#trustingSSLHosts(String...)
	 */
	@NotNull
	private List<String> sslHostsToTrust = new ArrayList<>();

	/**
	 * @see MailerGenericBuilder#trustingAllHosts(boolean)
	 */
	private boolean trustAllSSLHost;

	/**
	 * @see MailerGenericBuilder#verifyingServerIdentity(boolean)
	 */
	private boolean verifyingServerIdentity;

	/**
	 * @see MailerGenericBuilder#withProperties(Properties)
	 */
	@NotNull
	private final Properties properties = new Properties();

	/**
	 * @see MailerGenericBuilder#withTransportModeLoggingOnly(Boolean)
	 */
	private boolean transportModeLoggingOnly;

	/**
	 * @see MailerGenericBuilder#withCustomMailer(CustomMailer)
	 */
	@Nullable
	private CustomMailer customMailer;

	/**
	 * @see MailerGenericBuilder#withOAuth2AccessTokenProvider(OAuth2AccessTokenProvider)
	 */
	@Nullable
	private OAuth2AccessTokenProvider oauth2AccessTokenProvider;

	/**
	 * @see MailerGenericBuilder#withMailSendObserver(MailSendObserver)
	 */
	@Nullable
	private MailSendObserver mailSendObserver;

	/**
	 * @see MailerGenericBuilder#withMailSendObserver(MailSendObserver, Executor)
	 */
	@Nullable private Executor mailSendObserverExecutor;

	/**
	 * @see MailerGenericBuilder#withMailSendTimeout(Duration)
	 */
	@Nullable private Duration mailSendTimeout;

	MailerGenericBuilderImpl(@NotNull final SimpleJavaMailConfig config, @NotNull final FactorySendingLimits sendingLimits) {
		this.config = requireNonNull(config, "config");
		this.sendingLimits = requireNonNull(sendingLimits, "sendingLimits");
		this.messageRateLimit = resolveRateLimit(config, Property.SMTP_MESSAGE_RATE_LIMIT, Property.SMTP_MESSAGE_RATE_PERIOD);
		this.recipientRateLimit = resolveRateLimit(config, Property.SMTP_RECIPIENT_RATE_LIMIT, Property.SMTP_RECIPIENT_RATE_PERIOD);
		this.rateLimitGroup = config.getStringProperty(Property.SMTP_RATE_LIMIT_GROUP);
		if (rateLimitGroup != null) {
			withRateLimitGroup(rateLimitGroup);
		}
		this.rateLimitBurstsAllowed = config.valueOrProperty(null, Property.SMTP_RATE_LIMIT_ALLOW_BURSTS, DEFAULT_RATE_LIMIT_BURSTS_ALLOWED);
		this.legacySmtpContentSupportEnabled = config.valueOrProperty(null, Property.SMTP_LEGACY_CONTENT_SUPPORT, DEFAULT_LEGACY_SMTP_CONTENT_SUPPORT);
		this.mailSendTimeout = config.valueOrProperty(null, Property.DEFAULT_MAIL_SEND_TIMEOUT, DEFAULT_MAIL_SEND_TIMEOUT);
		if (mailSendTimeout != null) {
			MailSendControl.positiveTimeoutNanos(mailSendTimeout);
		}
		this.asyncQueueConfig = new AsyncQueueConfig(
				config.valueOrProperty(null, Property.DEFAULT_ASYNC_QUEUE_CAPACITY, DEFAULT_ASYNC_QUEUE_CONFIG.getCapacity()),
				config.valueOrProperty(null, Property.DEFAULT_ASYNC_QUEUE_OVERFLOW_POLICY, DEFAULT_ASYNC_QUEUE_CONFIG.getOverflowPolicy()),
				config.valueOrProperty(null, Property.DEFAULT_ASYNC_QUEUE_WAIT_TIMEOUT_MILLIS, DEFAULT_ASYNC_QUEUE_CONFIG.getWaitTimeoutMillis()));
		final Map<String, String> extraProperties = config.getProperty(EXTRA_PROPERTIES);
		if (extraProperties != null) {
			this.properties.putAll(extraProperties);
		}
		this.proxyHost = config.getStringProperty(PROXY_HOST);
		this.proxyUsername = config.getStringProperty(PROXY_USERNAME);
		this.proxyPassword = config.getStringProperty(PROXY_PASSWORD);
		this.clusterKey = config.hasProperty(DEFAULT_CONNECTIONPOOL_CLUSTER_KEY)
				? UUID.fromString(verifyNonnullOrEmpty(config.getStringProperty(DEFAULT_CONNECTIONPOOL_CLUSTER_KEY)))
				: UUID.randomUUID();

		this.proxyPort =  							verifyNonnullOrEmpty(config.valueOrProperty(null, Property.PROXY_PORT, DEFAULT_PROXY_PORT));
		this.proxyBridgePort = 						verifyNonnullOrEmpty(config.valueOrProperty(null, Property.PROXY_SOCKS5BRIDGE_PORT, DEFAULT_PROXY_BRIDGE_PORT));
		this.disableAllClientValidation = 			verifyNonnullOrEmpty(config.valueOrProperty(null, Property.DISABLE_ALL_CLIENTVALIDATION, DEFAULT_DISABLE_ALL_CLIENTVALIDATION));
		this.debugLogging = 						verifyNonnullOrEmpty(config.valueOrProperty(null, Property.JAVAXMAIL_DEBUG, DEFAULT_JAVAXMAIL_DEBUG));
		this.debugOutput = config.getProperty(Property.JAVAXMAIL_DEBUG_OUTPUT);
		this.debugPrinter = resolveDebugPrinter(debugOutput);
		this.sessionTimeout = 						verifyNonnullOrEmpty(config.valueOrProperty(null, Property.DEFAULT_SESSION_TIMEOUT_MILLIS, DEFAULT_SESSION_TIMEOUT_MILLIS));
		this.localBindAddress = 					config.getStringProperty(Property.SMTP_LOCAL_ADDRESS);
		this.localBindPort = 						config.getIntegerProperty(Property.SMTP_LOCAL_PORT);
		this.smtpClientHostname = 					config.getStringProperty(Property.SMTP_CLIENT_HOSTNAME);
		this.trustAllSSLHost = 						verifyNonnullOrEmpty(config.valueOrProperty(null, Property.DEFAULT_TRUST_ALL_HOSTS, DEFAULT_TRUST_ALL_HOSTS));
		this.verifyingServerIdentity = 				verifyNonnullOrEmpty(config.valueOrProperty(null, Property.DEFAULT_VERIFY_SERVER_IDENTITY, DEFAULT_VERIFY_SERVER_IDENTITY));
		this.threadPoolSize = 						verifyNonnullOrEmpty(config.valueOrProperty(null, Property.DEFAULT_POOL_SIZE, DEFAULT_POOL_SIZE));
		this.threadPoolKeepAliveTime = 				verifyNonnullOrEmpty(config.valueOrProperty(null, Property.DEFAULT_POOL_KEEP_ALIVE_TIME, DEFAULT_POOL_KEEP_ALIVE_TIME));
		this.connectionPoolCoreSize = 				verifyNonnullOrEmpty(config.valueOrProperty(null, Property.DEFAULT_CONNECTIONPOOL_CORE_SIZE, DEFAULT_CONNECTIONPOOL_CORE_SIZE));
		this.connectionPoolMaxSize = 				verifyNonnullOrEmpty(config.valueOrProperty(null, Property.DEFAULT_CONNECTIONPOOL_MAX_SIZE, DEFAULT_CONNECTIONPOOL_MAX_SIZE));
		this.connectionPoolClaimTimeoutMillis = 	verifyNonnullOrEmpty(config.valueOrProperty(null, Property.DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT_MILLIS, DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT_MILLIS));
		this.connectionPoolExpireAfterMillis = 		verifyNonnullOrEmpty(config.valueOrProperty(null, Property.DEFAULT_CONNECTIONPOOL_EXPIREAFTER_MILLIS, DEFAULT_CONNECTIONPOOL_EXPIREAFTER_MILLIS));
		this.connectionPoolExpireAfterCreationMillis = validateConnectionPoolExpireAfterCreationMillis(
				config.getIntegerProperty(Property.DEFAULT_CONNECTIONPOOL_EXPIREAFTERCREATION_MILLIS));
		this.connectionPoolLoadBalancingStrategy = 	verifyNonnullOrEmpty(config.valueOrProperty(null, Property.DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY,
				LoadBalancingStrategy.valueOf(DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY)));
		final Map<UUID, ConnectionPoolClusterConfig> configuredClusters = config.getProperty(Property.DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS);
		this.connectionPoolClusterConfigs = 		configuredClusters == null
				? Collections.<UUID, ConnectionPoolClusterConfig>emptyMap()
				: new LinkedHashMap<>(configuredClusters);
		this.transportModeLoggingOnly = 			verifyNonnullOrEmpty(config.valueOrProperty(null, Property.TRANSPORT_MODE_LOGGING_ONLY, DEFAULT_TRANSPORT_MODE_LOGGING_ONLY));

		final String trustedHosts = config.getStringProperty(Property.DEFAULT_TRUSTED_HOSTS);
		if (trustedHosts != null) {
			this.sslHostsToTrust = new ArrayList<>(Arrays.asList(trustedHosts.split(";")));
		}
		this.emailValidator = JMail.strictValidator();
	}

	@Nullable
	private static SendingRateLimit resolveRateLimit(final SimpleJavaMailConfig config, final Property countProperty, final Property periodProperty) {
		final Integer count = config.getIntegerProperty(countProperty);
		final Duration period = config.getProperty(periodProperty);
		if (count == null && period == null) {
			return null;
		}
		if (count == null || period == null) {
			throw new IllegalArgumentException("A sending limit needs both " + countProperty.key() + " and " + periodProperty.key()
					+ ". Supply a positive count and an ISO-8601 period such as PT1M, or remove both to disable the rule.");
		}
		return new SendingRateLimit(count, period);
	}

	FactorySendingLimits getFactorySendingLimits() {
		return sendingLimits;
	}

	final SimpleJavaMailConfig configuration() {
		return config;
	}

	final void customizedSessionSetting(final Property property, @Nullable final Object value) {
		explicitSessionSettings.put(property, value);
	}

	abstract TransportStrategy transportStrategyForLocks();

	/** Check the finished builder before executor, proxy or pool initialization can make a rejected build observable. */
	void validateLockedConfiguration() {
		final ConfigurationLocks locks = config.getLocks();
		if (locks.isEmpty()) {
			return;
		}
		validateLockedSendingSettings(locks);
		validateLockedConnectionSettings(locks);
		validateLockedExecutionSettings(locks);
		validateLockedExtraProperties(locks);
		validateLockedExecutorSettings(locks);
		validateCustomMailerLocks(locks);
		LockedSmtpConfiguration.verifyBuilderCustomizations(locks, explicitSessionSettings, transportStrategyForLocks());
		validateLockedClusterDefaults(locks);
		if (oauth2AccessTokenProvider != null && locks.contains(Property.SMTP_PASSWORD)) {
			throw locks.conflict(Property.SMTP_PASSWORD, "withOAuth2AccessTokenProvider(...) supplies credentials at send time, "
					+ "but this factory locks the SMTP credential. "
					+ "Runtime tokens can replace that locked value, so these settings cannot be combined. Remove that builder call, "
					+ "or remove the SMTP credential lock from the configuration source if credentials should be supplied dynamically.");
		}
	}

	private void validateLockedSendingSettings(final ConfigurationLocks locks) {
		locks.verify(Property.SMTP_RATE_LIMIT_GROUP, rateLimitGroup);
		locks.verify(Property.SMTP_MESSAGE_RATE_LIMIT, messageRateLimit == null ? null : messageRateLimit.getCount());
		locks.verify(Property.SMTP_MESSAGE_RATE_PERIOD, messageRateLimit == null ? null : messageRateLimit.getPeriod());
		locks.verify(Property.SMTP_RECIPIENT_RATE_LIMIT, recipientRateLimit == null ? null : recipientRateLimit.getCount());
		locks.verify(Property.SMTP_RECIPIENT_RATE_PERIOD, recipientRateLimit == null ? null : recipientRateLimit.getPeriod());
		locks.verify(Property.SMTP_RATE_LIMIT_ALLOW_BURSTS, rateLimitBurstsAllowed);
		locks.verify(Property.SMTP_LEGACY_CONTENT_SUPPORT, legacySmtpContentSupportEnabled);
		locks.verify(Property.DEFAULT_MAIL_SEND_TIMEOUT, mailSendTimeout);
		locks.verify(Property.TRANSPORT_MODE_LOGGING_ONLY, transportModeLoggingOnly);
	}

	private void validateLockedExecutionSettings(final ConfigurationLocks locks) {
		locks.verify(Property.DEFAULT_ASYNC_QUEUE_CAPACITY, asyncQueueConfig.getCapacity());
		locks.verify(Property.DEFAULT_ASYNC_QUEUE_OVERFLOW_POLICY, asyncQueueConfig.getOverflowPolicy());
		locks.verify(Property.DEFAULT_ASYNC_QUEUE_WAIT_TIMEOUT_MILLIS, asyncQueueConfig.getWaitTimeoutMillis());
		locks.verify(Property.DEFAULT_POOL_SIZE, threadPoolSize);
		locks.verify(Property.DEFAULT_POOL_KEEP_ALIVE_TIME, threadPoolKeepAliveTime);
		if (locks.contains(Property.DEFAULT_CONNECTIONPOOL_CLUSTER_KEY)
				&& !clusterKey.equals(UUID.fromString(config.getStringProperty(Property.DEFAULT_CONNECTIONPOOL_CLUSTER_KEY)))) {
			throw locks.conflict(Property.DEFAULT_CONNECTIONPOOL_CLUSTER_KEY,
					"withClusterKey(...) selects a different pool cluster from the one locked by this factory. "
					+ "Remove that builder call, use the locked cluster identifier, or change this lock in its configuration source.");
		}
		locks.verify(Property.DEFAULT_CONNECTIONPOOL_CORE_SIZE, connectionPoolCoreSize);
		locks.verify(Property.DEFAULT_CONNECTIONPOOL_MAX_SIZE, connectionPoolMaxSize);
		locks.verify(Property.DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT_MILLIS, connectionPoolClaimTimeoutMillis);
		locks.verify(Property.DEFAULT_CONNECTIONPOOL_EXPIREAFTER_MILLIS, connectionPoolExpireAfterMillis);
		locks.verify(Property.DEFAULT_CONNECTIONPOOL_EXPIREAFTERCREATION_MILLIS, connectionPoolExpireAfterCreationMillis);
		locks.verify(Property.DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY, connectionPoolLoadBalancingStrategy);
	}

	private void validateLockedConnectionSettings(final ConfigurationLocks locks) {
		locks.verify(Property.PROXY_HOST, proxyHost);
		locks.verify(Property.PROXY_PORT, proxyPort);
		locks.verify(Property.PROXY_USERNAME, proxyUsername);
		locks.verify(Property.PROXY_PASSWORD, proxyPassword);
		locks.verify(Property.PROXY_SOCKS5BRIDGE_PORT, proxyBridgePort);
		locks.verify(Property.JAVAXMAIL_DEBUG, debugLogging);
		locks.verify(Property.JAVAXMAIL_DEBUG_OUTPUT, debugOutput);
		locks.verify(Property.DISABLE_ALL_CLIENTVALIDATION, disableAllClientValidation);
		locks.verify(Property.DEFAULT_SESSION_TIMEOUT_MILLIS, sessionTimeout);
		locks.verify(Property.SMTP_LOCAL_ADDRESS, localBindAddress);
		locks.verify(Property.SMTP_LOCAL_PORT, localBindPort);
		locks.verify(Property.SMTP_CLIENT_HOSTNAME, smtpClientHostname);
		locks.verify(Property.DEFAULT_TRUST_ALL_HOSTS, trustAllSSLHost);
		locks.verify(Property.DEFAULT_VERIFY_SERVER_IDENTITY, verifyingServerIdentity);
		if (locks.contains(Property.DEFAULT_TRUSTED_HOSTS)
				&& !Arrays.asList(config.getStringProperty(Property.DEFAULT_TRUSTED_HOSTS).split(";")).equals(sslHostsToTrust)) {
			throw locks.conflict(Property.DEFAULT_TRUSTED_HOSTS, "trustingSSLHosts(...) supplies a different trusted-host list from the one locked by this factory. "
					+ "Remove that builder call, use the locked list, or change this lock in its configuration source.");
		}
	}

	private void validateLockedExtraProperties(final ConfigurationLocks locks) {
		for (final String key : locks.getValues().keySet()) {
			if (key.startsWith("simplejavamail.extraproperties.")) {
				locks.verify(key, properties.get(key.substring("simplejavamail.extraproperties.".length())));
			}
		}
	}

	private void validateLockedClusterDefaults(final ConfigurationLocks locks) {
		for (ConnectionPoolClusterConfig cluster : connectionPoolClusterConfigs.values()) {
			verifyClusterOverride(locks, Property.DEFAULT_CONNECTIONPOOL_CORE_SIZE, cluster.getCoreSize());
			verifyClusterOverride(locks, Property.DEFAULT_CONNECTIONPOOL_MAX_SIZE, cluster.getMaxSize());
			verifyClusterOverride(locks, Property.DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT_MILLIS, cluster.getClaimTimeoutMillis());
			verifyClusterOverride(locks, Property.DEFAULT_CONNECTIONPOOL_EXPIREAFTER_MILLIS, cluster.getExpireAfterMillis());
			verifyClusterOverride(locks, Property.DEFAULT_CONNECTIONPOOL_EXPIREAFTERCREATION_MILLIS, cluster.getExpireAfterCreationMillis());
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

	private void validateCustomMailerLocks(final ConfigurationLocks locks) {
		if (customMailer == null) {
			return;
		}
		for (String key : locks.getValues().keySet()) {
			final boolean smtpSetting = key.startsWith("simplejavamail.smtp.") && !key.startsWith("simplejavamail.smtp.ratelimit.");
			final boolean envelopeSetting = key.equals(Property.DEFAULT_REQUIRE_TLS.key()) || key.equals(Property.DEFAULT_SEND_TO_ACCEPTED_RECIPIENTS.key())
					|| key.equals(Property.DEFAULT_BOUNCETO_ADDRESS.key())
					|| key.equals(Property.DEFAULT_TO_ADDRESS.key()) || key.equals(Property.DEFAULT_CC_ADDRESS.key())
					|| key.equals(Property.DEFAULT_BCC_ADDRESS.key()) || key.equals(Property.DEFAULT_DELIVERY_STATUS_NOTIFICATION_NOTIFY.key())
					|| key.equals(Property.DEFAULT_DELIVERY_STATUS_NOTIFICATION_RETURN_OPTION.key());
			if (smtpSetting || envelopeSetting || key.startsWith("simplejavamail.proxy.") || key.startsWith("simplejavamail.extraproperties.")
					|| key.equals(Property.TRANSPORT_STRATEGY.key()) || key.equals(Property.CUSTOM_SSLFACTORY_CLASS.key())
					|| key.equals(Property.DEFAULT_TRUST_ALL_HOSTS.key()) || key.equals(Property.DEFAULT_TRUSTED_HOSTS.key())
					|| key.equals(Property.DEFAULT_VERIFY_SERVER_IDENTITY.key()) || key.equals(Property.OPPORTUNISTIC_TLS.key())
					|| key.equals(Property.DEFAULT_SESSION_TIMEOUT_MILLIS.key())) {
				throw locks.conflict(key, "withCustomMailer(...) hands transport and delivery envelope handling to your callback, "
						+ "but this factory locks a setting that must be applied there. Simple Java Mail cannot check what the callback does with it. "
						+ "Remove withCustomMailer(...) to use this factory's SMTP transport, "
						+ "or remove the corresponding locks from the configuration used for that callback.");
			}
		}
	}

	private void validateLockedExecutorSettings(final ConfigurationLocks locks) {
		if (executorService != null) {
			for (final Property property : new Property[]{Property.DEFAULT_POOL_SIZE, Property.DEFAULT_POOL_KEEP_ALIVE_TIME,
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

	/** @see MailerGenericBuilder#withMessageRateLimit(int, Duration) */
	@Override
	public T withMessageRateLimit(final int count, @NotNull final Duration period) {
		messageRateLimit = new SendingRateLimit(count, period);
		return (T) this;
	}

	/** @see MailerGenericBuilder#withRecipientRateLimit(int, Duration) */
	@Override
	public T withRecipientRateLimit(final int count, @NotNull final Duration period) {
		recipientRateLimit = new SendingRateLimit(count, period);
		return (T) this;
	}

	/** @see MailerGenericBuilder#withRateLimitGroup(String) */
	@Override
	public T withRateLimitGroup(@NotNull final String group) {
		if (requireNonNull(group, "group").isBlank()) {
			throw new IllegalArgumentException("A rate-limit group needs a nonblank name; call resetRateLimitGroup() for private allowance.");
		}
		rateLimitGroup = group;
		return (T) this;
	}

	/** @see MailerGenericBuilder#withRateLimitBurstsAllowed(boolean) */
	@Override
	public T withRateLimitBurstsAllowed(final boolean allowed) {
		rateLimitBurstsAllowed = allowed;
		return (T) this;
	}

	/** @see MailerGenericBuilder#resetMessageRateLimit() */
	@Override
	public T resetMessageRateLimit() {
		messageRateLimit = null;
		return (T) this;
	}

	/** @see MailerGenericBuilder#resetRecipientRateLimit() */
	@Override
	public T resetRecipientRateLimit() {
		recipientRateLimit = null;
		return (T) this;
	}

	/** @see MailerGenericBuilder#resetRateLimitGroup() */
	@Override
	public T resetRateLimitGroup() {
		rateLimitGroup = null;
		return (T) this;
	}

	/** @see MailerGenericBuilder#resetRateLimitBurstsAllowed() */
	@Override
	public T resetRateLimitBurstsAllowed() {
		return withRateLimitBurstsAllowed(DEFAULT_RATE_LIMIT_BURSTS_ALLOWED);
	}

	/** @see MailerGenericBuilder#getMessageRateLimit() */
	@Override
	@Nullable
	public SendingRateLimit getMessageRateLimit() {
		return messageRateLimit;
	}

	/** @see MailerGenericBuilder#getRecipientRateLimit() */
	@Override
	@Nullable
	public SendingRateLimit getRecipientRateLimit() {
		return recipientRateLimit;
	}

	/** @see MailerGenericBuilder#getRateLimitGroup() */
	@Override
	@Nullable
	public String getRateLimitGroup() {
		return rateLimitGroup;
	}

	/** @see MailerGenericBuilder#isRateLimitBurstsAllowed() */
	@Override
	public boolean isRateLimitBurstsAllowed() {
		return rateLimitBurstsAllowed;
	}

	/**
	 * For internal use.
	 */
	ProxyConfig buildProxyConfig() {
		validateProxy();
		return new ProxyConfigImpl(getProxyHost(), getProxyPort(), getProxyUsername(), getProxyPassword(), getProxyBridgePort());
	}

	private void validateProxy() {
		if (!valueNullOrEmpty(proxyHost)) {
			checkArgumentNotEmpty(proxyPort, "proxyHost provided, but not a proxyPort");

			if (!valueNullOrEmpty(proxyUsername) && valueNullOrEmpty(proxyPassword)) {
				throw new IllegalArgumentException("Proxy username provided but not a password");
			}
			if (valueNullOrEmpty(proxyUsername) && !valueNullOrEmpty(proxyPassword)) {
				throw new IllegalArgumentException("Proxy password provided but not a username");
			}
			if (!valueNullOrEmpty(proxyUsername) && valueNullOrEmpty(proxyBridgePort)) {
				throw new IllegalArgumentException("Cannot authenticate with proxy if no proxy bridge port is configured");
			}
		}
	}

	/**
	 * For internal use.
	 */
	EmailGovernance buildEmailGovernance() {
		return new EmailGovernanceImpl(
				config,
				new EmailStartingBuilderImpl(config),
				getEmailValidator(),
				getEmailDefaults(),
				getEmailOverrides(),
				getMaximumEmailSize());
	}

	/**
	 * For internal use.
	 */
	OperationalConfig buildOperationalConfig() {
		if (executorService != null && !asyncQueueConfig.equals(DEFAULT_ASYNC_QUEUE_CONFIG)) {
			throw new IllegalArgumentException("Configure queue capacity and overflow on the caller-owned executor; built-in async queue settings cannot be combined with withExecutorService");
		}
		return new OperationalConfigImpl(
				getProperties(),
				getSessionTimeout(),
				getLocalBindAddress(),
				getLocalBindPort(),
				getSmtpClientHostname(),
				isLegacySmtpContentSupportEnabled(),
				getThreadPoolSize(),
				getThreadPoolKeepAliveTime(),
				getClusterKey(),
				getConnectionPoolCoreSize(),
				getConnectionPoolMaxSize(),
				getConnectionPoolClaimTimeoutMillis(),
				getConnectionPoolExpireAfterMillis(),
				getConnectionPoolExpireAfterCreationMillis(),
				getConnectionPoolLoadBalancingStrategy(),
				Collections.unmodifiableMap(new LinkedHashMap<>(connectionPoolClusterConfigs)),
				isTransportModeLoggingOnly(),
				isDebugLogging(),
				getDebugPrinter(),
				isDisableAllClientValidation(),
				getSslHostsToTrust(),
				isTrustAllSSLHost(),
				isVerifyingServerIdentity(),
				getExecutorService() != null ? getExecutorService() : determineDefaultExecutorService(),
				isExecutorServiceUserProvided(),
				getCustomMailer(),
				getOAuth2AccessTokenProvider(),
				getAsyncQueueConfig(), getMailSendTimeout(), messageRateLimit, recipientRateLimit, rateLimitGroup, rateLimitBurstsAllowed);
	}

	/**
	 * @see MailerGenericBuilder#withProxy(String, Integer)
	 */
	@Override
	public T withProxy(@Nullable final String proxyHost, @Nullable final Integer proxyPort) {
		return (T) withProxyHost(proxyHost)
				.withProxyPort(proxyPort);
	}

	/**
	 * @see MailerGenericBuilder#withProxy(String, Integer, String, String)
	 */
	@Override
	public T withProxy(@Nullable final String proxyHost, @Nullable final Integer proxyPort, @Nullable final String proxyUsername, @Nullable final String proxyPassword) {
		return (T) withProxyHost(proxyHost)
				.withProxyPort(proxyPort)
				.withProxyUsername(proxyUsername)
				.withProxyPassword(proxyPassword);
	}

	/**
	 * @see MailerGenericBuilder#withProxyHost(String)
	 */
	@Override
	public T withProxyHost(@Nullable final String proxyHost) {
		this.proxyHost = proxyHost;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withProxyPort(Integer)
	 */
	@Override
	public T withProxyPort(@Nullable final Integer proxyPort) {
		this.proxyPort = proxyPort;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withProxyUsername(String)
	 */
	@Override
	public T withProxyUsername(@Nullable final String proxyUsername) {
		this.proxyUsername = proxyUsername;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withProxyPassword(String)
	 */
	@Override
	public T withProxyPassword(@Nullable final String proxyPassword) {
		this.proxyPassword = proxyPassword;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withProxyBridgePort(Integer)
	 */
	@Override
	public T withProxyBridgePort(@NotNull final Integer proxyBridgePort) {
		this.proxyBridgePort = proxyBridgePort;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withDebugLogging(Boolean)
	 */
	@Override
	public T withDebugLogging(@NotNull final Boolean debugLogging) {
		this.debugLogging = debugLogging;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withDebugPrinter(PrintStream)
	 */
	@Override
	public T withDebugPrinter(@NotNull final PrintStream debugPrinter) {
		if (this.debugPrinter != debugPrinter) {
			this.debugOutput = debugPrinter == System.out ? SessionDebugOutput.STDOUT : debugPrinter == System.err ? SessionDebugOutput.STDERR : null;
		}
		this.debugPrinter = debugPrinter;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withDebugOutput(SessionDebugOutput)
	 */
	@Override
	public T withDebugOutput(@NotNull final SessionDebugOutput debugOutput) {
		withDebugPrinter(SessionDebugOutputResolver.resolve(debugOutput));
		this.debugOutput = debugOutput;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#disablingAllClientValidation(Boolean)
	 */
	@Override
	public T disablingAllClientValidation(@NotNull final Boolean disableAllClientValidation) {
		this.disableAllClientValidation = disableAllClientValidation;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withSessionTimeout(Integer)
	 */
	@Override
	public T withSessionTimeout(@NotNull final Integer sessionTimeout) {
		this.sessionTimeout = sessionTimeout;
		customizedSessionSetting(Property.DEFAULT_SESSION_TIMEOUT_MILLIS, sessionTimeout);
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withLocalBindAddress(String)
	 */
	@Override
	public T withLocalBindAddress(@Nullable final String localBindAddress) {
		this.localBindAddress = localBindAddress;
		customizedSessionSetting(Property.SMTP_LOCAL_ADDRESS, localBindAddress);
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withLocalBindAddress(String, Integer)
	 */
	@Override
	public T withLocalBindAddress(@Nullable final String localBindAddress, @Nullable final Integer localBindPort) {
		this.localBindAddress = localBindAddress;
		this.localBindPort = localBindPort;
		customizedSessionSetting(Property.SMTP_LOCAL_ADDRESS, localBindAddress);
		customizedSessionSetting(Property.SMTP_LOCAL_PORT, localBindPort);
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withSmtpClientHostname(String)
	 */
	@Override
	public T withSmtpClientHostname(@Nullable final String smtpClientHostname) {
		this.smtpClientHostname = smtpClientHostname;
		customizedSessionSetting(Property.SMTP_CLIENT_HOSTNAME, smtpClientHostname);
		return (T) this;
	}

	/** @see MailerGenericBuilder#withLegacySmtpContentSupport(boolean) */
	@Override
	public T withLegacySmtpContentSupport(final boolean enabled) {
		this.legacySmtpContentSupportEnabled = enabled;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withEmailValidator(EmailValidator)
	 */
	@Override
	public T withEmailValidator(@NotNull final EmailValidator emailEmailValidator) {
		this.emailValidator = emailEmailValidator;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withEmailDefaults(Email)
	 */
	@Override
	public T withEmailDefaults(@NotNull Email emailDefaults) {
		this.emailDefaults = emailDefaults;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withEmailOverrides(Email)
	 */
	@Override
	public T withEmailOverrides(@NotNull Email emailOverrides) {
		this.emailOverrides = emailOverrides;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withMaximumEmailSize(int)
	 */
	@Override
	public T withMaximumEmailSize(int maximumEmailSize) {
		this.maximumEmailSize = maximumEmailSize;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withExecutorService(ExecutorService)
	 */
	@Override
	public T withExecutorService(@NotNull final ExecutorService executorService) {
		this.executorService = executorService;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withThreadPoolSize(Integer)
	 */
	@Override
	public T withThreadPoolSize(@NotNull final Integer threadPoolSize) {
		this.threadPoolSize = threadPoolSize;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withThreadPoolKeepAliveTime(Integer)
	 */
	@Override
	public T withThreadPoolKeepAliveTime(@NotNull final Integer threadPoolKeepAliveTime) {
		this.threadPoolKeepAliveTime = threadPoolKeepAliveTime;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withClusterKey(UUID)
	 */
	@Override
	public T withClusterKey(@NotNull final UUID clusterKey) {
		this.clusterKey = clusterKey;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withConnectionPoolCoreSize(Integer)
	 */
	@Override
	public T withConnectionPoolCoreSize(@NotNull final Integer connectionPoolCoreSize) {
		this.connectionPoolCoreSize = connectionPoolCoreSize;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withConnectionPoolMaxSize(Integer)
	 */
	@Override
	public T withConnectionPoolMaxSize(@NotNull final Integer connectionPoolMaxSize) {
		this.connectionPoolMaxSize = connectionPoolMaxSize;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withConnectionPoolClaimTimeoutMillis(Integer)
	 */
	@Override
	public T withConnectionPoolClaimTimeoutMillis(@NotNull final Integer connectionPoolClaimTimeoutMillis) {
		this.connectionPoolClaimTimeoutMillis = connectionPoolClaimTimeoutMillis;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withConnectionPoolExpireAfterMillis(Integer)
	 */
	@Override
	public T withConnectionPoolExpireAfterMillis(@NotNull final Integer connectionPoolExpireAfterMillis) {
		this.connectionPoolExpireAfterMillis = connectionPoolExpireAfterMillis;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withConnectionPoolExpireAfterCreationMillis(Integer)
	 */
	@Override
	public T withConnectionPoolExpireAfterCreationMillis(@NotNull final Integer connectionPoolExpireAfterCreationMillis) {
		this.connectionPoolExpireAfterCreationMillis = validateConnectionPoolExpireAfterCreationMillis(connectionPoolExpireAfterCreationMillis);
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withConnectionPoolLoadBalancingStrategy(LoadBalancingStrategy)
	 */
	@Override
	public T withConnectionPoolLoadBalancingStrategy(@NotNull final LoadBalancingStrategy loadBalancingStrategy) {
		this.connectionPoolLoadBalancingStrategy = loadBalancingStrategy;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withTransportModeLoggingOnly(Boolean)
	 */
	@Override
	public T withTransportModeLoggingOnly(@NotNull final Boolean transportModeLoggingOnly) {
		this.transportModeLoggingOnly = transportModeLoggingOnly;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#trustingSSLHosts(String...)
	 */
	@Override
	public T trustingSSLHosts(String... sslHostsToTrust) {
		this.sslHostsToTrust = Arrays.asList(sslHostsToTrust);
		customizedSessionSetting(Property.DEFAULT_TRUSTED_HOSTS, String.join(" ", sslHostsToTrust));
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#trustingAllHosts(boolean)
	 */
	@Override
	public T trustingAllHosts(final boolean trustAllHosts) {
		this.trustAllSSLHost = trustAllHosts;
		customizedSessionSetting(Property.DEFAULT_TRUST_ALL_HOSTS, trustAllHosts);
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#verifyingServerIdentity(boolean)
	 */
	@Override
	public T verifyingServerIdentity(final boolean verifyingServerIdentity) {
		this.verifyingServerIdentity = verifyingServerIdentity;
		customizedSessionSetting(Property.DEFAULT_VERIFY_SERVER_IDENTITY, verifyingServerIdentity);
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withProperties(Properties)
	 */
	@Override
	public T withProperties(@NotNull final Properties properties) {
		for (Map.Entry<Object, Object> property : properties.entrySet()) {
			this.properties.put(property.getKey(), property.getValue());
		}
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withProperties(Map)
	 */
	@Override
	public T withProperties(@NotNull final Map<String, String> properties) {
		for (Map.Entry<String, String> property : properties.entrySet()) {
			this.properties.put(property.getKey(), property.getValue());
		}
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withProperty(String, Object)
	 */
	@Override
	public T withProperty(@NotNull final String propertyName, @Nullable final Object propertyValue) {
		if (propertyValue == null) {
			this.properties.remove(propertyName);
		} else {
			this.properties.put(propertyName, propertyValue.toString());
		}
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withCustomMailer(CustomMailer)
	 */
	@Override
	public T withCustomMailer(@NotNull CustomMailer customMailer) {
		this.customMailer = customMailer;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withOAuth2AccessTokenProvider(OAuth2AccessTokenProvider)
	 */
	@Override
	public T withOAuth2AccessTokenProvider(@NotNull OAuth2AccessTokenProvider accessTokenProvider) {
		this.oauth2AccessTokenProvider = accessTokenProvider;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withMailSendObserver(MailSendObserver)
	 */
	@Override
	public T withMailSendObserver(@NotNull final MailSendObserver mailSendObserver) {
		this.mailSendObserver = requireNonNull(mailSendObserver, "mailSendObserver");
		this.mailSendObserverExecutor = null;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withMailSendObserver(MailSendObserver, Executor)
	 */
	@Override
	public T withMailSendObserver(@NotNull final MailSendObserver mailSendObserver, @NotNull final Executor observerExecutor) {
		requireNonNull(mailSendObserver, "mailSendObserver");
		requireNonNull(observerExecutor, "observerExecutor");
		this.mailSendObserver = mailSendObserver;
		this.mailSendObserverExecutor = observerExecutor;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withMailSendTimeout(Duration)
	 */
	@Override
	public T withMailSendTimeout(@NotNull final Duration timeout) {
		MailSendControl.positiveTimeoutNanos(timeout);
		mailSendTimeout = timeout;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#resetMailSendTimeout()
	 */
	@Override
	public T resetMailSendTimeout() {
		mailSendTimeout = DEFAULT_MAIL_SEND_TIMEOUT;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#getMailSendTimeout()
	 */
	@Override
	@Nullable
	public Duration getMailSendTimeout() {
		return mailSendTimeout;
	}

	/**
	 * @see MailerGenericBuilder#resetDisableAllClientValidations()
	 */
	@Override
	public T resetDisableAllClientValidations() {
		return disablingAllClientValidation(DEFAULT_DISABLE_ALL_CLIENTVALIDATION);
	}

	/**
	 * @see MailerGenericBuilder#resetSessionTimeout()
	 */
	@Override
	public T resetSessionTimeout() {
		return withSessionTimeout(DEFAULT_SESSION_TIMEOUT_MILLIS);
	}

	/**
	 * @see MailerGenericBuilder#resetTrustingAllHosts()
	 */
	@Override
	public T resetTrustingAllHosts() {
		return trustingAllHosts(DEFAULT_TRUST_ALL_HOSTS);
	}

	/**
	 * @see MailerGenericBuilder#resetVerifyingServerIdentity()
	 */
	@Override
	public T resetVerifyingServerIdentity() {
		return verifyingServerIdentity(DEFAULT_VERIFY_SERVER_IDENTITY);
	}

	/**
	 * @see MailerGenericBuilder#resetEmailValidator()
	 */
	@Override
	public T resetEmailValidator() {
		return withEmailValidator(JMail.strictValidator());
	}

	/**
	 * @see MailerGenericBuilder#resetExecutorService()
	 */
	@Override
	public T resetExecutorService() {
		this.executorService = null;
		return (T) this;
	}

	@NotNull
	private ExecutorService determineDefaultExecutorService() {
		final boolean batchAvailable = ModuleLoader.batchModuleAvailable();
		return new MailSendExecutor(batchAvailable ? getThreadPoolSize() : 1,
				batchAvailable ? getThreadPoolKeepAliveTime() : 0, asyncQueueConfig);
	}

	/**
	 * @see MailerGenericBuilder#withAsyncQueueCapacity(int)
	 */
	@Override
	public T withAsyncQueueCapacity(final int capacity) {
		asyncQueueConfig = new AsyncQueueConfig(capacity, asyncQueueConfig.getOverflowPolicy(), asyncQueueConfig.getWaitTimeoutMillis());
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withAsyncQueueOverflowPolicy(AsyncQueueOverflowPolicy)
	 */
	@Override
	public T withAsyncQueueOverflowPolicy(@NotNull final AsyncQueueOverflowPolicy overflowPolicy) {
		asyncQueueConfig = new AsyncQueueConfig(asyncQueueConfig.getCapacity(), overflowPolicy, asyncQueueConfig.getWaitTimeoutMillis());
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withAsyncQueueWaitTimeoutMillis(int)
	 */
	@Override
	public T withAsyncQueueWaitTimeoutMillis(final int waitTimeoutMillis) {
		asyncQueueConfig = new AsyncQueueConfig(asyncQueueConfig.getCapacity(), asyncQueueConfig.getOverflowPolicy(), waitTimeoutMillis);
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#resetAsyncQueue()
	 */
	@Override
	public T resetAsyncQueue() {
		asyncQueueConfig = DEFAULT_ASYNC_QUEUE_CONFIG;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#getAsyncQueueConfig()
	 */
	@Override
	@NotNull
	public AsyncQueueConfig getAsyncQueueConfig() {
		return asyncQueueConfig;
	}

	@Nullable
	private PrintStream resolveDebugPrinter(@Nullable final SessionDebugOutput debugOutput) {
		return debugOutput != null ? SessionDebugOutputResolver.resolve(debugOutput) : null;
	}

	/**
	 * @see MailerGenericBuilder#resetThreadPoolSize()
	 */
	@Override
	public T resetThreadPoolSize() {
		return this.withThreadPoolSize(DEFAULT_POOL_SIZE);
	}

	/**
	 * @see MailerGenericBuilder#resetThreadPoolKeepAliveTime()
	 */
	@Override
	public T resetThreadPoolKeepAliveTime() {
		return withThreadPoolKeepAliveTime(DEFAULT_POOL_KEEP_ALIVE_TIME);
	}

	/**
	 * @see MailerGenericBuilder#resetClusterKey()
	 */
	@Override
	public T resetClusterKey() {
		return this.withClusterKey(UUID.randomUUID());
	}

	/**
	 * @see MailerGenericBuilder#resetConnectionPoolCoreSize()
	 */
	@Override
	public T resetConnectionPoolCoreSize() {
		return this.withConnectionPoolCoreSize(DEFAULT_CONNECTIONPOOL_CORE_SIZE);
	}

	/**
	 * @see MailerGenericBuilder#resetConnectionPoolMaxSize()
	 */
	@Override
	public T resetConnectionPoolMaxSize() {
		return this.withConnectionPoolMaxSize(DEFAULT_CONNECTIONPOOL_MAX_SIZE);
	}

	/**
	 * @see MailerGenericBuilder#resetConnectionPoolClaimTimeoutMillis()
	 */
	@Override
	public T resetConnectionPoolClaimTimeoutMillis() {
		return this.withConnectionPoolClaimTimeoutMillis(DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT_MILLIS);
	}

	/**
	 * @see MailerGenericBuilder#resetConnectionPoolExpireAfterMillis()
	 */
	@Override
	public T resetConnectionPoolExpireAfterMillis() {
		return this.withConnectionPoolExpireAfterMillis(DEFAULT_CONNECTIONPOOL_EXPIREAFTER_MILLIS);
	}

	/**
	 * @see MailerGenericBuilder#clearConnectionPoolExpireAfterCreationMillis()
	 */
	@Override
	public T clearConnectionPoolExpireAfterCreationMillis() {
		this.connectionPoolExpireAfterCreationMillis = null;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#resetConnectionPoolLoadBalancingStrategy()
	 */
	@Override
	public T resetConnectionPoolLoadBalancingStrategy() {
		return this.withConnectionPoolLoadBalancingStrategy(LoadBalancingStrategy.valueOf(DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY));
	}

	/**
	 * @see MailerGenericBuilder#resetTransportModeLoggingOnly()
	 */
	@Override
	public T resetTransportModeLoggingOnly() {
		return withTransportModeLoggingOnly(DEFAULT_TRANSPORT_MODE_LOGGING_ONLY);
	}

	/**
	 * @see MailerGenericBuilder#clearProxy()
	 */
	@Override
	public T clearProxy() {
		return (T) withProxy(null, null, null, null)
				.withProxyBridgePort(DEFAULT_PROXY_BRIDGE_PORT);
	}

	/**
	 * @see MailerGenericBuilder#clearEmailValidator()
	 */
	@Override
	public T clearEmailValidator() {
		this.emailValidator = null;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#clearEmailDefaults()
	 */
	@Override
	public T clearEmailDefaults() {
		this.emailDefaults = null;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#clearEmailOverrides()
	 */
	@Override
	public T clearEmailOverrides() {
		this.emailOverrides = null;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#clearMaximumEmailSize()
	 */
	@Override
	public T clearMaximumEmailSize() {
		this.maximumEmailSize = null;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#clearTrustedSSLHosts()
	 */
	@Override
	public T clearTrustedSSLHosts() {
		return trustingSSLHosts();
	}

	/**
	 * @see MailerGenericBuilder#clearLocalBindAddress()
	 */
	@Override
	public T clearLocalBindAddress() {
		return withLocalBindAddress(null, null);
	}

	/**
	 * @see MailerGenericBuilder#clearSmtpClientHostname()
	 */
	@Override
	public T clearSmtpClientHostname() {
		return withSmtpClientHostname(null);
	}

	/**
	 * @see MailerGenericBuilder#clearProperties()
	 */
	@Override
	public T clearProperties() {
		properties.clear();
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#getProxyHost()
	 */
	@Override
	@Nullable
	public String getProxyHost() {
		return proxyHost;
	}

	/**
	 * @see MailerGenericBuilder#getProxyPort()
	 */
	@Override
	@Nullable
	public Integer getProxyPort() {
		return proxyPort;
	}

	/**
	 * @see MailerGenericBuilder#getProxyUsername()
	 */
	@Override
	@Nullable
	public String getProxyUsername() {
		return proxyUsername;
	}

	/**
	 * @see MailerGenericBuilder#getProxyPassword()
	 */
	@Override
	@Nullable
	public String getProxyPassword() {
		return proxyPassword;
	}

	/**
	 * @see MailerGenericBuilder#getProxyBridgePort()
	 */
	@Override
	@NotNull
	public Integer getProxyBridgePort() {
		return proxyBridgePort;
	}

	/**
	 * @see MailerGenericBuilder#isDebugLogging()
	 */
	@Override
	public boolean isDebugLogging() {
		return debugLogging;
	}

	/**
	 * @see MailerGenericBuilder#getDebugPrinter()
	 */
	@Override
	@Nullable
	public PrintStream getDebugPrinter() {
		return debugPrinter;
	}

	/**
	 * @see MailerGenericBuilder#isDisableAllClientValidation()
	 */
	@Override
	public boolean isDisableAllClientValidation() {
		return disableAllClientValidation;
	}

	/**
	 * @see MailerGenericBuilder#getSessionTimeout()
	 */
	@Override
	@NotNull
	public Integer getSessionTimeout() {
		return sessionTimeout;
	}

	/**
	 * @see MailerGenericBuilder#getLocalBindAddress()
	 */
	@Override
	@Nullable
	public String getLocalBindAddress() {
		return localBindAddress;
	}

	/**
	 * @see MailerGenericBuilder#getLocalBindPort()
	 */
	@Override
	@Nullable
	public Integer getLocalBindPort() {
		return localBindPort;
	}

	/**
	 * @see MailerGenericBuilder#getSmtpClientHostname()
	 */
	@Override
	@Nullable
	public String getSmtpClientHostname() {
		return smtpClientHostname;
	}

	/** @see MailerGenericBuilder#isLegacySmtpContentSupportEnabled() */
	@Override
	public boolean isLegacySmtpContentSupportEnabled() {
		return legacySmtpContentSupportEnabled;
	}

	/**
	 * @see MailerGenericBuilder#getEmailValidator()
	 */
	@Override
	@Nullable
	public EmailValidator getEmailValidator() {
		return emailValidator;
	}

	/**
	 * @see MailerGenericBuilder#getEmailDefaults()
	 */
	@Override
	@Nullable
	public Email getEmailDefaults() {
		return emailDefaults;
	}

	/**
	 * @see MailerGenericBuilder#getEmailOverrides()
	 */
	@Override
	@Nullable
	public Email getEmailOverrides() {
		return emailOverrides;
	}

	/**
	 * @see MailerGenericBuilder#getMaximumEmailSize()
	 */
	@Override
	@Nullable
	public Integer getMaximumEmailSize() {
		return maximumEmailSize;
	}

	/**
	 * @see MailerGenericBuilder#getExecutorService()
	 */
	@Override
	@Nullable
	public ExecutorService getExecutorService() {
		return executorService;
	}

	/**
	 * @see InternalMailerBuilder#isExecutorServiceUserProvided()
	 */
	@Override
	public boolean isExecutorServiceUserProvided() {
		return executorService != null;
	}

	/**
	 * @see MailerGenericBuilder#getThreadPoolSize()
	 */
	@Override
	@NotNull
	public Integer getThreadPoolSize() {
		return threadPoolSize;
	}

	/**
	 * @see MailerGenericBuilder#getThreadPoolKeepAliveTime()
	 */
	@Override
	@NotNull
	public Integer getThreadPoolKeepAliveTime() {
		return threadPoolKeepAliveTime;
	}

	/**
	 * @see MailerGenericBuilder#getClusterKey()
	 */
	@Override
	@NotNull
	public UUID getClusterKey() {
		return clusterKey;
	}

	/**
	 * @see MailerGenericBuilder#getConnectionPoolCoreSize()
	 */
	@Override
	@NotNull
	public Integer getConnectionPoolCoreSize() {
		return connectionPoolCoreSize;
	}

	/**
	 * @see MailerGenericBuilder#getConnectionPoolMaxSize()
	 */
	@Override
	@NotNull
	public Integer getConnectionPoolMaxSize() {
		return connectionPoolMaxSize;
	}

	/**
	 * @see MailerGenericBuilder#getConnectionPoolClaimTimeoutMillis()
	 */
	@Override
	@NotNull
	public Integer getConnectionPoolClaimTimeoutMillis() {
		return connectionPoolClaimTimeoutMillis;
	}

	/**
	 * @see MailerGenericBuilder#getConnectionPoolExpireAfterMillis()
	 */
	@Override
	@NotNull
	public Integer getConnectionPoolExpireAfterMillis() {
		return connectionPoolExpireAfterMillis;
	}

	/**
	 * @see MailerGenericBuilder#getConnectionPoolExpireAfterCreationMillis()
	 */
	@Override
	@Nullable
	public Integer getConnectionPoolExpireAfterCreationMillis() {
		return connectionPoolExpireAfterCreationMillis;
	}

	@Nullable
	private static Integer validateConnectionPoolExpireAfterCreationMillis(@Nullable final Integer value) {
		if (value != null && value < 1) {
			throw new IllegalArgumentException("connectionPoolExpireAfterCreationMillis must be positive");
		}
		return value;
	}

	/**
	 * @see MailerGenericBuilder#getConnectionPoolLoadBalancingStrategy()
	 */
	@Override
	@NotNull
	public LoadBalancingStrategy getConnectionPoolLoadBalancingStrategy() {
		return connectionPoolLoadBalancingStrategy;
	}

	/**
	 * @see MailerGenericBuilder#getSslHostsToTrust()
	 */
	@Override
	@NotNull
	public List<String> getSslHostsToTrust() {
		return sslHostsToTrust;
	}

	/**
	 * @see MailerGenericBuilder#isTrustAllSSLHost()
	 */
	@Override
	public boolean isTrustAllSSLHost() {
		return trustAllSSLHost;
	}

	/**
	 * @see MailerGenericBuilder#isVerifyingServerIdentity()
	 */
	@Override
	public boolean isVerifyingServerIdentity() {
		return verifyingServerIdentity;
	}

	/**
	 * @see MailerGenericBuilder#isTransportModeLoggingOnly()
	 */
	@Override
	public boolean isTransportModeLoggingOnly() {
		return transportModeLoggingOnly;
	}

	/**
	 * @see MailerGenericBuilder#getProperties()
	 */
	@Override
	@NotNull
	public Properties getProperties() {
		return properties;
	}

	/**
	 * @see MailerGenericBuilder#getCustomMailer()
	 */
	@Override
	@Nullable
	public CustomMailer getCustomMailer() {
		return customMailer;
	}

	/**
	 * @see MailerGenericBuilder#getOAuth2AccessTokenProvider()
	 */
	@Override
	@Nullable
	public OAuth2AccessTokenProvider getOAuth2AccessTokenProvider() {
		return oauth2AccessTokenProvider;
	}

	@Nullable
	MailSendObserver getMailSendObserver() {
		return mailSendObserver;
	}

	/**
	 * @see MailerGenericBuilder#withMailSendObserver(MailSendObserver, Executor)
	 */
	@Nullable
	Executor getMailSendObserverExecutor() {
		return mailSendObserverExecutor;
	}
}
