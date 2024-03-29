package org.simplejavamail.mailer.internal;

import com.sanctionco.jmail.EmailValidator;
import com.sanctionco.jmail.JMail;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.config.ConfigLoader.Property;
import org.simplejavamail.internal.moduleloader.ModuleLoader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.simplejavamail.config.ConfigLoader.getStringProperty;
import static org.simplejavamail.config.ConfigLoader.hasProperty;
import static org.simplejavamail.config.ConfigLoader.valueOrProperty;
import static org.simplejavamail.config.ConfigLoader.valueOrPropertyAsBoolean;
import static org.simplejavamail.config.ConfigLoader.valueOrPropertyAsInteger;
import static org.simplejavamail.config.ConfigLoader.valueOrPropertyAsString;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_CONNECTIONPOOL_CLUSTER_KEY;
import static org.simplejavamail.config.ConfigLoader.Property.PROXY_HOST;
import static org.simplejavamail.config.ConfigLoader.Property.PROXY_PASSWORD;
import static org.simplejavamail.config.ConfigLoader.Property.PROXY_USERNAME;
import static org.simplejavamail.internal.util.MiscUtil.checkArgumentNotEmpty;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;
import static org.simplejavamail.internal.util.Preconditions.verifyNonnullOrEmpty;

/**
 * @see MailerGenericBuilder
 */
@SuppressWarnings({"UnusedReturnValue", "unchecked"})
abstract class MailerGenericBuilderImpl<T extends MailerGenericBuilderImpl<?>> implements InternalMailerBuilder<T> {
	
	/**
	 * @see MailerGenericBuilder#async()
	 */
	private boolean async;
	
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
	 * @see #disablingAllClientValidation(Boolean)
	 */
	private boolean disableAllClientValidation;
	
