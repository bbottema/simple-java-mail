package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.api.mailer.config.OperationalConfig;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * @see OperationalConfig
 */
// FIXME Lombok, especially builder pattern
class OperationalConfigImpl implements OperationalConfig {
	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withSessionTimeout(Integer)
	 */
	private final int sessionTimeout;
	
	/**
	 * Can be overridden when calling {@code mailer.send(async = true)}.
	 *
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#async()
	 */
	private final boolean async;
	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withProperties(Properties)
	 */
	private final Properties properties;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withThreadPoolSize(Integer)
	 */
	private final int threadPoolSize;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withThreadPoolKeepAliveTime(Integer)
	 */
	private final int threadPoolKeepAliveTime;

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
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withConnectionPoolClaimTimeoutMillis(Integer)
	 */
	private final int connectionPoolClaimTimeoutMillis;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withConnectionPoolExpireAfterMillis(Integer)
	 */
	private final int connectionPoolExpireAfterMillis;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withConnectionPoolLoadBalancingStrategy(LoadBalancingStrategy)
	 */
	@NotNull
	private final LoadBalancingStrategy connectionPoolLoadBalancingStrategy;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withTransportModeLoggingOnly(Boolean)
	 */
	private final boolean transportModeLoggingOnly;
	
	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withDebugLogging(Boolean)
	 */
	private final boolean debugLogging;
	
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
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#verifyingServerIdentity(boolean)
	 */
	private final boolean verifyingServerIdentity;

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
	
	OperationalConfigImpl(final boolean async,
			@Nullable final Properties properties,
			final int sessionTimeout,
			final int threadPoolSize,
			final int threadPoolKeepAliveTime,
			@NotNull final UUID clusterKey,
			final int connectionPoolCoreSize,
			final int connectionPoolMaxSize,
			final int connectionPoolClaimTimeoutMillis,
			final int connectionPoolExpireAfterMillis,
			@NotNull final LoadBalancingStrategy connectionPoolLoadBalancingStrategy,
			final boolean transportModeLoggingOnly,
			final boolean debugLogging,
			@NotNull final List<String> sslHostsToTrust,
			final boolean trustAllSSLHost,
			final boolean verifyingServerIdentity,
			@NotNull final ExecutorService executorService,
			final boolean executorServiceIsUserProvided,
			@Nullable final CustomMailer customMailer) {
		this.async = async; // can be overridden when calling {@code mailer.send(async = true)}
		this.properties = properties;
		this.sessionTimeout = sessionTimeout;
		this.threadPoolSize = threadPoolSize;
		this.threadPoolKeepAliveTime = threadPoolKeepAliveTime;
		this.clusterKey = clusterKey;
		this.connectionPoolCoreSize = connectionPoolCoreSize;
		this.connectionPoolMaxSize = connectionPoolMaxSize;
		this.connectionPoolClaimTimeoutMillis = connectionPoolClaimTimeoutMillis;
		this.connectionPoolExpireAfterMillis = connectionPoolExpireAfterMillis;
		this.connectionPoolLoadBalancingStrategy = connectionPoolLoadBalancingStrategy;
		this.transportModeLoggingOnly = transportModeLoggingOnly;
		this.debugLogging = debugLogging;
		this.sslHostsToTrust = Collections.unmodifiableList(sslHostsToTrust);
		this.trustAllSSLHost = trustAllSSLHost;
		this.verifyingServerIdentity = verifyingServerIdentity;
		this.executorService = executorService;
		this.executorServiceIsUserProvided = executorServiceIsUserProvided;
		this.customMailer = customMailer;
	}

	@Override
	public String toString() {
		return "OperationalConfigImpl{" + "async=" + async
				+ ", properties=" + properties
				+ ", sessionTimeout=" + sessionTimeout
				+ ", threadPoolSize=" + threadPoolSize
				+ ", threadPoolKeepAliveTime=" + threadPoolKeepAliveTime
				+ ", clusterKey=" + clusterKey
				+ ", connectionPoolCoreSize=" + connectionPoolCoreSize
				+ ", connectionPoolMaxSize=" + connectionPoolMaxSize
				+ ", connectionPoolClaimTimeoutMillis=" + connectionPoolClaimTimeoutMillis
				+ ", connectionPoolExpireAfterMillis=" + connectionPoolExpireAfterMillis
				+ ", connectionPoolLoadBalancingStrategy=" + connectionPoolLoadBalancingStrategy
				+ ", transportModeLoggingOnly=" + transportModeLoggingOnly
				+ ", debugLogging=" + debugLogging
				+ ", sslHostsToTrust=" + sslHostsToTrust
				+ ", trustAllSSLHost=" + trustAllSSLHost
				+ ", verifyingServerIdentity=" + verifyingServerIdentity
				+ ", executorService=" + executorService
				+ ", customMailer=" + customMailer
				+ '}';
	}

