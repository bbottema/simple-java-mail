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
	private boolean ownsFailoverMailer;
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
		this.failoverName = config.get(ConfigKeys.FAILOVER_MAILER);
		this.failoverPolicy = ConfiguredFailover.policy(config);
		this.messageRateLimit = resolveRateLimit(config, ConfigKeys.SMTP_MESSAGE_RATE_LIMIT, ConfigKeys.SMTP_MESSAGE_RATE_PERIOD);
		this.recipientRateLimit = resolveRateLimit(config, ConfigKeys.SMTP_RECIPIENT_RATE_LIMIT, ConfigKeys.SMTP_RECIPIENT_RATE_PERIOD);
		this.rateLimitGroup = config.get(ConfigKeys.SMTP_RATE_LIMIT_GROUP);
		if (rateLimitGroup != null) {
			withRateLimitGroup(rateLimitGroup);
		}
		this.rateLimitBurstsAllowed = config.valueOrProperty(null, ConfigKeys.SMTP_RATE_LIMIT_ALLOW_BURSTS, DEFAULT_RATE_LIMIT_BURSTS_ALLOWED);
		this.legacySmtpContentSupportEnabled = config.valueOrProperty(null, ConfigKeys.SMTP_LEGACY_CONTENT_SUPPORT, DEFAULT_LEGACY_SMTP_CONTENT_SUPPORT);
		this.mailSendTimeout = config.valueOrProperty(null, ConfigKeys.DEFAULT_MAIL_SEND_TIMEOUT, DEFAULT_MAIL_SEND_TIMEOUT);
		if (mailSendTimeout != null) {
			MailSendControl.positiveTimeoutNanos(mailSendTimeout);
		}
		this.asyncQueueConfig = new AsyncQueueConfig(
				config.valueOrProperty(null, ConfigKeys.DEFAULT_ASYNC_QUEUE_CAPACITY, DEFAULT_ASYNC_QUEUE_CONFIG.getCapacity()),
				config.valueOrProperty(null, ConfigKeys.DEFAULT_ASYNC_QUEUE_OVERFLOW_POLICY, DEFAULT_ASYNC_QUEUE_CONFIG.getOverflowPolicy()),
				configuredDuration(ConfigKeys.ASYNC_QUEUE_WAIT_TIMEOUT, DEFAULT_ASYNC_QUEUE_CONFIG.getWaitTimeout()));
		final Map<String, String> extraProperties = config.get(ConfigKeys.EXTRA_PROPERTIES);
		if (extraProperties != null) {
			this.properties.putAll(extraProperties);
		}
		this.proxyHost = config.get(ConfigKeys.PROXY_HOST);
		this.proxyUsername = config.get(ConfigKeys.PROXY_USERNAME);
		this.proxyPassword = config.get(ConfigKeys.PROXY_PASSWORD);
		this.clusterKey = config.hasProperty(ConfigKeys.DEFAULT_CONNECTIONPOOL_CLUSTER_KEY)
				? UUID.fromString(verifyNonnullOrEmpty(config.get(ConfigKeys.DEFAULT_CONNECTIONPOOL_CLUSTER_KEY)))
				: UUID.randomUUID();

		this.proxyPort =  							verifyNonnullOrEmpty(config.valueOrProperty(null, ConfigKeys.PROXY_PORT, DEFAULT_PROXY_PORT));
		this.proxyBridgePort = 						verifyNonnullOrEmpty(config.valueOrProperty(null, ConfigKeys.PROXY_SOCKS5BRIDGE_PORT, DEFAULT_PROXY_BRIDGE_PORT));
		this.clientValidationWarningsOnly = 			verifyNonnullOrEmpty(config.valueOrProperty(null, ConfigKeys.DISABLE_ALL_CLIENTVALIDATION, DEFAULT_DISABLE_ALL_CLIENTVALIDATION));
		this.connectTimeout = configuredSocketTimeout(ConfigKeys.CONNECT_TIMEOUT);
		this.readTimeout = configuredSocketTimeout(ConfigKeys.READ_TIMEOUT);
		this.writeTimeout = configuredSocketTimeout(ConfigKeys.WRITE_TIMEOUT);
		this.localBindAddress = 					config.get(ConfigKeys.SMTP_LOCAL_ADDRESS);
		this.localBindPort = 						config.get(ConfigKeys.SMTP_LOCAL_PORT);
		this.smtpClientHostname = 					config.get(ConfigKeys.SMTP_CLIENT_HOSTNAME);
		this.trustAllSSLHost = 						verifyNonnullOrEmpty(config.valueOrProperty(null, ConfigKeys.DEFAULT_TRUST_ALL_HOSTS, DEFAULT_TRUST_ALL_HOSTS));
		this.verifyingServerIdentity = 				verifyNonnullOrEmpty(config.valueOrProperty(null, ConfigKeys.DEFAULT_VERIFY_SERVER_IDENTITY, DEFAULT_VERIFY_SERVER_IDENTITY));
		this.threadPoolSize = 						verifyNonnullOrEmpty(config.valueOrProperty(null, ConfigKeys.DEFAULT_POOL_SIZE, DEFAULT_POOL_SIZE));
		this.threadPoolKeepAliveTime = configuredDuration(ConfigKeys.THREAD_POOL_KEEP_ALIVE_TIME, DEFAULT_POOL_KEEP_ALIVE_TIME);
		this.threadPoolSizeExplicit = config.hasProperty(ConfigKeys.DEFAULT_POOL_SIZE);
		this.threadPoolKeepAliveTimeExplicit = config.hasProperty(ConfigKeys.THREAD_POOL_KEEP_ALIVE_TIME);
		this.connectionPoolCoreSize = 				verifyNonnullOrEmpty(config.valueOrProperty(null, ConfigKeys.DEFAULT_CONNECTIONPOOL_CORE_SIZE, DEFAULT_CONNECTIONPOOL_CORE_SIZE));
		this.connectionPoolMaxSize = 				verifyNonnullOrEmpty(config.valueOrProperty(null, ConfigKeys.DEFAULT_CONNECTIONPOOL_MAX_SIZE, DEFAULT_CONNECTIONPOOL_MAX_SIZE));
		this.connectionPoolClaimTimeout = configuredDuration(ConfigKeys.CONNECTION_POOL_CLAIM_TIMEOUT, DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT);
		this.connectionPoolExpireAfter = configuredDuration(ConfigKeys.CONNECTION_POOL_EXPIRE_AFTER, DEFAULT_CONNECTIONPOOL_EXPIREAFTER);
		this.connectionPoolExpireAfterCreation = configuredDuration(ConfigKeys.CONNECTION_POOL_EXPIRE_AFTER_CREATION, DEFAULT_CONNECTIONPOOL_EXPIREAFTERCREATION);
		this.connectionPoolLoadBalancingStrategy = 	verifyNonnullOrEmpty(config.valueOrProperty(null, ConfigKeys.DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY,
				LoadBalancingStrategy.valueOf(DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY)));
		final Map<UUID, ConnectionPoolClusterConfig> configuredClusters = config.get(ConfigKeys.DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS);
		this.connectionPoolClusterConfigs = 		configuredClusters == null
				? Collections.<UUID, ConnectionPoolClusterConfig>emptyMap()
				: new LinkedHashMap<>(configuredClusters);
		this.transportModeLoggingOnly = 			verifyNonnullOrEmpty(config.valueOrProperty(null, ConfigKeys.TRANSPORT_MODE_LOGGING_ONLY, DEFAULT_TRANSPORT_MODE_LOGGING_ONLY));

		final String trustedHosts = config.get(ConfigKeys.DEFAULT_TRUSTED_HOSTS);
		if (trustedHosts != null) {
			this.sslHostsToTrust = new ArrayList<>(Arrays.asList(trustedHosts.split(";")));
		}
		this.emailValidator = JMail.strictValidator();
		this.configuredClusterKey = clusterKey;
	}

	@Nullable
	private static SendingRateLimit resolveRateLimit(final SimpleJavaMailConfig config, final ConfigKey<Integer> countProperty, final ConfigKey<Duration> periodProperty) {
		final Integer count = config.get(countProperty);
		final Duration period = config.get(periodProperty);
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

	boolean ownsFailoverMailer() {
		return ownsFailoverMailer;
	}

	/** Validate the whole captured graph before constructing privately owned executors, proxy bridges or pools. */
	final MailerImpl buildConfiguredMailer(final Supplier<MailerImpl> constructMailer) {
		resolveCliFailoverPolicy();
		validateFailoverGraph(new LinkedHashSet<>());
		final MailerImpl suppliedFailoverMailer = failoverMailer;
		try {
			constructNamedFailoverMailer();
			final MailerImpl configuredMailer = constructMailer.get();
			// Traversal includes the initiating Mailer, so only configured routes may enable this gate.
			if (configuredMailer.failoverMailer() != null) {
				enableCoordinatedFailoverFor(configuredMailer);
			}
			return configuredMailer;
		} catch (RuntimeException | Error failure) {
			closeOwnedFailoverAfterFailedBuild(failure);
			throw failure;
		} finally {
			failoverMailer = suppliedFailoverMailer;
			ownsFailoverMailer = false;
		}
	}

	private void constructNamedFailoverMailer() {
		if (failoverName != null) {
			failoverMailer = (MailerImpl) new MailerRegularBuilderImpl(runtime.named(failoverName), runtime).buildMailer();
			ownsFailoverMailer = true;
		}
	}

	private static void enableCoordinatedFailoverFor(final MailerImpl configuredMailer) {
		for (MailerImpl routeParticipant = configuredMailer;
				routeParticipant != null;
				routeParticipant = routeParticipant.failoverMailer()) {
			routeParticipant.definition().recovery.enableCoordinatedFailover();
		}
	}

	private void closeOwnedFailoverAfterFailedBuild(final Throwable failure) {
		// Supplied Mailers are borrowed; construction rollback may close only privately created named Mailers.
		if (ownsFailoverMailer && failoverMailer != null) {
			try {
				failoverMailer.close();
			} catch (Exception cleanupFailure) {
				if (cleanupFailure instanceof InterruptedException) {
					Thread.currentThread().interrupt();
				}
				failure.addSuppressed(cleanupFailure);
			}
		}
	}

	final void validateFailoverGraph(final Set<FactoryMailerRuntime.Definition> failoverRoute) {
		validateLockedConfiguration();
		validateConnectionConstruction();
		ConfiguredFailover.verifyPolicyLocks(locksOf(config), failoverPolicy);
		verifyCompatibleAncestorMessageLocks(failoverRoute, config);
		if (!failoverRoute.add(definition)) {
			throw new IllegalArgumentException("The named failover Mailers form a cycle. "
					+ "Remove the relationship that points back to an earlier definition; each failover route must end.");
		}
		verifyLockedRelationship();
		validateNextFailoverStage(failoverRoute);
		failoverRoute.remove(definition);
	}

	private void validateConnectionConstruction() {
		validateOwnedConnectionRequirements();
		final Session suppliedSession = this instanceof MailerFromSessionBuilderImpl
				? ((MailerFromSessionBuilderImpl) this).getSession() : null;
		final MailerSessionSettings connectionSettings = buildSessionSettings(suppliedSession);
		verifyAuthenticatedProxyModuleAvailable(connectionSettings.getProxyConfig());
	}

	private void verifyAuthenticatedProxyModuleAvailable(@Nullable final ProxyConfig resolvedProxy) {
		if (customMailer == null && resolvedProxy != null && resolvedProxy.requiresProxy() && resolvedProxy.requiresAuthentication()) {
			ModuleLoader.loadAuthenticatedSocksModule();
		}
	}

	private void validateNextFailoverStage(final Set<FactoryMailerRuntime.Definition> failoverRoute) {
		if (failoverName != null) {
			new MailerRegularBuilderImpl(runtime.named(failoverName), runtime).validateFailoverGraph(failoverRoute);
		} else {
			for (MailerImpl failoverParticipant = failoverMailer;
					failoverParticipant != null;
					failoverParticipant = failoverParticipant.failoverMailer()) {
				verifySuppliedFailoverRouteLocks(failoverRoute, failoverParticipant);
			}
		}
	}

	private static void verifySuppliedFailoverRouteLocks(final Set<FactoryMailerRuntime.Definition> failoverRoute,
			final MailerImpl failoverParticipant) {
		for (FactoryMailerRuntime.Definition precedingDefinition : failoverRoute) {
			LockedEmailConfiguration.verifyCompatibleLocks(precedingDefinition.config, failoverParticipant.definition().config);
			if (failoverParticipant.getOperationalConfig().getCustomMailer() != null) {
				LockedEmailConfiguration.verifyCustomMailerCompatibility(precedingDefinition.config);
			}
		}
	}

	private static void verifyCompatibleAncestorMessageLocks(final Set<FactoryMailerRuntime.Definition> failoverRoute,
			final SimpleJavaMailConfig candidateConfiguration) {
		for (FactoryMailerRuntime.Definition precedingDefinition : failoverRoute) {
			LockedEmailConfiguration.verifyCompatibleLocks(precedingDefinition.config, candidateConfiguration);
		}
	}

	private void verifyLockedRelationship() {
		if (!locksOf(config).contains(Property.FAILOVER_MAILER)) {
			return;
		}
		final String permittedFailoverName = config.get(ConfigKeys.FAILOVER_MAILER);
		if (usesPermittedNamedFailover(permittedFailoverName) || usesPermittedSuppliedFailover(permittedFailoverName)) {
			return;
		}
		throw locksOf(config).conflict(Property.FAILOVER_MAILER, "This factory fixes the permitted failover definition and its nested route. "
				+ "Keep the captured named relationship, or ask the configuration owner to supply a compatible failover route; "
				+ "an unrelated Mailer with the same name or host is not that definition.");
	}

	private boolean usesPermittedNamedFailover(final String permittedFailoverName) {
		return failoverName != null && failoverName.equals(permittedFailoverName);
	}

	private boolean usesPermittedSuppliedFailover(final String permittedFailoverName) {
		return failoverMailer != null && failoverMailer.definition() == runtime.named(permittedFailoverName);
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
		resolveTelemetrySettings();
		final MailSendRetryPolicy resolvedRetryPolicy = resolveMailSendRetryPolicy();
		final ConfigurationLocks locks = locksOf(config);
		if (locks.isEmpty()) {
			return;
		}
		validateLockedSendingSettings(locks, resolvedRetryPolicy);
		validateLockedConnectionSettings(locks);
		validateLockedExecutionSettings(locks);
		validateLockedExtraProperties(locks);
		validateLockedExecutorSettings(locks);
		validateCustomMailerLocks(locks);
		LockedSmtpConnectionRequirements.verifyBuilderCustomizations(locks, explicitSessionSettings, transportStrategyForLocks());
		validateLockedClusterDefaults(locks);
		if (oauth2AccessTokenProvider != null && locks.contains(Property.SMTP_PASSWORD)) {
			throw locks.conflict(Property.SMTP_PASSWORD, "withOAuth2AccessTokenProvider(...) supplies credentials at send time, "
					+ "but this factory locks the SMTP credential. "
					+ "Runtime tokens can replace that locked value, so these settings cannot be combined. Remove that builder call, "
					+ "or remove the SMTP credential lock from the configuration source if credentials should be supplied dynamically.");
		}
	}

	private void validateLockedSendingSettings(final ConfigurationLocks locks, final MailSendRetryPolicy resolvedRetryPolicy) {
		ConfiguredMailSendRetry.verifyLocks(locks, resolvedRetryPolicy);
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
		locks.verify(Property.DEFAULT_ASYNC_QUEUE_WAIT_TIMEOUT_MILLIS, DurationMillis.toInt(asyncQueueConfig.getWaitTimeout(), "asyncQueueWaitTimeout"));
		locks.verify(Property.DEFAULT_POOL_SIZE, threadPoolSize);
		locks.verify(Property.DEFAULT_POOL_KEEP_ALIVE_TIME, DurationMillis.toNullableInt(threadPoolKeepAliveTime, "threadPoolKeepAliveTime"));
		if (locks.contains(Property.DEFAULT_CONNECTIONPOOL_CLUSTER_KEY)
				&& !clusterKey.equals(UUID.fromString(config.get(ConfigKeys.DEFAULT_CONNECTIONPOOL_CLUSTER_KEY)))) {
			throw locks.conflict(Property.DEFAULT_CONNECTIONPOOL_CLUSTER_KEY,
					"withClusterKey(...) selects a different pool cluster from the one locked by this factory. "
					+ "Remove that builder call, use the locked cluster identifier, or change this lock in its configuration source.");
		}
		locks.verify(Property.DEFAULT_CONNECTIONPOOL_CORE_SIZE, connectionPoolCoreSize);
		locks.verify(Property.DEFAULT_CONNECTIONPOOL_MAX_SIZE, connectionPoolMaxSize);
		locks.verify(Property.DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT_MILLIS, DurationMillis.toNullableInt(connectionPoolClaimTimeout, "connectionPoolClaimTimeout"));
		locks.verify(Property.DEFAULT_CONNECTIONPOOL_EXPIREAFTER_MILLIS, DurationMillis.toNullableInt(connectionPoolExpireAfter, "connectionPoolExpireAfter"));
		locks.verify(Property.DEFAULT_CONNECTIONPOOL_EXPIREAFTERCREATION_MILLIS, DurationMillis.toNullableInt(connectionPoolExpireAfterCreation, "connectionPoolExpireAfterCreation"));
		locks.verify(Property.DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY, connectionPoolLoadBalancingStrategy);
	}

	private void validateLockedConnectionSettings(final ConfigurationLocks locks) {
		locks.verify(Property.CONNECTION_CONFLICT_POLICY, resolvedConnectionConflictPolicy());
		locks.verify(Property.PROXY_HOST, proxyHost);
		locks.verify(Property.PROXY_PORT, proxyPort);
		locks.verify(Property.PROXY_USERNAME, proxyUsername);
		locks.verify(Property.PROXY_PASSWORD, proxyPassword);
		locks.verify(Property.PROXY_SOCKS5BRIDGE_PORT, proxyBridgePort);
		locks.verify(Property.JAVAXMAIL_DEBUG, config.valueOrProperty(debugConfig.getEnabled(), ConfigKeys.JAVAXMAIL_DEBUG, DEFAULT_JAVAXMAIL_DEBUG));
		locks.verify(Property.JAVAXMAIL_DEBUG_OUTPUT, debugConfig.getPrinter() == null
				? config.valueOrProperty(debugConfig.getOutput(), ConfigKeys.JAVAXMAIL_DEBUG_OUTPUT)
				: debugConfig.getPrinter() == System.out ? SessionDebugOutput.STDOUT : debugConfig.getPrinter() == System.err ? SessionDebugOutput.STDERR : null);
		locks.verify(Property.DISABLE_ALL_CLIENTVALIDATION, clientValidationWarningsOnly);
		validateLockedSocketTimeout(locks, Property.DEFAULT_CONNECT_TIMEOUT_MILLIS);
		validateLockedSocketTimeout(locks, Property.DEFAULT_READ_TIMEOUT_MILLIS);
		validateLockedSocketTimeout(locks, Property.DEFAULT_WRITE_TIMEOUT_MILLIS);
		locks.verify(Property.SMTP_LOCAL_ADDRESS, localBindAddress);
		locks.verify(Property.SMTP_LOCAL_PORT, localBindPort);
		locks.verify(Property.SMTP_CLIENT_HOSTNAME, smtpClientHostname);
		locks.verify(Property.DEFAULT_TRUST_ALL_HOSTS, trustAllSSLHost);
		locks.verify(Property.DEFAULT_VERIFY_SERVER_IDENTITY, verifyingServerIdentity);
		if (locks.contains(Property.DEFAULT_TRUSTED_HOSTS)
				&& !Arrays.asList(config.get(ConfigKeys.DEFAULT_TRUSTED_HOSTS).split(";")).equals(sslHostsToTrust)) {
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

	private void validateCustomMailerLocks(final ConfigurationLocks locks) {
		if (customMailer == null) {
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
		if (suppliedSession != null) {
			verifySessionIsUnowned(suppliedSession);
		}
		final Map<Property, Object> connectionChoices = suppliedSession == null
				? ownedConnectionChoices() : new LinkedHashMap<>(explicitSessionSettings);
		final ConfigurationLocks locks = locksOf(config);
		final TransportStrategy strategy = transportStrategyForLocks();
		verifyConnectionCustomizationSupported(strategy);
		addLockedConnectionChoices(connectionChoices, strategy, locks);

		final Map<Property, Object> declarations = new LinkedHashMap<>(explicitSessionSettings);
		final Map<String, Object> defaults = connectionDefaults(suppliedSession, strategy, connectionChoices);
		final ConnectionConfigurationResolver.Resolution resolution = ConnectionConfigurationResolver.resolve(config, suppliedSession, strategy,
				resolvedConnectionConflictPolicy(), getProperties(), explicitRawSessionSettings, declarations, defaults,
				regularPhysicalChoices(strategy), new ProxyConfigImpl(proxyHost, proxyPort, proxyUsername, proxyPassword, proxyBridgePort),
				proxyCustomized(), customMailer == null);
		final MailerSessionSettings resolved = sessionSettingsWithLocks(suppliedSession, strategy, locks, resolution);
		verifyResolvedConnectionLocks(suppliedSession, strategy, resolution, resolved);
		verifyResolvedOAuth2Configuration(suppliedSession, strategy, resolved);
		return resolved;
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

	private MailerSessionSettings sessionSettingsWithLocks(@Nullable final Session suppliedSession, @Nullable final TransportStrategy strategy,
			final ConfigurationLocks locks, final ConnectionConfigurationResolver.Resolution resolution) {
		// Locked connection members can add a missing caller-Session value, but never settle an ordinary conflict silently.
		resolution.overrides.putAll(MailerSessionSettings.connectionOverrides(strategy, lockedConnectionChoices(strategy, locks), getProperties()));
		final boolean applyDebugEnabled = suppliedSession == null || debugConfig.getEnabled() != null || config.hasProperty(ConfigKeys.JAVAXMAIL_DEBUG);
		final boolean applyDebugOutput = debugConfig.getOutput() != null || debugConfig.getPrinter() != null || config.hasProperty(ConfigKeys.JAVAXMAIL_DEBUG_OUTPUT);
		return MailerSessionSettings.resolve(suppliedSession, strategy, getProperties(), resolution.overrides, locks.getValues(),
				resolution.proxy, resolution.shadowedFactoryClassProperty, applyDebugEnabled, applyDebugOutput);
	}

	private void verifyResolvedConnectionLocks(@Nullable final Session suppliedSession, @Nullable final TransportStrategy strategy,
			final ConnectionConfigurationResolver.Resolution resolution, final MailerSessionSettings resolved) {
		final LockedSmtpConnectionRequirements requirements = new LockedSmtpConnectionRequirements(config, resolution.proxy, resolved.isTrustAllSSLHost(), strategy);
		requirements.verifyAdditionalProperties(getProperties());
		requirements.verifyExplicitConnectionChoices(resolution.explicitOverrides);
		if (suppliedSession != null) {
			requirements.verifyCallerOwnedSession(suppliedSession);
		}
		LockedSmtpConnectionRequirements.verifyCustomSocketFactory(locksOf(config), resolved.copyEffectiveProperties(), strategy);
	}

	private void verifyResolvedOAuth2Configuration(@Nullable final Session suppliedSession, @Nullable final TransportStrategy strategy,
			final MailerSessionSettings resolved) {
		if (customMailer == null) {
			final TransportStrategy authenticationStrategy = suppliedSession == null ? strategy : TransportStrategy.findStrategyForSession(suppliedSession);
			OAuth2AccessTokenResolver.validateConfiguration(Session.getInstance(resolved.copyEffectiveProperties()), new Properties(),
					authenticationStrategy, oauth2AccessTokenProvider);
		}
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
		return config.valueOrProperty(connectionConflictPolicy, ConfigKeys.CONNECTION_CONFLICT_POLICY, DEFAULT_CONNECTION_CONFLICT_POLICY);
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
		return supplied == null ? configuredSocketTimeout(property) : supplied;
	}

	private void validateLockedSocketTimeout(final ConfigurationLocks locks, final Property property) {
		// Ordinary inherited values yield to locks; only a deliberate builder choice may contradict one.
		if (explicitSessionSettings.containsKey(property)) {
			final Object value = explicitSessionSettings.get(property);
			locks.verify(Property.DEFAULT_SESSION_TIMEOUT_MILLIS, value);
			locks.verify(property, value);
		}
	}

	/** Assemble supplied configuration; default resolution and caller-Session ownership have already been settled by the builder. */
	OperationalConfig buildOperationalConfig(final MailerSessionSettings sessionSettings) {
		resolveTelemetrySettings();
		if (executorService != null && !asyncQueueConfig.equals(DEFAULT_ASYNC_QUEUE_CONFIG)) {
			throw new IllegalArgumentException("Configure queue capacity and overflow on the caller-owned executor; built-in async queue settings cannot be combined with withExecutorService");
		}
		return new OperationalConfigImpl(
				getProperties(),
				resolvedConnectionConflictPolicy(),
				sessionSettings.getConnectTimeout(),
				sessionSettings.getReadTimeout(),
				sessionSettings.getWriteTimeout(),
				sessionSettings.getLocalBindAddress(),
				sessionSettings.getLocalBindPort(),
				sessionSettings.getSmtpClientHostname(),
				isLegacySmtpContentSupportEnabled(),
				resolveTelemetryConfig(),
				resolvedWorkerCount(),
				resolvedWorkerKeepAlive(),
				getClusterKey(),
				getConnectionPoolCoreSize(),
				getConnectionPoolMaxSize(),
				getConnectionPoolClaimTimeout(),
				getConnectionPoolExpireAfter(),
				getConnectionPoolExpireAfterCreation(),
				getConnectionPoolLoadBalancingStrategy(),
				Collections.unmodifiableMap(new LinkedHashMap<>(connectionPoolClusterConfigs)),
				isTransportModeLoggingOnly(),
				resolveDebugConfig(sessionSettings.getDebugConfig()),
				isClientValidationWarningsOnly(),
				Collections.unmodifiableList(new ArrayList<>(sessionSettings.getSslHostsToTrust())),
				sessionSettings.isTrustAllSSLHost(),
				sessionSettings.isVerifyingServerIdentity(),
				getExecutorService() != null ? getExecutorService() : determineDefaultExecutorService(),
				isExecutorServiceUserProvided(),
				getCustomMailer(),
				getOAuth2AccessTokenProvider(),
				getAsyncQueueConfig(), getMailSendTimeout(), messageRateLimit, recipientRateLimit, rateLimitGroup, rateLimitBurstsAllowed, failoverPolicy,
				resolveMailSendRetryPolicy());
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
		failoverPolicy = policy;
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

	@Nullable
	MailerImpl getResolvedFailoverMailer() {
		return failoverMailer;
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
		final SessionDebugConfig.Builder resolved = SessionDebugConfig.builder().withEnabled(config.valueOrProperty(
				debugConfig.getEnabled(), ConfigKeys.JAVAXMAIL_DEBUG, baseline.getEnabled()));
		if (debugConfig.getPrinter() != null) {
			return resolved.withPrinter(debugConfig.getPrinter()).build();
		}
		final SessionDebugOutput output = config.valueOrProperty(debugConfig.getOutput(), ConfigKeys.JAVAXMAIL_DEBUG_OUTPUT, baseline.getOutput());
		return output != null ? resolved.withOutput(output).build() : resolved.withPrinter(requireNonNull(baseline.getPrinter(), "baselinePrinter")).build();
	}

	/**
	 * @see org.simplejavamail.api.mailer.config.ValidationConfig.Builder#withClientValidationWarningsOnly(boolean)
	 */
	@Override
	public T disablingAllClientValidation(@NotNull final Boolean clientValidationWarningsOnly) {
		this.clientValidationWarningsOnly = clientValidationWarningsOnly;
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
		this.legacySmtpContentSupportEnabled = enabled;
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
		threadPoolSizeExplicit = true;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withThreadPoolKeepAliveTime(Duration)
	 */
	@Override
	public T withThreadPoolKeepAliveTime(@NotNull final Duration threadPoolKeepAliveTime) {
		DurationMillis.toInt(threadPoolKeepAliveTime, "threadPoolKeepAliveTime");
		this.threadPoolKeepAliveTime = threadPoolKeepAliveTime;
		threadPoolKeepAliveTimeExplicit = true;
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
	 * @see MailerGenericBuilder#withConnectionPoolClaimTimeout(Duration)
	 */
	@Override
	public T withConnectionPoolClaimTimeout(@NotNull final Duration connectionPoolClaimTimeout) {
		DurationMillis.toInt(connectionPoolClaimTimeout, "connectionPoolClaimTimeout");
		this.connectionPoolClaimTimeout = connectionPoolClaimTimeout;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withConnectionPoolExpireAfter(Duration)
	 */
	@Override
	public T withConnectionPoolExpireAfter(@NotNull final Duration connectionPoolExpireAfter) {
		DurationMillis.toInt(connectionPoolExpireAfter, "connectionPoolExpireAfter");
		this.connectionPoolExpireAfter = connectionPoolExpireAfter;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withConnectionPoolExpireAfterCreation(Duration)
	 */
	@Override
	public T withConnectionPoolExpireAfterCreation(@NotNull final Duration connectionPoolExpireAfterCreation) {
		DurationMillis.toInt(connectionPoolExpireAfterCreation, "connectionPoolExpireAfterCreation");
		this.connectionPoolExpireAfterCreation = connectionPoolExpireAfterCreation;
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
			this.properties.put(property.getKey(), property.getValue());
			explicitRawSessionSettings.put(property.getKey().toString(), property.getValue());
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
			explicitRawSessionSettings.put(property.getKey(), property.getValue());
		}
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#withProperty(String, Object)
	 */
	@Override
	public T withProperty(@NotNull final String propertyName, @Nullable final Object propertyValue) {
		explicitRawSessionSettings.put(propertyName, propertyValue == null ? null : propertyValue.toString());
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
				.withEnabled(config.valueOrProperty(telemetryConfig.getEnabled(), ConfigKeys.TELEMETRY_ENABLED, DEFAULT_MAIL_SEND_TELEMETRY_ENABLED))
				.withSmtpEndpointAttributes(config.valueOrProperty(telemetryConfig.getSmtpEndpointAttributes(),ConfigKeys.TELEMETRY_SMTP_ENDPOINT_ATTRIBUTES, DEFAULT_MAIL_SEND_TELEMETRY_SMTP_ENDPOINT_ATTRIBUTES));
		return resolved.build();
	}

	/** Validate the builder's resolved choices before requesting optional runtime resources. */
	private void resolveTelemetrySettings() {
		final MailSendTelemetryConfig resolved = resolveTelemetryConfig();
		locksOf(config).verify(Property.TELEMETRY_ENABLED, resolved.getEnabled());
		locksOf(config).verify(Property.TELEMETRY_SMTP_ENDPOINT_ATTRIBUTES, resolved.getSmtpEndpointAttributes());
		if (Boolean.TRUE.equals(resolved.getEnabled())) {
			ModuleLoader.loadOpenTelemetryModule();
		}
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
		return config.valueOrProperty(null, property, fallback);
	}

	@NotNull
	private ExecutorService determineDefaultExecutorService() {
		return new MailSendExecutor(resolvedWorkerCount(), DurationMillis.toInt(resolvedWorkerKeepAlive(), "threadPoolKeepAliveTime"), asyncQueueConfig);
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
		this.connectionPoolExpireAfterCreation = null;
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
			explicitRawSessionSettings.put(key.toString(), null);
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
        connectionPoolCoreSize = config.valueOrProperty(choices.getCoreSize(), ConfigKeys.DEFAULT_CONNECTIONPOOL_CORE_SIZE, DEFAULT_CONNECTIONPOOL_CORE_SIZE);
        connectionPoolMaxSize = config.valueOrProperty(choices.getMaxSize(), ConfigKeys.DEFAULT_CONNECTIONPOOL_MAX_SIZE, DEFAULT_CONNECTIONPOOL_MAX_SIZE);
        connectionPoolClaimTimeout = durationChoice(choices.getClaimTimeout(), ConfigKeys.CONNECTION_POOL_CLAIM_TIMEOUT, DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT);
        connectionPoolExpireAfter = durationChoice(choices.getExpireAfter(), ConfigKeys.CONNECTION_POOL_EXPIRE_AFTER, DEFAULT_CONNECTIONPOOL_EXPIREAFTER);
        connectionPoolExpireAfterCreation = choices.isExpireAfterCreationCleared() ? null : durationChoice(choices.getExpireAfterCreation(),ConfigKeys.CONNECTION_POOL_EXPIRE_AFTER_CREATION, DEFAULT_CONNECTIONPOOL_EXPIREAFTERCREATION);
        clusterKey = choices.getClusterKey() == null ? configuredClusterKey : choices.getClusterKey();
        connectionPoolLoadBalancingStrategy = config.valueOrProperty(choices.getLoadBalancingStrategy(), ConfigKeys.DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY,
                LoadBalancingStrategy.valueOf(DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY));
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
        threadPoolSize = config.valueOrProperty(choices.getThreadPoolSize(), ConfigKeys.DEFAULT_POOL_SIZE, DEFAULT_POOL_SIZE);
        threadPoolKeepAliveTime = durationChoice(choices.getKeepAliveTime(), ConfigKeys.THREAD_POOL_KEEP_ALIVE_TIME, DEFAULT_POOL_KEEP_ALIVE_TIME);
        threadPoolSizeExplicit = choices.getThreadPoolSize() != null || config.hasProperty(ConfigKeys.DEFAULT_POOL_SIZE);
        threadPoolKeepAliveTimeExplicit = choices.getKeepAliveTime() != null || config.hasProperty(ConfigKeys.THREAD_POOL_KEEP_ALIVE_TIME);
        executorService = choices.getExecutorService();
        final AsyncQueueOverflowPolicy policy = choices.getRejectWhenFull() == null
                ? config.valueOrProperty(null, ConfigKeys.DEFAULT_ASYNC_QUEUE_OVERFLOW_POLICY, DEFAULT_ASYNC_QUEUE_OVERFLOW_POLICY)
                : choices.getRejectWhenFull() ? AsyncQueueOverflowPolicy.REJECT : AsyncQueueOverflowPolicy.WAIT_FOR_CAPACITY;
        asyncQueueConfig = new AsyncQueueConfig(config.valueOrProperty(choices.getQueueCapacity(), ConfigKeys.DEFAULT_ASYNC_QUEUE_CAPACITY, DEFAULT_ASYNC_QUEUE_CAPACITY),
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
        proxyHost = choices.isHostCleared() ? null : stringChoice(choices.getHost(), ConfigKeys.PROXY_HOST);
        proxyPort = choices.isPortCleared() ? null : config.valueOrProperty(choices.getPort(), ConfigKeys.PROXY_PORT, DEFAULT_PROXY_PORT);
        proxyUsername = choices.isUsernameCleared() ? null : stringChoice(choices.getUsername(), ConfigKeys.PROXY_USERNAME);
        proxyPassword = choices.isPasswordCleared() ? null : stringChoice(choices.getPassword(), ConfigKeys.PROXY_PASSWORD);
        proxyBridgePort = config.valueOrProperty(choices.getBridgePort(), ConfigKeys.PROXY_SOCKS5BRIDGE_PORT, DEFAULT_PROXY_BRIDGE_PORT);
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
        messageRateLimit = choices.isMessageRateLimitCleared() ? null : choices.getMessageRateLimit() != null ? choices.getMessageRateLimit()
                : resolveRateLimit(config, ConfigKeys.SMTP_MESSAGE_RATE_LIMIT, ConfigKeys.SMTP_MESSAGE_RATE_PERIOD);
        recipientRateLimit = choices.isRecipientRateLimitCleared() ? null : choices.getRecipientRateLimit() != null ? choices.getRecipientRateLimit()
                : resolveRateLimit(config, ConfigKeys.SMTP_RECIPIENT_RATE_LIMIT, ConfigKeys.SMTP_RECIPIENT_RATE_PERIOD);
        rateLimitGroup = choices.isRateLimitGroupCleared() ? null : config.valueOrProperty(choices.getRateLimitGroup(), ConfigKeys.SMTP_RATE_LIMIT_GROUP, null);
        if (rateLimitGroup != null) { withRateLimitGroup(rateLimitGroup); }
        rateLimitBurstsAllowed = config.valueOrProperty(choices.getRateLimitBurstsAllowed(), ConfigKeys.SMTP_RATE_LIMIT_ALLOW_BURSTS, DEFAULT_RATE_LIMIT_BURSTS_ALLOWED);
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
        localBindAddress = choices.isLocalBindAddressCleared() ? null : stringChoice(choices.getLocalBindAddress(), ConfigKeys.SMTP_LOCAL_ADDRESS);
        localBindPort = choices.isLocalBindPortCleared() ? null : config.valueOrProperty(choices.getLocalBindPort(), ConfigKeys.SMTP_LOCAL_PORT, null);
        smtpClientHostname = choices.isSmtpClientHostnameCleared() ? null : stringChoice(choices.getSmtpClientHostname(), ConfigKeys.SMTP_CLIENT_HOSTNAME);
        legacySmtpContentSupportEnabled = config.valueOrProperty(choices.getLegacySmtpContentSupport(), ConfigKeys.SMTP_LEGACY_CONTENT_SUPPORT, DEFAULT_LEGACY_SMTP_CONTENT_SUPPORT);
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
        final String configuredHosts = config.get(ConfigKeys.DEFAULT_TRUSTED_HOSTS);
        sslHostsToTrust = choices.getSslHostsToTrust() != null ? new ArrayList<>(choices.getSslHostsToTrust())
                : configuredHosts == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(configuredHosts.split(";")));
        trustAllSSLHost = config.valueOrProperty(choices.getTrustAllHosts(), ConfigKeys.DEFAULT_TRUST_ALL_HOSTS, DEFAULT_TRUST_ALL_HOSTS);
        verifyingServerIdentity = config.valueOrProperty(choices.getVerifyingServerIdentity(), ConfigKeys.DEFAULT_VERIFY_SERVER_IDENTITY, DEFAULT_VERIFY_SERVER_IDENTITY);
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
        clientValidationWarningsOnly = config.valueOrProperty(choices.getClientValidationWarningsOnly(), ConfigKeys.DISABLE_ALL_CLIENTVALIDATION, DEFAULT_DISABLE_ALL_CLIENTVALIDATION);
        emailValidator = choices.isEmailValidatorCleared() ? null : choices.getEmailValidator() == null ? JMail.strictValidator() : choices.getEmailValidator();
        maximumEmailSize = choices.getMaximumEmailSize();
        validationConfig = choices;
        return (T) this;
    }
    /** @see MailerGenericBuilder#getValidationConfig() */
    @Override @NotNull public ValidationConfig getValidationConfig() { return validationConfig; }
    /** @see MailerGenericBuilder#useConfiguredValidationConfig() */
    @Override public T useConfiguredValidationConfig() { return withValidationConfig(ValidationConfig.builder().build()); }

    /** Only omitted group members inherit; explicit empty strings retain their setter's validation or clearing semantics. */
    @Nullable final String stringChoice(@Nullable final String supplied, final ConfigKey<String> property) {
        return supplied == null ? config.get(property) : supplied;
    }

    @Nullable private Duration durationChoice(@Nullable final Duration supplied, final ConfigKey<Duration> property, @Nullable final Duration fallback) {
        return supplied == null ? configuredDuration(property, fallback) : supplied;
    }
}