	/**
	 * @see MailerGenericBuilder#withSessionTimeout(Integer)
	 */
	@NotNull
	private Integer sessionTimeout;

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
	 * @see MailerGenericBuilder#withConnectionPoolLoadBalancingStrategy(LoadBalancingStrategy loadBalancingStrategy)
	 */
	@NotNull
	private LoadBalancingStrategy connectionPoolLoadBalancingStrategy;

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
	 * Sets defaults configured for proxy host, proxy port, proxy username, proxy password and proxy bridge port (used in authenticated proxy).
	 * <p>
	 * <strong>Note:</strong> Any builder methods invoked after this will override the default value.
	 */
	MailerGenericBuilderImpl() {
		if (hasProperty(PROXY_HOST)) {
			this.proxyHost = getStringProperty(PROXY_HOST);
		}
		if (hasProperty(PROXY_USERNAME)) {
			this.proxyUsername = getStringProperty(PROXY_USERNAME);
		}
		if (hasProperty(PROXY_PASSWORD)) {
			this.proxyPassword = getStringProperty(PROXY_PASSWORD);
		}
		this.clusterKey = hasProperty(DEFAULT_CONNECTIONPOOL_CLUSTER_KEY)
				? UUID.fromString(verifyNonnullOrEmpty(getStringProperty(DEFAULT_CONNECTIONPOOL_CLUSTER_KEY)))
				: UUID.randomUUID(); // <-- this makes sure it won't form a cluster with another mailer

		this.proxyPort 								= verifyNonnullOrEmpty(valueOrPropertyAsInteger(null, Property.PROXY_PORT, DEFAULT_PROXY_PORT));
		this.proxyBridgePort 						= verifyNonnullOrEmpty(valueOrPropertyAsInteger(null, Property.PROXY_SOCKS5BRIDGE_PORT, DEFAULT_PROXY_BRIDGE_PORT));
		this.disableAllClientValidation				= verifyNonnullOrEmpty(valueOrPropertyAsBoolean(null, Property.DISABLE_ALL_CLIENTVALIDATION, DEFAULT_DISABLE_ALL_CLIENTVALIDATION));
		this.debugLogging 							= verifyNonnullOrEmpty(valueOrPropertyAsBoolean(null, Property.JAVAXMAIL_DEBUG, DEFAULT_JAVAXMAIL_DEBUG));
		this.sessionTimeout 						= verifyNonnullOrEmpty(valueOrPropertyAsInteger(null, Property.DEFAULT_SESSION_TIMEOUT_MILLIS, DEFAULT_SESSION_TIMEOUT_MILLIS));
		this.trustAllSSLHost 						= verifyNonnullOrEmpty(valueOrPropertyAsBoolean(null, Property.DEFAULT_TRUST_ALL_HOSTS, DEFAULT_TRUST_ALL_HOSTS));
		this.verifyingServerIdentity 				= verifyNonnullOrEmpty(valueOrPropertyAsBoolean(null, Property.DEFAULT_VERIFY_SERVER_IDENTITY, DEFAULT_VERIFY_SERVER_IDENTITY));
		this.threadPoolSize 						= verifyNonnullOrEmpty(valueOrPropertyAsInteger(null, Property.DEFAULT_POOL_SIZE, DEFAULT_POOL_SIZE));
		this.threadPoolKeepAliveTime 				= verifyNonnullOrEmpty(valueOrPropertyAsInteger(null, Property.DEFAULT_POOL_KEEP_ALIVE_TIME, DEFAULT_POOL_KEEP_ALIVE_TIME));
		this.connectionPoolCoreSize 				= verifyNonnullOrEmpty(valueOrPropertyAsInteger(null, Property.DEFAULT_CONNECTIONPOOL_CORE_SIZE, DEFAULT_CONNECTIONPOOL_CORE_SIZE));
		this.connectionPoolMaxSize 					= verifyNonnullOrEmpty(valueOrPropertyAsInteger(null, Property.DEFAULT_CONNECTIONPOOL_MAX_SIZE, DEFAULT_CONNECTIONPOOL_MAX_SIZE));
		this.connectionPoolClaimTimeoutMillis 		= verifyNonnullOrEmpty(valueOrPropertyAsInteger(null, Property.DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT_MILLIS, DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT_MILLIS));
		this.connectionPoolExpireAfterMillis 		= verifyNonnullOrEmpty(valueOrPropertyAsInteger(null, Property.DEFAULT_CONNECTIONPOOL_EXPIREAFTER_MILLIS, DEFAULT_CONNECTIONPOOL_EXPIREAFTER_MILLIS));
		this.connectionPoolLoadBalancingStrategy	= verifyNonnullOrEmpty(valueOrProperty(null, Property.DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY, LoadBalancingStrategy.valueOf(DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY)));
		this.transportModeLoggingOnly 				= verifyNonnullOrEmpty(valueOrPropertyAsBoolean(null, Property.TRANSPORT_MODE_LOGGING_ONLY, DEFAULT_TRANSPORT_MODE_LOGGING_ONLY));

		final String trustedHosts = valueOrPropertyAsString(null, Property.DEFAULT_TRUSTED_HOSTS, null);
		if (trustedHosts != null) {
			this.sslHostsToTrust = Arrays.asList(trustedHosts.split(";"));
		}

		this.emailValidator = JMail.strictValidator();
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
				getEmailValidator(),
				getEmailDefaults(),
				getEmailOverrides(),
				getMaximumEmailSize());
	}
	
	/**
	 * For internal use.
	 */
	OperationalConfig buildOperationalConfig() {
		return new OperationalConfigImpl(
				isAsync(),
				getProperties(),
				getSessionTimeout(),
				getThreadPoolSize(),
				getThreadPoolKeepAliveTime(),
				getClusterKey(),
				getConnectionPoolCoreSize(),
				getConnectionPoolMaxSize(),
				getConnectionPoolClaimTimeoutMillis(),
				getConnectionPoolExpireAfterMillis(),
				getConnectionPoolLoadBalancingStrategy(),
				isTransportModeLoggingOnly(),
				isDebugLogging(),
				isDisableAllClientValidation(),
				getSslHostsToTrust(),
				isTrustAllSSLHost(),
				isVerifyingServerIdentity(),
				getExecutorService() != null ? getExecutorService() : determineDefaultExecutorService(),
				isExecutorServiceUserProvided(),
				getCustomMailer());
	}
	
	/**
	 * @see MailerGenericBuilder#async()
	 */
	@Override
	public T async() {
		this.async = true;
		return (T) this;
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
		return (T) this;
	}
	
	/**
	 * @see MailerGenericBuilder#trustingAllHosts(boolean)
	 */
	@Override
	public T trustingAllHosts(final boolean trustAllHosts) {
		this.trustAllSSLHost = trustAllHosts;
		return (T) this;
	}

	/**
	 * @see MailerGenericBuilder#verifyingServerIdentity(boolean)
	 */
	@Override
	public T verifyingServerIdentity(final boolean verifyingServerIdentity) {
		this.verifyingServerIdentity = verifyingServerIdentity;
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
		return (ModuleLoader.batchModuleAvailable())
				? ModuleLoader.loadBatchModule().createDefaultExecutorService(getThreadPoolSize(), getThreadPoolKeepAliveTime())
				: Executors.newSingleThreadExecutor();
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
		return this.withConnectionPoolCoreSize(DEFAULT_CONNECTIONPOOL_MAX_SIZE);
	}

	/**
	 * @see MailerGenericBuilder#resetConnectionPoolClaimTimeoutMillis()
	 */
	@Override
	public T resetConnectionPoolClaimTimeoutMillis() {
		return this.withConnectionPoolExpireAfterMillis(DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT_MILLIS);
	}

	/**
	 * @see MailerGenericBuilder#resetConnectionPoolExpireAfterMillis()
	 */
	@Override
	public T resetConnectionPoolExpireAfterMillis() {
		return this.withConnectionPoolExpireAfterMillis(DEFAULT_CONNECTIONPOOL_EXPIREAFTER_MILLIS);
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
	 * @see MailerGenericBuilder#clearProperties()
	 */
	@Override
	public T clearProperties() {
		properties.clear();
		return (T) this;
	}
	
	/**
	 * @see MailerGenericBuilder#isAsync()
	 */
	@Override
	public boolean isAsync() {
		return async;
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
}