package org.simplejavamail.mailer.internal;

import org.simplejavamail.config.ConfigKeys;
import org.simplejavamail.config.ConfigKey;

import static org.simplejavamail.internal.config.ConfigurationSnapshot.locksOf;

import com.sanctionco.jmail.EmailValidator;
import com.sanctionco.jmail.JMail;
import jakarta.mail.Session;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.MailSendHandlers;
import org.simplejavamail.api.mailer.MailSendTelemetry;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.internal.clisupport.CliFailoverMailerBuilder;
import org.simplejavamail.api.internal.clisupport.CliMailerConfigurationBuilder;
import org.simplejavamail.api.internal.clisupport.CliMailSendRetryBuilder;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.config.AsyncExecutionConfig;
import org.simplejavamail.api.mailer.config.SocksProxyConfig;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.api.mailer.config.SendingLimitsConfig;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.api.mailer.config.TlsConfig;
import org.simplejavamail.api.mailer.config.ValidationConfig;
import org.simplejavamail.api.mailer.config.AsyncQueueConfig;
import org.simplejavamail.api.mailer.config.AsyncQueueOverflowPolicy;
import org.simplejavamail.api.mailer.config.ConnectionPoolClusterConfig;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.api.mailer.config.MailSendRetryPolicy;
import org.simplejavamail.api.mailer.config.OAuth2AccessTokenProvider;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.SessionDebugOutput;
import org.simplejavamail.api.mailer.config.SessionDebugConfig;
import org.simplejavamail.api.mailer.config.MailSendTelemetryConfig;
import org.simplejavamail.api.mailer.config.SendingRateLimit;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader.Property;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.email.internal.EmailStartingBuilderImpl;
import org.simplejavamail.internal.config.ConfigurationLocks;
import org.simplejavamail.internal.configresolution.ConfigurationChoice;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.util.concurrent.MailSendControl;
import org.simplejavamail.internal.util.DurationMillis;
import org.simplejavamail.mailer.internal.ratelimit.FactorySendingLimits;

import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_CONNECTIONPOOL_CLUSTER_KEY;
import static org.simplejavamail.config.ConfigLoader.Property.EXTRA_PROPERTIES;
import static org.simplejavamail.config.ConfigLoader.Property.PROXY_HOST;
import static org.simplejavamail.config.ConfigLoader.Property.PROXY_PASSWORD;
import static org.simplejavamail.config.ConfigLoader.Property.PROXY_USERNAME;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;
import static org.simplejavamail.internal.util.Preconditions.verifyNonnullOrEmpty;

/**
 * @see MailerGenericBuilder
 */