	/**
	 * @see OperationalConfig#isAsync()
	 */
	@Override
	public boolean isAsync() {
		return async;
	}
	
	/**
	 * @see OperationalConfig#getSessionTimeout()
	 */
	@Override
	public int getSessionTimeout() {
		return sessionTimeout;
	}

	/**
	 * @see OperationalConfig#getThreadPoolSize()
	 */
	@Override
	public int getThreadPoolSize() {
		return threadPoolSize;
	}

	/**
	 * @see OperationalConfig#getThreadPoolKeepAliveTime()
	 */
	@Override
	public int getThreadPoolKeepAliveTime() {
		return threadPoolKeepAliveTime;
	}

	/**
	 * @see OperationalConfig#getConnectionPoolCoreSize()
	 */
	@Override
	public int getConnectionPoolCoreSize() {
		return connectionPoolCoreSize;
	}

	/**
	 * @see OperationalConfig#getConnectionPoolMaxSize()
	 */
	@Override
	public int getConnectionPoolMaxSize() {
		return connectionPoolMaxSize;
	}

	/**
	 * @see OperationalConfig#getConnectionPoolClaimTimeoutMillis()
	 */
	@Override
	public int getConnectionPoolClaimTimeoutMillis() {
		return connectionPoolClaimTimeoutMillis;
	}

	/**
	 * @see OperationalConfig#getConnectionPoolExpireAfterMillis()
	 */
	@Override
	public int getConnectionPoolExpireAfterMillis() {
		return connectionPoolExpireAfterMillis;
	}

	/**
	 * @see OperationalConfig#getConnectionPoolLoadBalancingStrategy()
	 */
	@NotNull
	@Override
	public LoadBalancingStrategy getConnectionPoolLoadBalancingStrategy() {
		return connectionPoolLoadBalancingStrategy;
	}

	/**
	 * @see OperationalConfig#isTransportModeLoggingOnly()
	 */
	@Override
	public boolean isTransportModeLoggingOnly() {
		return transportModeLoggingOnly;
	}
	
	/**
	 * @see OperationalConfig#isDebugLogging()
	 */
	@Override
	public boolean isDebugLogging() {
		return debugLogging;
	}
	
	/**
	 * @see OperationalConfig#getSslHostsToTrust()
	 */
	@NotNull
	@Override
	public List<String> getSslHostsToTrust() {
		return sslHostsToTrust;
	}

	/**
	 * @see OperationalConfig#isTrustAllSSLHost()
	 */
	@Override
	public boolean isTrustAllSSLHost() {
		return trustAllSSLHost;
	}

	/**
	 * @see OperationalConfig#isVerifyingServerIdentity()
	 */
	@Override
	public boolean isVerifyingServerIdentity() {
		return verifyingServerIdentity;
	}

	/**
	 * @see OperationalConfig#getProperties()
	 */
	@NotNull
	@Override
	public Properties getProperties() {
		return properties;
	}

	/**
	 * @see OperationalConfig#getClusterKey()
	 */
	@NotNull
	@Override
	public UUID getClusterKey() {
		return clusterKey;
	}

	/**
	 * @see OperationalConfig#getExecutorService()
	 */
	@NotNull
	@Override
	public ExecutorService getExecutorService() {
		return executorService;
	}

	/**
	 * @see OperationalConfig#executorServiceIsUserProvided()
	 */
	@Override
	public boolean executorServiceIsUserProvided() {
		return executorServiceIsUserProvided;
	}

	/**
	 * @see OperationalConfig#getCustomMailer()
	 */
	@Nullable
	@Override
	public CustomMailer getCustomMailer() {
		return customMailer;
	}
}