@SuppressWarnings({"UnusedReturnValue", "unchecked"})
abstract class MailerGenericBuilderImpl<T extends MailerGenericBuilderImpl<?>>
		implements InternalMailerBuilder<T>, CliFailoverMailerBuilder, CliMailSendRetryBuilder, CliMailerConfigurationBuilder {


    private ConnectionPoolConfig connectionPoolConfig = ConnectionPoolConfig.builder().build();
    private AsyncExecutionConfig asyncExecutionConfig = AsyncExecutionConfig.builder().build();
    private SocksProxyConfig proxyConfig = SocksProxyConfig.builder().build();
    private SendingLimitsConfig sendingLimitsConfig = SendingLimitsConfig.builder().build();
    private ConnectionConfig connectionConfig = ConnectionConfig.builder().build();
    private TlsConfig tlsConfig = TlsConfig.builder().build();
    private ValidationConfig validationConfig = ValidationConfig.builder().build();
    /** A builder's generated fallback identity remains stable when a supplied group is replaced. */
    private UUID configuredClusterKey;

	private static final AsyncQueueConfig DEFAULT_ASYNC_QUEUE_CONFIG = new AsyncQueueConfig(
			DEFAULT_ASYNC_QUEUE_CAPACITY, DEFAULT_ASYNC_QUEUE_OVERFLOW_POLICY, DEFAULT_ASYNC_QUEUE_WAIT_TIMEOUT);

	@NotNull
	private final SimpleJavaMailConfig config;
	/** Factory runtime state stays separate from the immutable property snapshot and built OperationalConfig. */
	private final FactorySendingLimits sendingLimits;
	private final FactoryMailerRuntime runtime;
	private final FactoryMailerRuntime.Definition definition;
	private final Map<Property, Object> explicitSessionSettings = new LinkedHashMap<>();
	private final Map<String, Object> explicitRawSessionSettings = new LinkedHashMap<>();
	@Nullable private ConnectionConflictPolicy connectionConflictPolicy;
	/** @see MailerGenericBuilder#withMessageRateLimit(int, Duration) */
	@Nullable private SendingRateLimit messageRateLimit;
	/** @see MailerGenericBuilder#withRecipientRateLimit(int, Duration) */
	@Nullable private SendingRateLimit recipientRateLimit;
	/** @see MailerGenericBuilder#withRateLimitGroup(String) */
	@Nullable private String rateLimitGroup;
	/** @see MailerGenericBuilder#withRateLimitBurstsAllowed(boolean) */
	private boolean rateLimitBurstsAllowed;
	@Nullable private MailerImpl failoverMailer;
	@Nullable private FailoverPolicy failoverPolicy;
	@Nullable private String failoverName;
	private final Properties cliFailoverOptions = new Properties();
	/** @see MailerGenericBuilder#withMailSendRetryPolicy(MailSendRetryPolicy) */
	@NotNull private MailSendRetryPolicy mailSendRetryPolicy = MailSendRetryPolicy.builder().build();
	private final Properties cliRetryOptions = new Properties();

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

	@NotNull private SessionDebugConfig debugConfig = SessionDebugConfig.builder().build();

	/**
	 * @see #disablingAllClientValidation(Boolean)
	 */
	private boolean clientValidationWarningsOnly;

	/**
	 * @see MailerGenericBuilder#withSessionTimeout(Duration)
	 */
	@NotNull
	private Duration connectTimeout;
	private Duration readTimeout;
	private Duration writeTimeout;

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
	private boolean threadPoolSizeExplicit;

	/**
	 * @see MailerGenericBuilder#withThreadPoolKeepAliveTime(Duration)
	 */
	@NotNull
	private Duration threadPoolKeepAliveTime;
	private boolean threadPoolKeepAliveTimeExplicit;

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
	 * @see MailerGenericBuilder#withConnectionPoolClaimTimeout(Duration)
	 */
	@NotNull
	private Duration connectionPoolClaimTimeout;

	/**
	 * @see MailerGenericBuilder#withConnectionPoolExpireAfter(Duration)
	 */
	@NotNull
	private Duration connectionPoolExpireAfter;

	/**
	 * @see MailerGenericBuilder#withConnectionPoolExpireAfterCreation(Duration)
	 */
	@Nullable
	private Duration connectionPoolExpireAfterCreation;

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

	@Nullable private MailSendHandlers sendHandlers;
	@Nullable private Function<UUID, MailSendHandlers> sendHandlersFactory;
	@NotNull private Supplier<UUID> sendIdGenerator = UUID::randomUUID;
	@NotNull private MailSendTelemetryConfig telemetryConfig = MailSendTelemetryConfig.builder().build();

	/**
	 * @see MailerGenericBuilder#withMailSendTimeout(Duration)
	 */
	@Nullable private Duration mailSendTimeout;

	MailerGenericBuilderImpl(@NotNull final SimpleJavaMailConfig config, @NotNull final FactorySendingLimits sendingLimits) {
		this(config, new FactoryMailerRuntime(config, sendingLimits));
	}

	MailerGenericBuilderImpl(@NotNull final SimpleJavaMailConfig config, @NotNull final FactoryMailerRuntime runtime) {
		this(runtime.independent(config), runtime);
	}

	MailerGenericBuilderImpl(final FactoryMailerRuntime.Definition definition, final FactoryMailerRuntime runtime) {
		final SimpleJavaMailConfig config = definition.config;
		this.config = requireNonNull(config, "config");
		this.runtime = requireNonNull(runtime, "runtime");
		this.definition = definition;
		this.sendingLimits = runtime.sendingLimits();
		this.failoverName = MailerConfigurationResolver.configured(config, ConfigKeys.FAILOVER_MAILER, null);
		this.failoverPolicy = ConfiguredFailover.policy(config);
		this.messageRateLimit = resolveRateLimit(config, ConfigKeys.SMTP_MESSAGE_RATE_LIMIT, ConfigKeys.SMTP_MESSAGE_RATE_PERIOD);
		this.recipientRateLimit = resolveRateLimit(config, ConfigKeys.SMTP_RECIPIENT_RATE_LIMIT, ConfigKeys.SMTP_RECIPIENT_RATE_PERIOD);
		this.rateLimitGroup = MailerConfigurationResolver.configured(config, ConfigKeys.SMTP_RATE_LIMIT_GROUP, null);
		if (rateLimitGroup != null) {
			validateRateLimitGroup(rateLimitGroup);
		}
		this.rateLimitBurstsAllowed = MailerConfigurationResolver.resolve(config, ConfigKeys.SMTP_RATE_LIMIT_ALLOW_BURSTS,
				null, DEFAULT_RATE_LIMIT_BURSTS_ALLOWED);
		this.legacySmtpContentSupportEnabled = MailerConfigurationResolver.resolve(config, ConfigKeys.SMTP_LEGACY_CONTENT_SUPPORT,
				null, DEFAULT_LEGACY_SMTP_CONTENT_SUPPORT);
		this.mailSendTimeout = MailerConfigurationResolver.resolve(config, ConfigKeys.DEFAULT_MAIL_SEND_TIMEOUT, null, DEFAULT_MAIL_SEND_TIMEOUT);
		if (mailSendTimeout != null) {
			MailSendControl.positiveTimeoutNanos(mailSendTimeout);
		}
		this.asyncQueueConfig = new AsyncQueueConfig(
				MailerConfigurationResolver.resolve(config, ConfigKeys.DEFAULT_ASYNC_QUEUE_CAPACITY, null, DEFAULT_ASYNC_QUEUE_CONFIG.getCapacity()),
				MailerConfigurationResolver.resolve(config, ConfigKeys.DEFAULT_ASYNC_QUEUE_OVERFLOW_POLICY,
						null, DEFAULT_ASYNC_QUEUE_CONFIG.getOverflowPolicy()),
				configuredDuration(ConfigKeys.ASYNC_QUEUE_WAIT_TIMEOUT, DEFAULT_ASYNC_QUEUE_CONFIG.getWaitTimeout()));
		final Map<String, String> extraProperties = MailerConfigurationResolver.configured(config, ConfigKeys.EXTRA_PROPERTIES, null);
		if (extraProperties != null) {
			this.properties.putAll(extraProperties);
		}
		this.proxyHost = MailerConfigurationResolver.configured(config, ConfigKeys.PROXY_HOST, null);
		this.proxyUsername = MailerConfigurationResolver.configured(config, ConfigKeys.PROXY_USERNAME, null);
		this.proxyPassword = MailerConfigurationResolver.configured(config, ConfigKeys.PROXY_PASSWORD, null);
		this.clusterKey = config.hasProperty(ConfigKeys.DEFAULT_CONNECTIONPOOL_CLUSTER_KEY)
				? UUID.fromString(verifyNonnullOrEmpty(MailerConfigurationResolver.configured(config, ConfigKeys.DEFAULT_CONNECTIONPOOL_CLUSTER_KEY, null)))
				: UUID.randomUUID();

		this.proxyPort =  							verifyNonnullOrEmpty(MailerConfigurationResolver.resolve(config, ConfigKeys.PROXY_PORT, null, DEFAULT_PROXY_PORT));
		this.proxyBridgePort = 						verifyNonnullOrEmpty(MailerConfigurationResolver.resolve(config, ConfigKeys.PROXY_SOCKS5BRIDGE_PORT,
				null, DEFAULT_PROXY_BRIDGE_PORT));
		this.clientValidationWarningsOnly = 			verifyNonnullOrEmpty(MailerConfigurationResolver.resolve(config, ConfigKeys.DISABLE_ALL_CLIENTVALIDATION,
				null, DEFAULT_DISABLE_ALL_CLIENTVALIDATION));
		this.connectTimeout = configuredSocketTimeout(ConfigKeys.CONNECT_TIMEOUT);
		this.readTimeout = configuredSocketTimeout(ConfigKeys.READ_TIMEOUT);
		this.writeTimeout = configuredSocketTimeout(ConfigKeys.WRITE_TIMEOUT);
		this.localBindAddress = 					MailerConfigurationResolver.configured(config, ConfigKeys.SMTP_LOCAL_ADDRESS, null);
		this.localBindPort = 						MailerConfigurationResolver.configured(config, ConfigKeys.SMTP_LOCAL_PORT, null);
		this.smtpClientHostname = 					MailerConfigurationResolver.configured(config, ConfigKeys.SMTP_CLIENT_HOSTNAME, null);
		this.trustAllSSLHost = 						verifyNonnullOrEmpty(MailerConfigurationResolver.resolve(config, ConfigKeys.DEFAULT_TRUST_ALL_HOSTS,
				null, DEFAULT_TRUST_ALL_HOSTS));
		this.verifyingServerIdentity = 				verifyNonnullOrEmpty(MailerConfigurationResolver.resolve(config, ConfigKeys.DEFAULT_VERIFY_SERVER_IDENTITY,
				null, DEFAULT_VERIFY_SERVER_IDENTITY));
		this.threadPoolSize = 						verifyNonnullOrEmpty(MailerConfigurationResolver.resolve(config, ConfigKeys.DEFAULT_POOL_SIZE,
				null, DEFAULT_POOL_SIZE));
		this.threadPoolKeepAliveTime = configuredDuration(ConfigKeys.THREAD_POOL_KEEP_ALIVE_TIME, DEFAULT_POOL_KEEP_ALIVE_TIME);
		this.threadPoolSizeExplicit = config.hasProperty(ConfigKeys.DEFAULT_POOL_SIZE);
		this.threadPoolKeepAliveTimeExplicit = config.hasProperty(ConfigKeys.THREAD_POOL_KEEP_ALIVE_TIME);
		this.connectionPoolCoreSize = 				verifyNonnullOrEmpty(MailerConfigurationResolver.resolve(config, ConfigKeys.DEFAULT_CONNECTIONPOOL_CORE_SIZE,
				null, DEFAULT_CONNECTIONPOOL_CORE_SIZE));
		this.connectionPoolMaxSize = 				verifyNonnullOrEmpty(MailerConfigurationResolver.resolve(config, ConfigKeys.DEFAULT_CONNECTIONPOOL_MAX_SIZE,
				null, DEFAULT_CONNECTIONPOOL_MAX_SIZE));
		this.connectionPoolClaimTimeout = configuredDuration(ConfigKeys.CONNECTION_POOL_CLAIM_TIMEOUT, DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT);
		this.connectionPoolExpireAfter = configuredDuration(ConfigKeys.CONNECTION_POOL_EXPIRE_AFTER, DEFAULT_CONNECTIONPOOL_EXPIREAFTER);
		this.connectionPoolExpireAfterCreation = configuredDuration(ConfigKeys.CONNECTION_POOL_EXPIRE_AFTER_CREATION, DEFAULT_CONNECTIONPOOL_EXPIREAFTERCREATION);
		this.connectionPoolLoadBalancingStrategy = 	verifyNonnullOrEmpty(MailerConfigurationResolver.resolve(config, ConfigKeys.DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY,
				null, LoadBalancingStrategy.valueOf(DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY)));
		final Map<UUID, ConnectionPoolClusterConfig> configuredClusters = MailerConfigurationResolver.configured(config,
				ConfigKeys.DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS, Collections.emptyMap());
		this.connectionPoolClusterConfigs = new LinkedHashMap<>(configuredClusters);
		this.transportModeLoggingOnly = 			verifyNonnullOrEmpty(MailerConfigurationResolver.resolve(config, ConfigKeys.TRANSPORT_MODE_LOGGING_ONLY,
				null, DEFAULT_TRANSPORT_MODE_LOGGING_ONLY));

		final String trustedHosts = MailerConfigurationResolver.configured(config, ConfigKeys.DEFAULT_TRUSTED_HOSTS, null);
		if (trustedHosts != null) {
			this.sslHostsToTrust = new ArrayList<>(Arrays.asList(trustedHosts.split(";")));
		}
		this.emailValidator = JMail.strictValidator();
		this.configuredClusterKey = clusterKey;
	}

	@Nullable
	private static SendingRateLimit resolveRateLimit(final SimpleJavaMailConfig config, final ConfigKey<Integer> countProperty, final ConfigKey<Duration> periodProperty) {
		final Integer count = MailerConfigurationResolver.configured(config, countProperty, null);
		final Duration period = MailerConfigurationResolver.configured(config, periodProperty, null);
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

	FactoryMailerRuntime.Definition getDefinition() {
		return definition;
	}

	/** Validate the whole captured graph before constructing privately owned executors, proxy bridges or pools. */
	final MailerImpl buildConfiguredMailer() {
		final CapturedMailerInputs captured = captureMailerInputs();
		final ResolvedMailerConfiguration resolved = ResolvedMailerConfiguration.resolve(captured);
		final ValidatedMailerPlan plan = ValidatedMailerPlan.validate(resolved);
		final MailerImpl mailer = constructMailer(plan);
		if (mailer.failoverMailer() != null) {
			for (MailerImpl participant = mailer; participant != null; participant = participant.failoverMailer()) {
				participant.definition().recovery.enableCoordinatedFailover();
			}
		}
		return mailer;
	}

	abstract MailerImpl constructMailer(ValidatedMailerPlan plan);

	final CapturedMailerInputs captureMailerInputs() {
		return captureMailerInputs(new LinkedHashSet<>());
	}

	final CapturedMailerInputs captureMailerInputs(final Set<FactoryMailerRuntime.Definition> route) {
		resolveCliFailoverPolicy();
		if (!route.add(definition)) {
			throw new IllegalArgumentException("The named failover Mailers form a cycle. "
					+ "Remove the relationship that points back to an earlier definition; each failover route must end.");
		}
		final Session suppliedSession = this instanceof MailerFromSessionBuilderImpl ? ((MailerFromSessionBuilderImpl) this).getSession() : null;
		final MailerRegularBuilderImpl regular = this instanceof MailerRegularBuilderImpl ? (MailerRegularBuilderImpl) this : null;
		final CapturedConnectionInputs connection = captureConnectionInputs(suppliedSession);
		final MailerConfigurationRequirements requirements = captureConfigurationRequirements();
		final MailerOperationalSettings operational = captureOperationalSettings(requirements);
		final CapturedMailerInputs child = failoverName == null ? null
				: new MailerRegularBuilderImpl(runtime.named(failoverName), runtime).captureMailerInputs(route);
		final String lockedName = locksOf(config).contains(Property.FAILOVER_MAILER)
				? MailerConfigurationResolver.configured(config, ConfigKeys.FAILOVER_MAILER, null) : null;
		final CapturedMailerInputs result = CapturedMailerInputs.builder().connection(connection).operational(operational).requirements(requirements)
				.definition(definition).sendingLimits(sendingLimits).emailValidator(emailValidator).emailDefaults(emailDefaults).emailOverrides(emailOverrides)
				.maximumEmailSize(maximumEmailSize).handlers(sendHandlers).handlersFactory(sendHandlersFactory).idGenerator(sendIdGenerator)
				.applyDebugEnabled(suppliedSession == null || debugConfig.getEnabled() != null || config.hasProperty(ConfigKeys.JAVAXMAIL_DEBUG))
				.applyDebugOutput(debugConfig.getOutput() != null || debugConfig.getPrinter() != null || config.hasProperty(ConfigKeys.JAVAXMAIL_DEBUG_OUTPUT))
				.regularMailer(regular != null).opportunisticTLS(regular == null || regular.isOpportunisticTLS())
				.ownedTransportStrategy(regular == null ? null : regular.getTransportStrategy()).password(regular == null ? null : regular.getPassword())
				.ignoredSmtpSettings(regular != null && customMailer != null && regular.getHost() != null)
				.permittedFailoverDefinition(lockedName == null ? null : runtime.named(lockedName)).namedChild(child).borrowedFailover(failoverMailer).build();
		route.remove(definition);
		return result;
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
		captureConfigurationRequirements().validate();
	}

	MailerConfigurationRequirements captureConfigurationRequirements() {
		final Map<Property, Object> choices = new LinkedHashMap<>();
		if (!locksOf(config).isEmpty()) {
			captureSendingLockChoices(choices);
			captureConnectionLockChoices(choices);
			captureExecutionLockChoices(choices);
		}
		final Map<Property, Object> serverChoices = new LinkedHashMap<>();
		captureLockedServerChoices(serverChoices);
		return MailerConfigurationRequirements.builder().config(config).choices(choices).serverChoices(serverChoices)
				.explicitSessionSettings(explicitSessionSettings).properties(properties)
				.clusters(connectionPoolClusterConfigs.values()).sslHostsToTrust(sslHostsToTrust).clusterKey(clusterKey)
				.retryPolicy(resolveMailSendRetryPolicy()).telemetryConfig(resolveTelemetryConfig())
				.strategy(locksOf(config).isEmpty() ? null : transportStrategyForLocks())
				.customMailer(customMailer != null).borrowedExecutor(executorService != null)
				.tokenProvider(oauth2AccessTokenProvider != null).customFactoryInstance(hasCustomSSLFactoryInstanceForLocks()).build();
	}

	void captureLockedServerChoices(final Map<Property, Object> choices) { }

	boolean hasCustomSSLFactoryInstanceForLocks() { return false; }

	private void captureSendingLockChoices(final Map<Property, Object> choices) {
		choices.put(Property.SMTP_RATE_LIMIT_GROUP, rateLimitGroup);
		choices.put(Property.SMTP_MESSAGE_RATE_LIMIT, messageRateLimit == null ? null : messageRateLimit.getCount());
		choices.put(Property.SMTP_MESSAGE_RATE_PERIOD, messageRateLimit == null ? null : messageRateLimit.getPeriod());
		choices.put(Property.SMTP_RECIPIENT_RATE_LIMIT, recipientRateLimit == null ? null : recipientRateLimit.getCount());
		choices.put(Property.SMTP_RECIPIENT_RATE_PERIOD, recipientRateLimit == null ? null : recipientRateLimit.getPeriod());
		choices.put(Property.SMTP_RATE_LIMIT_ALLOW_BURSTS, rateLimitBurstsAllowed);
		choices.put(Property.SMTP_LEGACY_CONTENT_SUPPORT, legacySmtpContentSupportEnabled);
		choices.put(Property.DEFAULT_MAIL_SEND_TIMEOUT, mailSendTimeout);
		choices.put(Property.TRANSPORT_MODE_LOGGING_ONLY, transportModeLoggingOnly);
	}

	private void captureConnectionLockChoices(final Map<Property, Object> choices) {
		choices.put(Property.CONNECTION_CONFLICT_POLICY, resolvedConnectionConflictPolicy());
		choices.put(Property.PROXY_HOST, proxyHost);
		choices.put(Property.PROXY_PORT, proxyPort);
		choices.put(Property.PROXY_USERNAME, proxyUsername);
		choices.put(Property.PROXY_PASSWORD, proxyPassword);
		choices.put(Property.PROXY_SOCKS5BRIDGE_PORT, proxyBridgePort);
		choices.put(Property.JAVAXMAIL_DEBUG, MailerConfigurationResolver.resolve(config, ConfigKeys.JAVAXMAIL_DEBUG,
				debugConfig.getEnabled(), DEFAULT_JAVAXMAIL_DEBUG));
		choices.put(Property.JAVAXMAIL_DEBUG_OUTPUT, debugConfig.getPrinter() == null
				? MailerConfigurationResolver.resolve(config, ConfigKeys.JAVAXMAIL_DEBUG_OUTPUT, debugConfig.getOutput(), null)
				: debugConfig.getPrinter() == System.out ? SessionDebugOutput.STDOUT : debugConfig.getPrinter() == System.err ? SessionDebugOutput.STDERR : null);
		choices.put(Property.DISABLE_ALL_CLIENTVALIDATION, clientValidationWarningsOnly);
		choices.put(Property.SMTP_LOCAL_ADDRESS, localBindAddress);
		choices.put(Property.SMTP_LOCAL_PORT, localBindPort);
		choices.put(Property.SMTP_CLIENT_HOSTNAME, smtpClientHostname);
		choices.put(Property.DEFAULT_TRUST_ALL_HOSTS, trustAllSSLHost);
		choices.put(Property.DEFAULT_VERIFY_SERVER_IDENTITY, verifyingServerIdentity);
	}

	private void captureExecutionLockChoices(final Map<Property, Object> choices) {
		choices.put(Property.DEFAULT_ASYNC_QUEUE_CAPACITY, asyncQueueConfig.getCapacity());
		choices.put(Property.DEFAULT_ASYNC_QUEUE_OVERFLOW_POLICY, asyncQueueConfig.getOverflowPolicy());
		choices.put(Property.DEFAULT_ASYNC_QUEUE_WAIT_TIMEOUT_MILLIS, DurationMillis.toInt(asyncQueueConfig.getWaitTimeout(), "asyncQueueWaitTimeout"));
		choices.put(Property.DEFAULT_POOL_SIZE, threadPoolSize);
		choices.put(Property.DEFAULT_POOL_KEEP_ALIVE_TIME, DurationMillis.toNullableInt(threadPoolKeepAliveTime, "threadPoolKeepAliveTime"));
		choices.put(Property.DEFAULT_CONNECTIONPOOL_CORE_SIZE, connectionPoolCoreSize);
		choices.put(Property.DEFAULT_CONNECTIONPOOL_MAX_SIZE, connectionPoolMaxSize);
		choices.put(Property.DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT_MILLIS, DurationMillis.toNullableInt(connectionPoolClaimTimeout, "connectionPoolClaimTimeout"));
		choices.put(Property.DEFAULT_CONNECTIONPOOL_EXPIREAFTER_MILLIS, DurationMillis.toNullableInt(connectionPoolExpireAfter, "connectionPoolExpireAfter"));
		choices.put(Property.DEFAULT_CONNECTIONPOOL_EXPIREAFTERCREATION_MILLIS, DurationMillis.toNullableInt(connectionPoolExpireAfterCreation, "connectionPoolExpireAfterCreation"));
		choices.put(Property.DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY, connectionPoolLoadBalancingStrategy);
	}

	/** @see MailerGenericBuilder#withMessageRateLimit(int, Duration) */
	@Override
	public T withMessageRateLimit(final int count, @NotNull final Duration period) {
		messageRateLimit = resolveRateLimitChoice(new SendingRateLimit(count, period), false, ConfigKeys.SMTP_MESSAGE_RATE_LIMIT, ConfigKeys.SMTP_MESSAGE_RATE_PERIOD);
		return (T) this;
	}

	/** @see MailerGenericBuilder#withRecipientRateLimit(int, Duration) */
	@Override
	public T withRecipientRateLimit(final int count, @NotNull final Duration period) {
		recipientRateLimit = resolveRateLimitChoice(new SendingRateLimit(count, period), false, ConfigKeys.SMTP_RECIPIENT_RATE_LIMIT, ConfigKeys.SMTP_RECIPIENT_RATE_PERIOD);
		return (T) this;
	}

	/** @see MailerGenericBuilder#withRateLimitGroup(String) */
	@Override
	public T withRateLimitGroup(@NotNull final String group) {
		validateRateLimitGroup(group);
		rateLimitGroup = MailerConfigurationResolver.resolve(config, ConfigKeys.SMTP_RATE_LIMIT_GROUP,
				ConfigurationChoice.set(group), null, "withRateLimitGroup");
		return (T) this;
	}

	private static void validateRateLimitGroup(final String group) {
		if (requireNonNull(group, "group").isBlank()) {
			throw new IllegalArgumentException("A rate-limit group needs a nonblank name; call resetRateLimitGroup() for private allowance.");
		}
	}

	/** @see MailerGenericBuilder#withRateLimitBurstsAllowed(boolean) */
	@Override
	public T withRateLimitBurstsAllowed(final boolean allowed) {
		rateLimitBurstsAllowed = MailerConfigurationResolver.resolve(config, ConfigKeys.SMTP_RATE_LIMIT_ALLOW_BURSTS,
				ConfigurationChoice.set(allowed), DEFAULT_RATE_LIMIT_BURSTS_ALLOWED, "withRateLimitBurstsAllowed");
		return (T) this;
	}

	/** @see MailerGenericBuilder#resetMessageRateLimit() */
	@Override
	public T resetMessageRateLimit() {
		messageRateLimit = resolveRateLimitChoice(null, true, ConfigKeys.SMTP_MESSAGE_RATE_LIMIT, ConfigKeys.SMTP_MESSAGE_RATE_PERIOD);
		return (T) this;
	}

	/** @see MailerGenericBuilder#resetRecipientRateLimit() */
	@Override
	public T resetRecipientRateLimit() {
		recipientRateLimit = resolveRateLimitChoice(null, true, ConfigKeys.SMTP_RECIPIENT_RATE_LIMIT, ConfigKeys.SMTP_RECIPIENT_RATE_PERIOD);
		return (T) this;
	}

	/** @see MailerGenericBuilder#resetRateLimitGroup() */
	@Override
	public T resetRateLimitGroup() {
		rateLimitGroup = MailerConfigurationResolver.resolve(config, ConfigKeys.SMTP_RATE_LIMIT_GROUP,
				ConfigurationChoice.remove(), null, "resetRateLimitGroup");
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
	EmailGovernance buildEmailGovernance() {
		return new EmailGovernanceImpl(
				config,
				new EmailStartingBuilderImpl(config),
				getEmailValidator(),
				getEmailDefaults(),
				getEmailOverrides(),
				getMaximumEmailSize());
	}

	/** Resolve connection facts once, without changing or claiming a caller-owned Session. */
	MailerSessionSettings buildSessionSettings(@Nullable final Session suppliedSession) {
		return prepareConnectionPlan(suppliedSession).settings();
	}

	final ValidatedConnectionPlan prepareConnectionPlan(@Nullable final Session suppliedSession) {
		validateOwnedConnectionRequirements();
		final CapturedConnectionInputs inputs = captureConnectionInputs(suppliedSession);
		final ResolvedConnection resolved = ConnectionConfigurationResolver.resolve(inputs);
		final MailerSessionSettings settings = sessionSettingsWithLocks(inputs, resolved);
		return ValidatedConnectionPlan.validate(resolved, settings, this instanceof MailerRegularBuilderImpl ? (MailerRegularBuilderImpl) this : null);
	}

	CapturedConnectionInputs captureConnectionInputs(@Nullable final Session suppliedSession) {
		if (suppliedSession != null) {
			verifySessionIsUnowned(suppliedSession);
		}
		final Map<Property, Object> connectionChoices = suppliedSession == null
				? ownedConnectionChoices() : new LinkedHashMap<>(explicitSessionSettings);
		final ConfigurationLocks locks = locksOf(config);
		final TransportStrategy strategy = transportStrategyForLocks();
		verifyConnectionCustomizationSupported(strategy);
		addLockedConnectionChoices(connectionChoices, strategy, locks);

		final Properties additional = getProperties();
		final Map<String, Object> required = MailerSessionSettings.connectionOverrides(strategy, lockedConnectionChoices(strategy, locks), additional);
		if (strategy != null && Boolean.FALSE.equals(locks.getValues().get(Property.DEFAULT_TRUST_ALL_HOSTS.key()))
				&& !locks.contains(Property.DEFAULT_TRUSTED_HOSTS)) {
			required.put(strategy.propertyNameSSLTrust(), ConnectionConfigurationResolver.TrustAllDisabled.INSTANCE);
		}
		return CapturedConnectionInputs.builder().config(config).targetSession(suppliedSession).strategy(strategy)
				.policy(resolvedConnectionConflictPolicy()).additionalProperties(additional)
				.rawChoices(explicitRawSessionSettings).typedChoices(explicitSessionSettings)
				.defaults(connectionDefaults(suppliedSession, strategy, connectionChoices)).physicalChoices(regularPhysicalChoices(strategy))
				.requiredOverrides(required)
				.proxyFallback(new ProxyConfigImpl(proxyHost, proxyPort, proxyUsername, proxyPassword, proxyBridgePort))
				.proxyCustomized(proxyCustomized()).customMailer(customMailer).tokenProvider(oauth2AccessTokenProvider).build();
	}

	private void verifyConnectionCustomizationSupported(@Nullable final TransportStrategy strategy) {
		if (customMailer == null && strategy == null && (!explicitSessionSettings.isEmpty() || proxyCustomized())) {
			// Proxy-only customization must also reject a protocol whose connection properties SJM cannot interpret.
			final Map<Property, Object> choices = explicitSessionSettings.isEmpty()
					? Collections.singletonMap(Property.PROXY_HOST, proxyHost) : explicitSessionSettings;
			MailerSessionSettings.connectionOverrides(null, choices, getProperties());
		}
	}

	private Map<String, Object> connectionDefaults(@Nullable final Session suppliedSession, @Nullable final TransportStrategy strategy,
			final Map<Property, Object> connectionChoices) {
		final Map<String, Object> defaults = new LinkedHashMap<>();
		if (suppliedSession == null && strategy != null) {
			strategy.generateProperties().forEach((key, value) -> defaults.put(key.toString(), value));
			defaults.putAll(MailerSessionSettings.connectionOverrides(strategy, connectionChoices, getProperties()));
			if (customMailer == null) {
				addRegularConnectionDefaults(defaults, strategy);
			}
		}
		return defaults;
	}

	private MailerSessionSettings sessionSettingsWithLocks(final CapturedConnectionInputs inputs, final ResolvedConnection resolution) {
		final boolean applyDebugEnabled = inputs.getSuppliedSession() == null || debugConfig.getEnabled() != null || config.hasProperty(ConfigKeys.JAVAXMAIL_DEBUG);
		final boolean applyDebugOutput = debugConfig.getOutput() != null || debugConfig.getPrinter() != null || config.hasProperty(ConfigKeys.JAVAXMAIL_DEBUG_OUTPUT);
		return MailerSessionSettings.resolve(inputs.getSuppliedSession(), inputs.getStrategy(), inputs.copyAdditionalProperties(), resolution.overrides, locksOf(config).getValues(),
				resolution.proxy, resolution.shadowedFactoryClassProperty, applyDebugEnabled, applyDebugOutput).toBuilder()
				.connectionConflictPolicy(inputs.getPolicy()).customMailer(inputs.getCustomMailer()).tokenProvider(inputs.getTokenProvider()).build();
	}

	private void verifySessionIsUnowned(final Session session) {
		SessionBasedEmailToMimeMessageConverter.verifySessionCanBeConfigured(session, !locksOf(config).isEmpty());
		if (session.getProperties().containsKey("org.simplejavamail.internal.mailerOwner")) {
			throw new IllegalArgumentException("This Session or its Properties object already belongs to another Mailer, which may still be building or shutting down. "
					+ "Sharing it would mix their email defaults, overrides and connection settings. Reuse the existing Mailer, or create a fresh Session "
					+ "with independent Properties copied from your original SMTP settings.");
		}
	}

	private Map<Property, Object> lockedConnectionChoices(@Nullable final TransportStrategy strategy, final ConfigurationLocks locks) {
		final Map<Property, Object> choices = new LinkedHashMap<>();
		addLockedConnectionChoices(choices, strategy, locks);
		return choices;
	}

	void addRegularConnectionDefaults(final Map<String, Object> defaults, final TransportStrategy strategy) {
	}

	Map<String, Object> regularPhysicalChoices(@Nullable final TransportStrategy strategy) {
		return Collections.emptyMap();
	}

	void validateOwnedConnectionRequirements() {
	}

	private boolean proxyCustomized() {
		return proxyConfig.getHost() != null || proxyConfig.isHostCleared() || proxyConfig.getPort() != null || proxyConfig.isPortCleared()
				|| proxyConfig.getUsername() != null || proxyConfig.isUsernameCleared() || proxyConfig.getPassword() != null
				|| proxyConfig.isPasswordCleared() || proxyConfig.getBridgePort() != null
				|| explicitSessionSettings.keySet().stream().anyMatch(property -> property.name().startsWith("PROXY_"));
	}

	private void addProxyDeclarations(final Map<Property, Object> declarations) {
		if (proxyConfig.getHost() != null || proxyConfig.isHostCleared()) {
			declarations.put(Property.PROXY_HOST, proxyHost);
		}
		if (proxyConfig.getPort() != null || proxyConfig.isPortCleared()) {
			declarations.put(Property.PROXY_PORT, proxyPort);
		}
		if (proxyConfig.getUsername() != null || proxyConfig.isUsernameCleared()) {
			declarations.put(Property.PROXY_USERNAME, proxyUsername);
		}
		if (proxyConfig.getPassword() != null || proxyConfig.isPasswordCleared()) {
			declarations.put(Property.PROXY_PASSWORD, proxyPassword);
		}
		if (proxyConfig.getBridgePort() != null) {
			declarations.put(Property.PROXY_SOCKS5BRIDGE_PORT, proxyBridgePort);
		}
	}

	/** @see MailerGenericBuilder#withConnectionConflictPolicy(ConnectionConflictPolicy) */
	@Override
	public T withConnectionConflictPolicy(final ConnectionConflictPolicy policy) {
		connectionConflictPolicy = requireNonNull(policy, "connectionConflictPolicy");
		return (T) this;
	}

	/** @see MailerGenericBuilder#getConnectionConflictPolicy() */
	@Override
	@Nullable
	public ConnectionConflictPolicy getConnectionConflictPolicy() {
		return connectionConflictPolicy;
	}

	/** @see MailerGenericBuilder#resetConnectionConflictPolicy() */
	@Override
	public T resetConnectionConflictPolicy() {
		return withConnectionConflictPolicy(DEFAULT_CONNECTION_CONFLICT_POLICY);
	}

	/** @see MailerGenericBuilder#useConfiguredConnectionConflictPolicy() */
	@Override
	public T useConfiguredConnectionConflictPolicy() {
		connectionConflictPolicy = null;
		return (T) this;
	}

	private ConnectionConflictPolicy resolvedConnectionConflictPolicy() {
		return MailerConfigurationResolver.resolve(config, ConfigKeys.CONNECTION_CONFLICT_POLICY,
				connectionConflictPolicy, DEFAULT_CONNECTION_CONFLICT_POLICY);
	}

	private void addLockedConnectionChoices(final Map<Property, Object> connectionChoices, @Nullable final TransportStrategy strategy,
			final ConfigurationLocks locks) {
		if (strategy == null) {
			return; // The caller-Session lock check explains why an opaque protocol cannot satisfy an SMTP restriction.
		}
		if (locks.contains(Property.DEFAULT_SESSION_TIMEOUT_MILLIS)) {
			for (final Property property : socketTimeoutProperties()) {
				connectionChoices.put(property, config.asMap().get(Property.DEFAULT_SESSION_TIMEOUT_MILLIS));
			}
		}
		for (final Property property : List.of(Property.DEFAULT_CONNECT_TIMEOUT_MILLIS, Property.DEFAULT_READ_TIMEOUT_MILLIS,
				Property.DEFAULT_WRITE_TIMEOUT_MILLIS, Property.SMTP_LOCAL_ADDRESS, Property.SMTP_LOCAL_PORT,
				Property.SMTP_CLIENT_HOSTNAME, Property.DEFAULT_TRUSTED_HOSTS, Property.DEFAULT_TRUST_ALL_HOSTS, Property.DEFAULT_VERIFY_SERVER_IDENTITY)) {
			if (locks.contains(property)) {
				final Object value = config.asMap().get(property);
				// SJM's configured host list uses semicolons; Jakarta Mail expects spaces between the same host names.
				connectionChoices.put(property, property == Property.DEFAULT_TRUSTED_HOSTS ? String.join(" ", ((String) value).split(";")) : value);
			}
		}
	}

	private Map<Property, Object> ownedConnectionChoices() {
		final Map<Property, Object> choices = new LinkedHashMap<>();
		final TransportStrategy strategy = requireNonNull(transportStrategyForLocks(), "transportStrategy");
		addOwnedSocketTimeout(choices, Property.DEFAULT_CONNECT_TIMEOUT_MILLIS, strategy.propertyNameConnectionTimeout(), connectTimeout);
		addOwnedSocketTimeout(choices, Property.DEFAULT_READ_TIMEOUT_MILLIS, strategy.propertyNameTimeout(), readTimeout);
		addOwnedSocketTimeout(choices, Property.DEFAULT_WRITE_TIMEOUT_MILLIS, strategy.propertyNameWriteTimeout(), writeTimeout);
		choices.put(Property.DEFAULT_TRUSTED_HOSTS, String.join(" ", sslHostsToTrust));
		choices.put(Property.DEFAULT_TRUST_ALL_HOSTS, trustAllSSLHost);
		choices.put(Property.DEFAULT_VERIFY_SERVER_IDENTITY, verifyingServerIdentity);
		if (!valueNullOrEmpty(localBindAddress)) {
			choices.put(Property.SMTP_LOCAL_ADDRESS, localBindAddress);
		}
		if (localBindPort != null) {
			choices.put(Property.SMTP_LOCAL_PORT, localBindPort);
		}
		if (!valueNullOrEmpty(smtpClientHostname)) {
			choices.put(Property.SMTP_CLIENT_HOSTNAME, smtpClientHostname);
		}
		// An explicit removal must also replace an unlocked additional Jakarta Mail property on an owned Session.
		for (final Property property : List.of(Property.SMTP_LOCAL_ADDRESS, Property.SMTP_LOCAL_PORT, Property.SMTP_CLIENT_HOSTNAME)) {
			if (explicitSessionSettings.containsKey(property) && explicitSessionSettings.get(property) == null) {
				choices.put(property, null);
			}
		}
		return choices;
	}

	/** A captured timeout supplies a fallback; an explicit typed choice deliberately replaces its raw Jakarta Mail setting. */
	private void addOwnedSocketTimeout(final Map<Property, Object> choices, final Property property, final String sessionKey, final Duration value) {
		if (explicitSessionSettings.containsKey(property) || MailerSessionSettings.propertyValue(properties, sessionKey) == null) {
			choices.put(property, DurationMillis.toInt(value, property.key()));
		}
	}

	private static List<Property> socketTimeoutProperties() {
		return List.of(Property.DEFAULT_CONNECT_TIMEOUT_MILLIS, Property.DEFAULT_READ_TIMEOUT_MILLIS, Property.DEFAULT_WRITE_TIMEOUT_MILLIS);
	}

	private Duration configuredSocketTimeout(final ConfigKey<Duration> property) {
		return configuredDuration(property, configuredDuration(ConfigKeys.SESSION_TIMEOUT, DEFAULT_SESSION_TIMEOUT));
	}

	private Duration socketTimeoutChoice(@Nullable final Duration supplied, final ConfigKey<Duration> property) {
        return MailerConfigurationResolver.resolve(config, property,
                supplied, configuredDuration(ConfigKeys.SESSION_TIMEOUT, DEFAULT_SESSION_TIMEOUT));
	}

	private MailerOperationalSettings captureOperationalSettings(final MailerConfigurationRequirements requirements) {
		return MailerOperationalSettings.builder().legacySmtpContentSupportEnabled(legacySmtpContentSupportEnabled)
				.telemetryConfig(requirements.getTelemetryConfig()).threadPoolSize(resolvedWorkerCount()).threadPoolKeepAliveTime(resolvedWorkerKeepAlive())
				.clusterKey(clusterKey).connectionPoolCoreSize(connectionPoolCoreSize).connectionPoolMaxSize(connectionPoolMaxSize)
				.connectionPoolClaimTimeout(connectionPoolClaimTimeout).connectionPoolExpireAfter(connectionPoolExpireAfter)
				.connectionPoolExpireAfterCreation(connectionPoolExpireAfterCreation).connectionPoolLoadBalancingStrategy(connectionPoolLoadBalancingStrategy)
				.connectionPoolClusterConfigs(connectionPoolClusterConfigs).transportModeLoggingOnly(transportModeLoggingOnly).debugConfig(debugConfig)
				.clientValidationWarningsOnly(clientValidationWarningsOnly).executorService(executorService).asyncQueueConfig(asyncQueueConfig)
				.mailSendTimeout(mailSendTimeout).messageRateLimit(messageRateLimit).recipientRateLimit(recipientRateLimit).rateLimitGroup(rateLimitGroup)
				.rateLimitBurstsAllowed(rateLimitBurstsAllowed).failoverPolicy(failoverPolicy).mailSendRetryPolicy(requirements.getRetryPolicy()).build();
	}

	/** @see MailerGenericBuilder#withMailSendRetryPolicy(MailSendRetryPolicy) */
	@Override public T withMailSendRetryPolicy(final MailSendRetryPolicy policy) {
		mailSendRetryPolicy = requireNonNull(policy, "mailSendRetryPolicy");
		cliRetryOptions.clear();
		return (T) this;
	}
	/** @see MailerGenericBuilder#clearMailSendRetryPolicy() */
	@Override public T clearMailSendRetryPolicy() {
		return withMailSendRetryPolicy(MailSendRetryPolicy.builder().clearRetrySchedule()
				.resetWaitingCapacity().resetMaximumMessageSize().resetMemoryLimit().build());
	}
	/** @see MailerGenericBuilder#getMailSendRetryPolicy() */
	@Override @NotNull public MailSendRetryPolicy getMailSendRetryPolicy() { return mailSendRetryPolicy; }
	/** @see CliMailSendRetryBuilder#withMailSendRetryWaitingCapacity(int) */
	@Override public T withMailSendRetryWaitingCapacity(final int capacity) {
		return withCliRetryOption(Property.DEFAULT_MAIL_SEND_RETRY_WAITING_CAPACITY, capacity);
	}
	/** @see CliMailSendRetryBuilder#resetMailSendRetryWaitingCapacity() */
	@Override public T resetMailSendRetryWaitingCapacity() {
		return withMailSendRetryWaitingCapacity(DEFAULT_MAIL_SEND_RETRY_WAITING_CAPACITY);
	}
	/** @see CliMailSendRetryBuilder#withMailSendRetryMaximumMessageSize(long) */
	@Override public T withMailSendRetryMaximumMessageSize(final long bytes) {
		return withCliRetryOption(Property.DEFAULT_MAIL_SEND_RETRY_MAXIMUM_MESSAGE_SIZE, bytes);
	}
	/** @see CliMailSendRetryBuilder#resetMailSendRetryMaximumMessageSize() */
	@Override public T resetMailSendRetryMaximumMessageSize() {
		return withMailSendRetryMaximumMessageSize(DEFAULT_MAIL_SEND_RETRY_MAXIMUM_MESSAGE_SIZE);
	}
	/** @see CliMailSendRetryBuilder#withMailSendRetryMemoryLimit(long) */
	@Override public T withMailSendRetryMemoryLimit(final long bytes) { return withCliRetryOption(Property.DEFAULT_MAIL_SEND_RETRY_MEMORY_LIMIT, bytes); }
	/** @see CliMailSendRetryBuilder#resetMailSendRetryMemoryLimit() */
	@Override public T resetMailSendRetryMemoryLimit() {
		return withMailSendRetryMemoryLimit(DEFAULT_MAIL_SEND_RETRY_MEMORY_LIMIT);
	}

	/** @see CliMailSendRetryBuilder#withMailSendRetryAttempts(int) */
	@Override public T withMailSendRetryAttempts(final int attempts) { return withCliRetryOption(Property.SMTP_RETRY_ATTEMPTS, attempts); }
	/** @see CliMailSendRetryBuilder#withMailSendRetryDelay(Duration) */
	@Override public T withMailSendRetryDelay(final Duration delay) { return withCliRetryOption(Property.SMTP_RETRY_DELAY, delay); }
	/** @see CliMailSendRetryBuilder#withMailSendRetryBackoff(int, Duration) */
	@Override public T withMailSendRetryBackoff(final int multiplier, final Duration maximumDelay) {
		// Validate through the canonical builder before storing either CLI field; an invalid pair must not leave a partial change.
		MailSendRetryPolicy.builder().withRetryBackoff(multiplier, maximumDelay);
		withCliRetryOption(Property.SMTP_RETRY_BACKOFF_MULTIPLIER, multiplier);
		return withCliRetryOption(Property.SMTP_RETRY_MAXIMUM_DELAY, maximumDelay);
	}
	/** @see CliMailSendRetryBuilder#withMailSendRetryJitter(double) */
	@Override public T withMailSendRetryJitter(final double fraction) { return withCliRetryOption(Property.SMTP_RETRY_JITTER, fraction); }

	private T withCliRetryOption(final Property property, final Object value) {
		cliRetryOptions.put(property.key(), requireNonNull(value, property.key()));
		return (T) this;
	}

	private MailSendRetryPolicy resolveMailSendRetryPolicy() {
		if (cliRetryOptions.isEmpty()) {
			return ConfiguredMailSendRetry.policy(config, mailSendRetryPolicy);
		}
		cliRetryOptions.forEach((key, value) -> locksOf(config).verify((String) key, value));
		final SimpleJavaMailConfig overrides = ConfigLoader.builder().withProperties("CLI retry options", cliRetryOptions).load();
		return ConfiguredMailSendRetry.policy(config, ConfiguredMailSendRetry.withOverrides(mailSendRetryPolicy, overrides));
	}

	/** @see MailerGenericBuilder#withFailoverMailer(Mailer, FailoverPolicy) */
	@Override
	public T withFailoverMailer(@NotNull final Mailer mailer, @NotNull final FailoverPolicy policy) {
		requireNonNull(mailer, "failoverMailer");
		requireNonNull(policy, "policy");
		if (!(mailer instanceof MailerImpl)) {
			throw new IllegalArgumentException("Use a failover Mailer created with Simple Java Mail's mailerBuilder(). "
					+ "An independent Mailer implementation cannot share this send's deadline, cancellation or connection lifecycle. "
					+ "Third-party Jakarta Mail providers are supported through a Simple Java Mail Mailer.");
		}
		failoverMailer = (MailerImpl) mailer;
		failoverPolicy = MailerConfigurationResolver.select(ConfigurationChoice.set(policy), ConfiguredFailover.policy(config), null,
				"simplejavamail.failover", "withFailoverMailer");
		failoverName = null;
		cliFailoverOptions.clear();
		return (T) this;
	}

	/** @see MailerGenericBuilder#clearFailoverMailer() */
	@Override
	public T clearFailoverMailer() {
		failoverMailer = null;
		failoverPolicy = null;
		failoverName = null;
		cliFailoverOptions.clear();
		return (T) this;
	}

	/** @see CliFailoverMailerBuilder#withFailoverMailerName(String) */
	@Override
	public T withFailoverMailerName(final String name) {
		failoverName = requireNonNull(name, "name");
		failoverMailer = null;
		return withCliFailoverOption(Property.FAILOVER_MAILER, name);
	}

	/** @see CliFailoverMailerBuilder#withFailoverPrimaryConnectionAttempts(int) */
	@Override
	public T withFailoverPrimaryConnectionAttempts(final int attempts) {
		return withCliFailoverOption(Property.FAILOVER_PRIMARY_CONNECTION_ATTEMPTS, attempts);
	}

	/** @see CliFailoverMailerBuilder#withFailoverPrimaryRetryDelay(Duration) */
	@Override
	public T withFailoverPrimaryRetryDelay(final Duration delay) {
		return withCliFailoverOption(Property.FAILOVER_PRIMARY_RETRY_DELAY, delay);
	}

	/** @see CliFailoverMailerBuilder#withFailoverPrimaryRecheckInterval(Duration) */
	@Override
	public T withFailoverPrimaryRecheckInterval(final Duration interval) {
		return withCliFailoverOption(Property.FAILOVER_PRIMARY_RECHECK_INTERVAL, interval);
	}

	/** @see CliFailoverMailerBuilder#withFailoverPrimaryRecheckBackoff(int, Duration) */
	@Override
	public T withFailoverPrimaryRecheckBackoff(final int multiplier, final Duration maximumInterval) {
		FailoverPolicy.builder().withPrimaryRecheckBackoff(multiplier, maximumInterval);
		withCliFailoverOption(Property.FAILOVER_PRIMARY_BACKOFF_MULTIPLIER, multiplier);
		return withCliFailoverOption(Property.FAILOVER_PRIMARY_MAXIMUM_RECHECK_INTERVAL, maximumInterval);
	}

	/** @see CliFailoverMailerBuilder#withFailoverOnAuthenticationFailure(boolean) */
	@Override
	public T withFailoverOnAuthenticationFailure(final boolean enabled) {
		return withCliFailoverOption(Property.FAILOVER_ON_AUTHENTICATION_FAILURE, enabled);
	}

	/** @see CliFailoverMailerBuilder#withFailoverOnCertificateVerificationFailure(boolean) */
	@Override
	public T withFailoverOnCertificateVerificationFailure(final boolean enabled) {
		return withCliFailoverOption(Property.FAILOVER_ON_CERTIFICATE_VERIFICATION_FAILURE, enabled);
	}

	private T withCliFailoverOption(final Property property, final Object value) {
		cliFailoverOptions.put(property.key(), requireNonNull(value, property.key()));
		return (T) this;
	}

	private void resolveCliFailoverPolicy() {
		if (!cliFailoverOptions.isEmpty()) {
			// Validate with the other finished builder settings, so CLI construction can show the safe, property-specific conflict.
			cliFailoverOptions.forEach((key, value) -> locksOf(config).verify((String) key, value));
			failoverPolicy = ConfiguredFailover.policy(ConfigLoader.builder().withConfig(config).withProperties("CLI failover options", cliFailoverOptions).load());
		}
	}

	/** @see MailerGenericBuilder#getFailoverMailer() */
	@Override
	@Nullable
	public Mailer getFailoverMailer() {
		return failoverMailer;
	}

	/** @see MailerGenericBuilder#getFailoverPolicy() */
	@Override
	@Nullable
	public FailoverPolicy getFailoverPolicy() {
		return failoverPolicy;
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
		customizedSessionSetting(Property.PROXY_HOST, proxyHost);
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withProxyPort(Integer)
	 */
	@Override
	public T withProxyPort(@Nullable final Integer proxyPort) {
		this.proxyPort = proxyPort;
		customizedSessionSetting(Property.PROXY_PORT, proxyPort);
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withProxyUsername(String)
	 */
	@Override
	public T withProxyUsername(@Nullable final String proxyUsername) {
		this.proxyUsername = proxyUsername;
		customizedSessionSetting(Property.PROXY_USERNAME, proxyUsername);
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withProxyPassword(String)
	 */
	@Override
	public T withProxyPassword(@Nullable final String proxyPassword) {
		this.proxyPassword = proxyPassword;
		customizedSessionSetting(Property.PROXY_PASSWORD, proxyPassword);
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withProxyBridgePort(Integer)
	 */
	@Override
	public T withProxyBridgePort(@NotNull final Integer proxyBridgePort) {
		this.proxyBridgePort = proxyBridgePort;
		customizedSessionSetting(Property.PROXY_SOCKS5BRIDGE_PORT, proxyBridgePort);
		return (T) this;
	}

	/** Internal CLI scalar adaptation; it preserves earlier choices in the same invocation. */
	@Override public T withDebugLogging(final Boolean enabled) {
		return withDebugConfig(debugConfig.toBuilder().withEnabled(requireNonNull(enabled, "enabled")).build());
	}
	@Override public T withDebugOutput(final SessionDebugOutput output) {
		return withDebugConfig(debugConfig.toBuilder().withOutput(output).build());
	}
	@Override public T withMailSendTelemetryEnabled(final boolean enabled) {
		return withTelemetryConfig(telemetryConfig.toBuilder().withEnabled(enabled).build());
	}
	@Override public T resetMailSendTelemetryEnabled() {
		return withTelemetryConfig(telemetryConfig.toBuilder().resetEnabled().build());
	}
	@Override public T withMailSendTelemetrySmtpEndpointAttributes(final boolean enabled) {
		return withTelemetryConfig(telemetryConfig.toBuilder().withSmtpEndpointAttributes(enabled).build());
	}
	@Override public T resetMailSendTelemetrySmtpEndpointAttributes() {
		return withTelemetryConfig(telemetryConfig.toBuilder().resetSmtpEndpointAttributes().build());
	}
	@Override public T withSessionTimeout(final Integer timeout) { return withSessionTimeout(Duration.ofMillis(timeout)); }
	@Override public T withConnectTimeout(final Integer timeout) {
		connectTimeout = Duration.ofMillis(requireNonNull(timeout, "connectTimeout"));
		customizedSessionSetting(Property.DEFAULT_CONNECT_TIMEOUT_MILLIS, timeout);
		return (T) this;
	}
	@Override public T withReadTimeout(final Integer timeout) {
		readTimeout = Duration.ofMillis(requireNonNull(timeout, "readTimeout"));
		customizedSessionSetting(Property.DEFAULT_READ_TIMEOUT_MILLIS, timeout);
		return (T) this;
	}
	@Override public T withWriteTimeout(final Integer timeout) {
		writeTimeout = Duration.ofMillis(requireNonNull(timeout, "writeTimeout"));
		customizedSessionSetting(Property.DEFAULT_WRITE_TIMEOUT_MILLIS, timeout);
		return (T) this;
	}
	@Override public T resetConnectTimeout() { return withConnectTimeout(DurationMillis.toInt(DEFAULT_SESSION_TIMEOUT, "connectTimeout")); }
	@Override public T resetReadTimeout() { return withReadTimeout(DurationMillis.toInt(DEFAULT_SESSION_TIMEOUT, "readTimeout")); }
	@Override public T resetWriteTimeout() { return withWriteTimeout(DurationMillis.toInt(DEFAULT_SESSION_TIMEOUT, "writeTimeout")); }
	@Override public T withThreadPoolKeepAliveTime(final Integer keepAliveTime) { return withThreadPoolKeepAliveTime(Duration.ofMillis(keepAliveTime)); }
	@Override public T withAsyncQueueWaitTimeoutMillis(final int waitTimeoutMillis) { return withAsyncQueueWaitTimeout(Duration.ofMillis(waitTimeoutMillis)); }
	@Override public T withConnectionPoolClaimTimeoutMillis(final Integer timeoutMillis) { return withConnectionPoolClaimTimeout(Duration.ofMillis(timeoutMillis)); }
	@Override public T resetConnectionPoolClaimTimeoutMillis() { return resetConnectionPoolClaimTimeout(); }
	@Override public T withConnectionPoolExpireAfterMillis(final Integer expireAfterMillis) { return withConnectionPoolExpireAfter(Duration.ofMillis(expireAfterMillis)); }
	@Override public T resetConnectionPoolExpireAfterMillis() { return resetConnectionPoolExpireAfter(); }
	@Override public T withConnectionPoolExpireAfterCreationMillis(final Integer expireAfterCreationMillis) {
		return withConnectionPoolExpireAfterCreation(Duration.ofMillis(expireAfterCreationMillis));
	}
	@Override public T clearConnectionPoolExpireAfterCreationMillis() { return clearConnectionPoolExpireAfterCreation(); }
	@Override public T clearEmailDefaults() { return useConfiguredEmailDefaults(); }

	/** @see MailerGenericBuilder#withDebugConfig(SessionDebugConfig) */
	@Override public T withDebugConfig(@NotNull final SessionDebugConfig debugConfig) {
		this.debugConfig = requireNonNull(debugConfig, "debugConfig");
		return (T) this;
	}

	/** @see MailerGenericBuilder#getDebugConfig() */
	@Override @NotNull public SessionDebugConfig getDebugConfig() { return debugConfig; }

	/** @see MailerGenericBuilder#useConfiguredDebugConfig() */
	@Override public T useConfiguredDebugConfig() { return withDebugConfig(SessionDebugConfig.builder().build()); }

	private SessionDebugConfig resolveDebugConfig(final SessionDebugConfig baseline) {
		return ResolvedMailerConfiguration.resolveDebugConfig(config, debugConfig, baseline, this instanceof MailerFromSessionBuilderImpl);
	}

	/**
	 * @see org.simplejavamail.api.mailer.config.ValidationConfig.Builder#withClientValidationWarningsOnly(boolean)
	 */
	@Override
	public T disablingAllClientValidation(@NotNull final Boolean clientValidationWarningsOnly) {
		this.clientValidationWarningsOnly = MailerConfigurationResolver.resolve(config, ConfigKeys.DISABLE_ALL_CLIENTVALIDATION,
				ConfigurationChoice.set(clientValidationWarningsOnly), DEFAULT_DISABLE_ALL_CLIENTVALIDATION, "disablingAllClientValidation");
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withSessionTimeout(Duration)
	 */
	@Override
	public T withSessionTimeout(@NotNull final Duration sessionTimeout) {
		final int millis = DurationMillis.toInt(sessionTimeout, "sessionTimeout");
		connectTimeout = sessionTimeout;
		readTimeout = sessionTimeout;
		writeTimeout = sessionTimeout;
		for (final Property property : socketTimeoutProperties()) {
			customizedSessionSetting(property, millis);
		}
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
		this.legacySmtpContentSupportEnabled = MailerConfigurationResolver.resolve(config, ConfigKeys.SMTP_LEGACY_CONTENT_SUPPORT,
				ConfigurationChoice.set(enabled), DEFAULT_LEGACY_SMTP_CONTENT_SUPPORT, "withLegacySmtpContentSupport");
		return (T) this;
	}

	/** @see MailerGenericBuilder#resetLegacySmtpContentSupport() */
	@Override
	public T resetLegacySmtpContentSupport() {
		return withLegacySmtpContentSupport(DEFAULT_LEGACY_SMTP_CONTENT_SUPPORT);
	}

	/**
	 * @see MailerGenericBuilder#withEmailValidator(EmailValidator)
	 */
	@Override
	public T withEmailValidator(@NotNull final EmailValidator emailEmailValidator) {
		this.emailValidator = MailerConfigurationResolver.select(ConfigurationChoice.set(emailEmailValidator), null,
				null, "emailValidator", "withEmailValidator");
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
		this.threadPoolSize = MailerConfigurationResolver.resolve(config, ConfigKeys.DEFAULT_POOL_SIZE,
				ConfigurationChoice.set(threadPoolSize), DEFAULT_POOL_SIZE, "withThreadPoolSize");
		threadPoolSizeExplicit = true;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withThreadPoolKeepAliveTime(Duration)
	 */
	@Override
	public T withThreadPoolKeepAliveTime(@NotNull final Duration threadPoolKeepAliveTime) {
		DurationMillis.toInt(threadPoolKeepAliveTime, "threadPoolKeepAliveTime");
		this.threadPoolKeepAliveTime = MailerConfigurationResolver.resolve(config, ConfigKeys.THREAD_POOL_KEEP_ALIVE_TIME,
				ConfigurationChoice.set(threadPoolKeepAliveTime), DEFAULT_POOL_KEEP_ALIVE_TIME, "withThreadPoolKeepAliveTime");
		threadPoolKeepAliveTimeExplicit = true;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withClusterKey(UUID)
	 */
	@Override
	public T withClusterKey(@NotNull final UUID clusterKey) {
		this.clusterKey = resolveClusterKey(requireNonNull(clusterKey, "clusterKey"));
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withConnectionPoolCoreSize(Integer)
	 */
	@Override
	public T withConnectionPoolCoreSize(@NotNull final Integer connectionPoolCoreSize) {
		this.connectionPoolCoreSize = MailerConfigurationResolver.resolve(config, ConfigKeys.DEFAULT_CONNECTIONPOOL_CORE_SIZE,
				ConfigurationChoice.set(connectionPoolCoreSize), DEFAULT_CONNECTIONPOOL_CORE_SIZE, "withConnectionPoolCoreSize");
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withConnectionPoolMaxSize(Integer)
	 */
	@Override
	public T withConnectionPoolMaxSize(@NotNull final Integer connectionPoolMaxSize) {
		this.connectionPoolMaxSize = MailerConfigurationResolver.resolve(config, ConfigKeys.DEFAULT_CONNECTIONPOOL_MAX_SIZE,
				ConfigurationChoice.set(connectionPoolMaxSize), DEFAULT_CONNECTIONPOOL_MAX_SIZE, "withConnectionPoolMaxSize");
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withConnectionPoolClaimTimeout(Duration)
	 */
	@Override
	public T withConnectionPoolClaimTimeout(@NotNull final Duration connectionPoolClaimTimeout) {
		DurationMillis.toInt(connectionPoolClaimTimeout, "connectionPoolClaimTimeout");
		this.connectionPoolClaimTimeout = MailerConfigurationResolver.resolve(config, ConfigKeys.CONNECTION_POOL_CLAIM_TIMEOUT,
				ConfigurationChoice.set(connectionPoolClaimTimeout), DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT, "withConnectionPoolClaimTimeout");
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withConnectionPoolExpireAfter(Duration)
	 */
	@Override
	public T withConnectionPoolExpireAfter(@NotNull final Duration connectionPoolExpireAfter) {
		DurationMillis.toInt(connectionPoolExpireAfter, "connectionPoolExpireAfter");
		this.connectionPoolExpireAfter = MailerConfigurationResolver.resolve(config, ConfigKeys.CONNECTION_POOL_EXPIRE_AFTER,
				ConfigurationChoice.set(connectionPoolExpireAfter), DEFAULT_CONNECTIONPOOL_EXPIREAFTER, "withConnectionPoolExpireAfter");
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withConnectionPoolExpireAfterCreation(Duration)
	 */
	@Override
	public T withConnectionPoolExpireAfterCreation(@NotNull final Duration connectionPoolExpireAfterCreation) {
		DurationMillis.toInt(connectionPoolExpireAfterCreation, "connectionPoolExpireAfterCreation");
		this.connectionPoolExpireAfterCreation = MailerConfigurationResolver.resolve(config, ConfigKeys.CONNECTION_POOL_EXPIRE_AFTER_CREATION,
				ConfigurationChoice.set(connectionPoolExpireAfterCreation), DEFAULT_CONNECTIONPOOL_EXPIREAFTERCREATION, "withConnectionPoolExpireAfterCreation");
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withConnectionPoolLoadBalancingStrategy(LoadBalancingStrategy)
	 */
	@Override
	public T withConnectionPoolLoadBalancingStrategy(@NotNull final LoadBalancingStrategy loadBalancingStrategy) {
		this.connectionPoolLoadBalancingStrategy = MailerConfigurationResolver.resolve(config, ConfigKeys.DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY,
				ConfigurationChoice.set(loadBalancingStrategy),
				LoadBalancingStrategy.valueOf(DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY), "withConnectionPoolLoadBalancingStrategy");
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withTransportModeLoggingOnly(Boolean)
	 */
	@Override
	public T withTransportModeLoggingOnly(@NotNull final Boolean transportModeLoggingOnly) {
		this.transportModeLoggingOnly = MailerConfigurationResolver.resolve(config, ConfigKeys.TRANSPORT_MODE_LOGGING_ONLY,
				ConfigurationChoice.set(transportModeLoggingOnly), DEFAULT_TRANSPORT_MODE_LOGGING_ONLY, "withTransportModeLoggingOnly");
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#trustingSSLHosts(String...)
	 */
	@Override
	public T trustingSSLHosts(String... sslHostsToTrust) {
		this.sslHostsToTrust = new ArrayList<>(Arrays.asList(sslHostsToTrust));
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
			if (property.getKey() instanceof String) {
				final String name = (String) property.getKey();
				this.properties.put(name, resolveRawProperty(name, ConfigurationChoice.set(property.getValue())));
				explicitRawSessionSettings.put(name, property.getValue());
			} else {
				this.properties.put(property.getKey(), property.getValue());
			}
		}
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withProperties(Map)
	 */
	@Override
	public T withProperties(@NotNull final Map<String, String> properties) {
		for (Map.Entry<String, String> property : properties.entrySet()) {
			this.properties.put(property.getKey(), resolveRawProperty(property.getKey(), ConfigurationChoice.set(property.getValue())));
			explicitRawSessionSettings.put(property.getKey(), property.getValue());
		}
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withProperty(String, Object)
	 */
	@Override
	public T withProperty(@NotNull final String propertyName, @Nullable final Object propertyValue) {
		requireNonNull(propertyName, "propertyName");
		final String text = propertyValue == null ? null : requireNonNull(propertyValue.toString(), "propertyValue.toString()");
		final Object selected = resolveRawProperty(propertyName, MailerConfigurationResolver.choice(text, text == null));
		if (selected == null) {
			this.properties.remove(propertyName);
		} else {
			this.properties.put(propertyName, selected);
		}
		explicitRawSessionSettings.put(propertyName, text);
		return (T) this;
	}

	@Nullable
	private Object resolveRawProperty(final String name, final ConfigurationChoice<Object> choice) {
		final Map<String, String> captured = MailerConfigurationResolver.configured(config, ConfigKeys.EXTRA_PROPERTIES, null);
		return MailerConfigurationResolver.select(choice, captured == null ? null : captured.get(name), null,
				"simplejavamail.extraproperties." + name, "withProperty/withProperties");
	}

	/**
	 * @see MailerGenericBuilder#withCustomMailer(CustomMailer)
	 */
	@Override
	public T withCustomMailer(@NotNull CustomMailer customMailer) {
		this.customMailer = customMailer;
		return (T) this;
	}

	/** @see MailerGenericBuilder#clearCustomMailer() */
	@Override
	public T clearCustomMailer() {
		customMailer = null;
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

	/** @see MailerGenericBuilder#clearOAuth2AccessTokenProvider() */
	@Override
	public T clearOAuth2AccessTokenProvider() {
		oauth2AccessTokenProvider = null;
		return (T) this;
	}



	/** @see MailerGenericBuilder#withTelemetryConfig(MailSendTelemetryConfig) */
	@Override public T withTelemetryConfig(@NotNull final MailSendTelemetryConfig telemetryConfig) {
		this.telemetryConfig = requireNonNull(telemetryConfig, "telemetryConfig");
		return (T) this;
	}

	/** @see MailerGenericBuilder#getTelemetryConfig() */
	@Override @NotNull public MailSendTelemetryConfig getTelemetryConfig() { return telemetryConfig; }

	/** @see MailerGenericBuilder#useConfiguredTelemetryConfig() */
	@Override public T useConfiguredTelemetryConfig() { return withTelemetryConfig(MailSendTelemetryConfig.builder().build()); }

	/** Resolves only captured values, retaining the application-owned integration. */
	MailSendTelemetryConfig resolveTelemetryConfig() {
		final MailSendTelemetryConfig.Builder resolved = telemetryConfig.toBuilder()
				.withEnabled(MailerConfigurationResolver.resolve(config, ConfigKeys.TELEMETRY_ENABLED,
						telemetryConfig.getEnabled(), DEFAULT_MAIL_SEND_TELEMETRY_ENABLED))
				.withSmtpEndpointAttributes(MailerConfigurationResolver.resolve(config, ConfigKeys.TELEMETRY_SMTP_ENDPOINT_ATTRIBUTES,
						telemetryConfig.getSmtpEndpointAttributes(), DEFAULT_MAIL_SEND_TELEMETRY_SMTP_ENDPOINT_ATTRIBUTES));
		return resolved.build();
	}

	/** @see MailerGenericBuilder#withSendHandlers(MailSendHandlers) */
	@Override public T withSendHandlers(@NotNull final MailSendHandlers handlers) {
		sendHandlers = requireNonNull(handlers, "sendHandlers"); return (T) this;
	}
	/** @see MailerGenericBuilder#getSendHandlers() */
	@Override @Nullable public MailSendHandlers getSendHandlers() { return sendHandlers; }
	/** @see MailerGenericBuilder#clearSendHandlers() */
	@Override public T clearSendHandlers() { sendHandlers = null; return (T) this; }
	/** @see MailerGenericBuilder#withSendHandlersFactory(Function) */
	@Override public T withSendHandlersFactory(@NotNull final Function<UUID, MailSendHandlers> factory) {
		sendHandlersFactory = requireNonNull(factory, "sendHandlersFactory"); return (T) this;
	}
	/** @see MailerGenericBuilder#getSendHandlersFactory() */
	@Override @Nullable public Function<UUID, MailSendHandlers> getSendHandlersFactory() { return sendHandlersFactory; }
	/** @see MailerGenericBuilder#clearSendHandlersFactory() */
	@Override public T clearSendHandlersFactory() { sendHandlersFactory = null; return (T) this; }
	/** @see MailerGenericBuilder#withSendIdGenerator(Supplier) */
	@Override public T withSendIdGenerator(@NotNull final Supplier<UUID> generator) {
		sendIdGenerator = requireNonNull(generator, "sendIdGenerator"); return (T) this;
	}
	/** @see MailerGenericBuilder#getSendIdGenerator() */
	@Override @NotNull public Supplier<UUID> getSendIdGenerator() { return sendIdGenerator; }
	/** @see MailerGenericBuilder#resetSendIdGenerator() */
	@Override public T resetSendIdGenerator() { sendIdGenerator = UUID::randomUUID; return (T) this; }

	/**
	 * @see MailerGenericBuilder#withMailSendTimeout(Duration)
	 */
	@Override
	public T withMailSendTimeout(@NotNull final Duration timeout) {
		MailSendControl.positiveTimeoutNanos(timeout);
		mailSendTimeout = MailerConfigurationResolver.resolve(config, ConfigKeys.DEFAULT_MAIL_SEND_TIMEOUT,
				ConfigurationChoice.set(timeout), DEFAULT_MAIL_SEND_TIMEOUT, "withMailSendTimeout");
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#resetMailSendTimeout()
	 */
	@Override
	public T resetMailSendTimeout() {
		mailSendTimeout = MailerConfigurationResolver.resolve(config, ConfigKeys.DEFAULT_MAIL_SEND_TIMEOUT,
				ConfigurationChoice.remove(), DEFAULT_MAIL_SEND_TIMEOUT, "resetMailSendTimeout");
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
	 * @see org.simplejavamail.api.mailer.config.ValidationConfig.Builder#resetClientValidationWarningsOnly()
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
		return withSessionTimeout(DEFAULT_SESSION_TIMEOUT);
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

	private int resolvedWorkerCount() {
		return threadPoolSizeExplicit || ModuleLoader.batchModuleAvailable() ? threadPoolSize : 1;
	}

	private Duration resolvedWorkerKeepAlive() {
		return threadPoolKeepAliveTimeExplicit || ModuleLoader.batchModuleAvailable() ? threadPoolKeepAliveTime : Duration.ZERO;
	}

	@Nullable private Duration configuredDuration(final ConfigKey<Duration> property, @Nullable final Duration fallback) {
		return MailerConfigurationResolver.resolve(config, property, null, fallback);
	}

	/**
	 * @see MailerGenericBuilder#withAsyncQueueCapacity(int)
	 */
	@Override
	public T withAsyncQueueCapacity(final int capacity) {
		asyncQueueConfig = new AsyncQueueConfig(capacity, asyncQueueConfig.getOverflowPolicy(), asyncQueueConfig.getWaitTimeout());
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withAsyncQueueOverflowPolicy(AsyncQueueOverflowPolicy)
	 */
	@Override
	public T withAsyncQueueOverflowPolicy(@NotNull final AsyncQueueOverflowPolicy overflowPolicy) {
		asyncQueueConfig = new AsyncQueueConfig(asyncQueueConfig.getCapacity(), overflowPolicy, asyncQueueConfig.getWaitTimeout());
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withAsyncQueueWaitTimeout(Duration)
	 */
	@Override
	public T withAsyncQueueWaitTimeout(final Duration waitTimeout) {
		asyncQueueConfig = new AsyncQueueConfig(asyncQueueConfig.getCapacity(), asyncQueueConfig.getOverflowPolicy(), waitTimeout);
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

	/** @see MailerGenericBuilder#getAsyncQueueConfig() */
	@Override @NotNull public AsyncQueueConfig getAsyncQueueConfig() { return asyncQueueConfig; }



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
	 * @see MailerGenericBuilder#resetConnectionPoolClaimTimeout()
	 */
	@Override
	public T resetConnectionPoolClaimTimeout() {
		return this.withConnectionPoolClaimTimeout(DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT);
	}

	/**
	 * @see MailerGenericBuilder#resetConnectionPoolExpireAfter()
	 */
	@Override
	public T resetConnectionPoolExpireAfter() {
		return this.withConnectionPoolExpireAfter(DEFAULT_CONNECTIONPOOL_EXPIREAFTER);
	}

	/**
	 * @see MailerGenericBuilder#clearConnectionPoolExpireAfterCreation()
	 */
	@Override
	public T clearConnectionPoolExpireAfterCreation() {
		this.connectionPoolExpireAfterCreation = MailerConfigurationResolver.resolve(config, ConfigKeys.CONNECTION_POOL_EXPIRE_AFTER_CREATION,
				ConfigurationChoice.remove(), DEFAULT_CONNECTIONPOOL_EXPIREAFTERCREATION, "clearConnectionPoolExpireAfterCreation");
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
		this.emailValidator = MailerConfigurationResolver.select(ConfigurationChoice.remove(), null, null, "emailValidator", "clearEmailValidator");
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#useConfiguredEmailDefaults()
	 */
	@Override
	public T useConfiguredEmailDefaults() {
		this.emailDefaults = null;
		return (T) this;
	}

	/** @see MailerGenericBuilder#withoutEmailDefaults() */
	@Override public T withoutEmailDefaults() {
		return withEmailDefaults(new EmailStartingBuilderImpl(config).startingBlank().buildEmail());
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
		for (Object key : properties.keySet()) {
			if (key instanceof String) { explicitRawSessionSettings.put((String) key, null); }
		}
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
	 * @see OperationalConfig#isClientValidationWarningsOnly()
	 */
	@Override
	public boolean isClientValidationWarningsOnly() {
		return clientValidationWarningsOnly;
	}

	/**
	 * @see MailerGenericBuilder#getSessionTimeout()
	 */
	@Override
	@Nullable
	public Duration getSessionTimeout() {
		return connectTimeout.equals(readTimeout) && connectTimeout.equals(writeTimeout) ? connectTimeout : null;
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
	public Duration getThreadPoolKeepAliveTime() {
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
	 * @see MailerGenericBuilder#getConnectionPoolClaimTimeout()
	 */
	@Override
	@NotNull
	public Duration getConnectionPoolClaimTimeout() {
		return connectionPoolClaimTimeout;
	}

	/**
	 * @see MailerGenericBuilder#getConnectionPoolExpireAfter()
	 */
	@Override
	@NotNull
	public Duration getConnectionPoolExpireAfter() {
		return connectionPoolExpireAfter;
	}

	/**
	 * @see MailerGenericBuilder#getConnectionPoolExpireAfterCreation()
	 */
	@Override
	@Nullable
	public Duration getConnectionPoolExpireAfterCreation() {
		return connectionPoolExpireAfterCreation;
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
		return Collections.unmodifiableList(new ArrayList<>(sslHostsToTrust));
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
		final Properties copy = new Properties();
		copy.putAll(properties);
		return copy;
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

	/** Captures observer registrations and binds the already-resolved telemetry permission; no progress dispatcher is started here. */
	MailSendObserverNotifier buildObserverNotifier() {
		return MailSendObserverNotifier.builder()
			.handlers(sendHandlers)
			.handlersFactory(sendHandlersFactory)
			.idGenerator(sendIdGenerator)
			.telemetry(MailSendTelemetryRecorder.bind(resolveTelemetryConfig().getIntegration(), Boolean.TRUE.equals(resolveTelemetryConfig().getEnabled()), Boolean.TRUE.equals(resolveTelemetryConfig().getSmtpEndpointAttributes())))
			.loggingOnly(transportModeLoggingOnly)
			.build();
	}

    /** @see MailerGenericBuilder#withConnectionPoolConfig(ConnectionPoolConfig) */
    @Override public T withConnectionPoolConfig(@NotNull final ConnectionPoolConfig choices) {
        requireNonNull(choices, "connectionPoolConfig");
        connectionPoolCoreSize = MailerConfigurationResolver.resolve(config, ConfigKeys.DEFAULT_CONNECTIONPOOL_CORE_SIZE,
                choices.getCoreSize(), DEFAULT_CONNECTIONPOOL_CORE_SIZE);
        connectionPoolMaxSize = MailerConfigurationResolver.resolve(config, ConfigKeys.DEFAULT_CONNECTIONPOOL_MAX_SIZE,
                choices.getMaxSize(), DEFAULT_CONNECTIONPOOL_MAX_SIZE);
        connectionPoolClaimTimeout = durationChoice(choices.getClaimTimeout(), ConfigKeys.CONNECTION_POOL_CLAIM_TIMEOUT, DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT);
        connectionPoolExpireAfter = durationChoice(choices.getExpireAfter(), ConfigKeys.CONNECTION_POOL_EXPIRE_AFTER, DEFAULT_CONNECTIONPOOL_EXPIREAFTER);
        connectionPoolExpireAfterCreation = MailerConfigurationResolver.resolve(config, ConfigKeys.CONNECTION_POOL_EXPIRE_AFTER_CREATION,
                MailerConfigurationResolver.choice(choices.getExpireAfterCreation(), choices.isExpireAfterCreationCleared()),
                DEFAULT_CONNECTIONPOOL_EXPIREAFTERCREATION, "ConnectionPoolConfig.withExpireAfterCreation/clearExpireAfterCreation");
        clusterKey = resolveClusterKey(choices.getClusterKey());
        connectionPoolLoadBalancingStrategy = MailerConfigurationResolver.resolve(config, ConfigKeys.DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY,
                choices.getLoadBalancingStrategy(), LoadBalancingStrategy.valueOf(DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY));
        connectionPoolConfig = choices;
        return (T) this;
    }
    /** @see MailerGenericBuilder#getConnectionPoolConfig() */
    @Override @NotNull public ConnectionPoolConfig getConnectionPoolConfig() { return connectionPoolConfig; }
    /** @see MailerGenericBuilder#useConfiguredConnectionPoolConfig() */
    @Override public T useConfiguredConnectionPoolConfig() { return withConnectionPoolConfig(ConnectionPoolConfig.builder().build()); }

    /** @see MailerGenericBuilder#withAsyncExecutionConfig(AsyncExecutionConfig) */
    @Override public T withAsyncExecutionConfig(@NotNull final AsyncExecutionConfig choices) {
        requireNonNull(choices, "asyncExecutionConfig");
        threadPoolSize = MailerConfigurationResolver.resolve(config, ConfigKeys.DEFAULT_POOL_SIZE,
                choices.getThreadPoolSize(), DEFAULT_POOL_SIZE);
        threadPoolKeepAliveTime = durationChoice(choices.getKeepAliveTime(), ConfigKeys.THREAD_POOL_KEEP_ALIVE_TIME, DEFAULT_POOL_KEEP_ALIVE_TIME);
        threadPoolSizeExplicit = choices.getThreadPoolSize() != null || config.hasProperty(ConfigKeys.DEFAULT_POOL_SIZE);
        threadPoolKeepAliveTimeExplicit = choices.getKeepAliveTime() != null || config.hasProperty(ConfigKeys.THREAD_POOL_KEEP_ALIVE_TIME);
        executorService = choices.getExecutorService();
        final AsyncQueueOverflowPolicy suppliedPolicy = choices.getRejectWhenFull() == null ? null
                : choices.getRejectWhenFull() ? AsyncQueueOverflowPolicy.REJECT : AsyncQueueOverflowPolicy.WAIT_FOR_CAPACITY;
        final AsyncQueueOverflowPolicy policy = MailerConfigurationResolver.resolve(config,
                ConfigKeys.DEFAULT_ASYNC_QUEUE_OVERFLOW_POLICY, suppliedPolicy, DEFAULT_ASYNC_QUEUE_OVERFLOW_POLICY);
        asyncQueueConfig = new AsyncQueueConfig(MailerConfigurationResolver.resolve(config, ConfigKeys.DEFAULT_ASYNC_QUEUE_CAPACITY,
                choices.getQueueCapacity(), DEFAULT_ASYNC_QUEUE_CAPACITY),
                policy, durationChoice(choices.getCapacityWait(), ConfigKeys.ASYNC_QUEUE_WAIT_TIMEOUT, DEFAULT_ASYNC_QUEUE_WAIT_TIMEOUT));
        asyncExecutionConfig = choices;
        return (T) this;
    }
    /** @see MailerGenericBuilder#getAsyncExecutionConfig() */
    @Override @NotNull public AsyncExecutionConfig getAsyncExecutionConfig() { return asyncExecutionConfig; }
    /** @see MailerGenericBuilder#useConfiguredAsyncExecutionConfig() */
    @Override public T useConfiguredAsyncExecutionConfig() { return withAsyncExecutionConfig(AsyncExecutionConfig.builder().build()); }

    /** @see MailerGenericBuilder#withProxyConfig(SocksProxyConfig) */
    @Override public T withProxyConfig(@NotNull final SocksProxyConfig choices) {
        requireNonNull(choices, "proxyConfig");
        for (Property property : List.of(Property.PROXY_HOST, Property.PROXY_PORT, Property.PROXY_USERNAME, Property.PROXY_PASSWORD,
                Property.PROXY_SOCKS5BRIDGE_PORT)) { explicitSessionSettings.remove(property); }
        proxyHost = MailerConfigurationResolver.resolve(config, ConfigKeys.PROXY_HOST,
                MailerConfigurationResolver.choice(choices.getHost(), choices.isHostCleared()), null, "SocksProxyConfig.withHost/clearHost");
        proxyPort = MailerConfigurationResolver.resolve(config, ConfigKeys.PROXY_PORT,
                MailerConfigurationResolver.choice(choices.getPort(), choices.isPortCleared()), DEFAULT_PROXY_PORT, "SocksProxyConfig.withPort/clearPort");
        proxyUsername = MailerConfigurationResolver.resolve(config, ConfigKeys.PROXY_USERNAME,
                MailerConfigurationResolver.choice(choices.getUsername(), choices.isUsernameCleared()), null, "SocksProxyConfig.withUsername/clearUsername");
        proxyPassword = MailerConfigurationResolver.resolve(config, ConfigKeys.PROXY_PASSWORD,
                MailerConfigurationResolver.choice(choices.getPassword(), choices.isPasswordCleared()), null, "SocksProxyConfig.withPassword/clearPassword");
        proxyBridgePort = MailerConfigurationResolver.resolve(config, ConfigKeys.PROXY_SOCKS5BRIDGE_PORT,
                choices.getBridgePort(), DEFAULT_PROXY_BRIDGE_PORT);
        proxyConfig = choices;
        addProxyDeclarations(explicitSessionSettings);
        return (T) this;
    }
    /** @see MailerGenericBuilder#getProxyConfig() */
    @Override @NotNull public SocksProxyConfig getProxyConfig() { return proxyConfig; }
    /** @see MailerGenericBuilder#useConfiguredProxyConfig() */
    @Override public T useConfiguredProxyConfig() { return withProxyConfig(SocksProxyConfig.builder().build()); }

    /** @see MailerGenericBuilder#withSendingLimitsConfig(SendingLimitsConfig) */
    @Override public T withSendingLimitsConfig(@NotNull final SendingLimitsConfig choices) {
        requireNonNull(choices, "sendingLimitsConfig");
        messageRateLimit = resolveRateLimitChoice(choices.getMessageRateLimit(), choices.isMessageRateLimitCleared(),
                ConfigKeys.SMTP_MESSAGE_RATE_LIMIT, ConfigKeys.SMTP_MESSAGE_RATE_PERIOD);
        recipientRateLimit = resolveRateLimitChoice(choices.getRecipientRateLimit(), choices.isRecipientRateLimitCleared(),
                ConfigKeys.SMTP_RECIPIENT_RATE_LIMIT, ConfigKeys.SMTP_RECIPIENT_RATE_PERIOD);
        rateLimitGroup = MailerConfigurationResolver.resolve(config, ConfigKeys.SMTP_RATE_LIMIT_GROUP,
                MailerConfigurationResolver.choice(valueNullOrEmpty(choices.getRateLimitGroup()) ? null : choices.getRateLimitGroup(), choices.isRateLimitGroupCleared()),
                null, "SendingLimitsConfig.withRateLimitGroup/clearRateLimitGroup");
        if (rateLimitGroup != null) { validateRateLimitGroup(rateLimitGroup); }
        rateLimitBurstsAllowed = MailerConfigurationResolver.resolve(config, ConfigKeys.SMTP_RATE_LIMIT_ALLOW_BURSTS,
                choices.getRateLimitBurstsAllowed(), DEFAULT_RATE_LIMIT_BURSTS_ALLOWED);
        sendingLimitsConfig = choices;
        return (T) this;
    }
    /** @see MailerGenericBuilder#getSendingLimitsConfig() */
    @Override @NotNull public SendingLimitsConfig getSendingLimitsConfig() { return sendingLimitsConfig; }
    /** @see MailerGenericBuilder#useConfiguredSendingLimitsConfig() */
    @Override public T useConfiguredSendingLimitsConfig() { return withSendingLimitsConfig(SendingLimitsConfig.builder().build()); }

    /** @see MailerGenericBuilder#withConnectionConfig(ConnectionConfig) */
    @Override public T withConnectionConfig(@NotNull final ConnectionConfig choices) {
        requireNonNull(choices, "connectionConfig");
        for (Property property : List.of(Property.DEFAULT_SESSION_TIMEOUT_MILLIS, Property.DEFAULT_CONNECT_TIMEOUT_MILLIS,
                Property.DEFAULT_READ_TIMEOUT_MILLIS, Property.DEFAULT_WRITE_TIMEOUT_MILLIS,
                Property.SMTP_LOCAL_ADDRESS, Property.SMTP_LOCAL_PORT, Property.SMTP_CLIENT_HOSTNAME)) {
            explicitSessionSettings.remove(property);
        }
        connectTimeout = socketTimeoutChoice(choices.getConnectTimeout(), ConfigKeys.CONNECT_TIMEOUT);
        readTimeout = socketTimeoutChoice(choices.getReadTimeout(), ConfigKeys.READ_TIMEOUT);
        writeTimeout = socketTimeoutChoice(choices.getWriteTimeout(), ConfigKeys.WRITE_TIMEOUT);
        localBindAddress = MailerConfigurationResolver.resolve(config, ConfigKeys.SMTP_LOCAL_ADDRESS,
                MailerConfigurationResolver.choice(choices.getLocalBindAddress(), choices.isLocalBindAddressCleared()), null,
                "ConnectionConfig.withLocalBindAddress/clearLocalBindAddress");
        localBindPort = MailerConfigurationResolver.resolve(config, ConfigKeys.SMTP_LOCAL_PORT,
                MailerConfigurationResolver.choice(choices.getLocalBindPort(), choices.isLocalBindPortCleared()), null,
                "ConnectionConfig.withLocalBindPort/clearLocalBindPort");
        smtpClientHostname = MailerConfigurationResolver.resolve(config, ConfigKeys.SMTP_CLIENT_HOSTNAME,
                MailerConfigurationResolver.choice(choices.getSmtpClientHostname(), choices.isSmtpClientHostnameCleared()), null,
                "ConnectionConfig.withSmtpClientHostname/clearSmtpClientHostname");
        legacySmtpContentSupportEnabled = MailerConfigurationResolver.resolve(config, ConfigKeys.SMTP_LEGACY_CONTENT_SUPPORT,
                choices.getLegacySmtpContentSupport(), DEFAULT_LEGACY_SMTP_CONTENT_SUPPORT);
        if (choices.getConnectTimeout() != null) { customizedSessionSetting(Property.DEFAULT_CONNECT_TIMEOUT_MILLIS, DurationMillis.toInt(connectTimeout, "connectTimeout")); }
        if (choices.getReadTimeout() != null) { customizedSessionSetting(Property.DEFAULT_READ_TIMEOUT_MILLIS, DurationMillis.toInt(readTimeout, "readTimeout")); }
        if (choices.getWriteTimeout() != null) { customizedSessionSetting(Property.DEFAULT_WRITE_TIMEOUT_MILLIS, DurationMillis.toInt(writeTimeout, "writeTimeout")); }
        if (choices.getLocalBindAddress() != null || choices.isLocalBindAddressCleared()) { customizedSessionSetting(Property.SMTP_LOCAL_ADDRESS, localBindAddress); }
        if (choices.getLocalBindPort() != null || choices.isLocalBindPortCleared()) { customizedSessionSetting(Property.SMTP_LOCAL_PORT, localBindPort); }
        if (choices.getSmtpClientHostname() != null || choices.isSmtpClientHostnameCleared()) { customizedSessionSetting(Property.SMTP_CLIENT_HOSTNAME, smtpClientHostname); }
        connectionConfig = choices;
        return (T) this;
    }
    /** @see MailerGenericBuilder#getConnectionConfig() */
    @Override @NotNull public ConnectionConfig getConnectionConfig() { return connectionConfig; }
    /** @see MailerGenericBuilder#useConfiguredConnectionConfig() */
    @Override public T useConfiguredConnectionConfig() { return withConnectionConfig(ConnectionConfig.builder().build()); }

    /** @see MailerGenericBuilder#withTlsConfig(TlsConfig) */
    @Override public T withTlsConfig(@NotNull final TlsConfig choices) {
        requireNonNull(choices, "tlsConfig");
        validateTlsConstructionScope(choices);
        for (Property property : List.of(Property.DEFAULT_TRUSTED_HOSTS, Property.DEFAULT_TRUST_ALL_HOSTS, Property.DEFAULT_VERIFY_SERVER_IDENTITY,
                Property.OPPORTUNISTIC_TLS, Property.CUSTOM_SSLFACTORY_CLASS)) { explicitSessionSettings.remove(property); }
        final String configuredHosts = MailerConfigurationResolver.configured(config, ConfigKeys.DEFAULT_TRUSTED_HOSTS, null);
        final List<String> capturedHosts = configuredHosts == null ? null : Arrays.asList(configuredHosts.split(";"));
        sslHostsToTrust = new ArrayList<>(MailerConfigurationResolver.select(ConfigurationChoice.ofNullable(choices.getSslHostsToTrust()),
                capturedHosts, Collections.emptyList(), ConfigKeys.DEFAULT_TRUSTED_HOSTS.key(), "TlsConfig.trustingSSLHosts"));
        trustAllSSLHost = MailerConfigurationResolver.resolve(config, ConfigKeys.DEFAULT_TRUST_ALL_HOSTS,
                choices.getTrustAllHosts(), DEFAULT_TRUST_ALL_HOSTS);
        verifyingServerIdentity = MailerConfigurationResolver.resolve(config, ConfigKeys.DEFAULT_VERIFY_SERVER_IDENTITY,
                choices.getVerifyingServerIdentity(), DEFAULT_VERIFY_SERVER_IDENTITY);
        if (choices.getSslHostsToTrust() != null) { customizedSessionSetting(Property.DEFAULT_TRUSTED_HOSTS, String.join(" ", sslHostsToTrust)); }
        if (choices.getTrustAllHosts() != null) { customizedSessionSetting(Property.DEFAULT_TRUST_ALL_HOSTS, trustAllSSLHost); }
        if (choices.getVerifyingServerIdentity() != null) { customizedSessionSetting(Property.DEFAULT_VERIFY_SERVER_IDENTITY, verifyingServerIdentity); }
        applyRegularTlsChoices(choices);
        tlsConfig = choices;
        return (T) this;
    }
    /** Custom factory/opportunistic construction remains unavailable for caller-owned Sessions. */
    void validateTlsConstructionScope(final TlsConfig choices) {
        if (choices.getCustomSSLFactoryClass() != null || choices.isCustomSSLFactoryClassCleared()
                || choices.getCustomSSLFactoryInstance() != null || choices.isCustomSSLFactoryInstanceCleared() || choices.getOpportunisticTLS() != null) {
            throw new IllegalArgumentException("Configure custom SSL factories and opportunistic TLS on the supplied Session, or use mailerBuilder() without a Session");
        }
    }
    void applyRegularTlsChoices(final TlsConfig choices) { }
    /** @see MailerGenericBuilder#getTlsConfig() */
    @Override @NotNull public TlsConfig getTlsConfig() { return tlsConfig; }
    /** @see MailerGenericBuilder#useConfiguredTlsConfig() */
    @Override public T useConfiguredTlsConfig() { return withTlsConfig(TlsConfig.builder().build()); }

    /** @see MailerGenericBuilder#withValidationConfig(ValidationConfig) */
    @Override public T withValidationConfig(@NotNull final ValidationConfig choices) {
        requireNonNull(choices, "validationConfig");
        clientValidationWarningsOnly = MailerConfigurationResolver.resolve(config, ConfigKeys.DISABLE_ALL_CLIENTVALIDATION,
                choices.getClientValidationWarningsOnly(), DEFAULT_DISABLE_ALL_CLIENTVALIDATION);
        emailValidator = MailerConfigurationResolver.select(MailerConfigurationResolver.choice(choices.getEmailValidator(), choices.isEmailValidatorCleared()),
                null, choices.isEmailValidatorCleared() || choices.getEmailValidator() != null ? null : JMail.strictValidator(),
                "emailValidator", "ValidationConfig.withEmailValidator/clearEmailValidator");
        maximumEmailSize = choices.getMaximumEmailSize();
        validationConfig = choices;
        return (T) this;
    }
    /** @see MailerGenericBuilder#getValidationConfig() */
    @Override @NotNull public ValidationConfig getValidationConfig() { return validationConfig; }
    /** @see MailerGenericBuilder#useConfiguredValidationConfig() */
    @Override public T useConfiguredValidationConfig() { return withValidationConfig(ValidationConfig.builder().build()); }

    @Nullable private Duration durationChoice(@Nullable final Duration supplied, final ConfigKey<Duration> property, @Nullable final Duration fallback) {
        return MailerConfigurationResolver.resolve(config, property, supplied, fallback);
    }

    private UUID resolveClusterKey(@Nullable final UUID supplied) {
        final boolean captured = config.hasProperty(ConfigKeys.DEFAULT_CONNECTIONPOOL_CLUSTER_KEY);
        return MailerConfigurationResolver.select(ConfigurationChoice.ofNullable(supplied), captured ? configuredClusterKey : null,
                captured ? null : configuredClusterKey, ConfigKeys.DEFAULT_CONNECTIONPOOL_CLUSTER_KEY.key(), "ConnectionPoolConfig.withClusterKey");
    }

    @Nullable
    private SendingRateLimit resolveRateLimitChoice(@Nullable final SendingRateLimit supplied, final boolean removed,
            final ConfigKey<Integer> count, final ConfigKey<Duration> period) {
        return MailerConfigurationResolver.select(MailerConfigurationResolver.choice(supplied, removed), resolveRateLimit(config, count, period),
                null, count.key() + "/" + period.key(), "SendingLimitsConfig.withRateLimit/clearRateLimit");
    }
}
