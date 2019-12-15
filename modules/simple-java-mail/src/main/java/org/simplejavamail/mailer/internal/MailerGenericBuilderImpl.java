/*
 * Copyright (C) 2009 Benny Bottema (benny@bennybottema.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.simplejavamail.mailer.internal;

import org.hazlewood.connor.bottema.emailaddress.EmailAddressCriteria;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.config.ConfigLoader.Property;
import org.simplejavamail.internal.modules.ModuleLoader;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_CONNECTIONPOOL_CLUSTER_KEY;
import static org.simplejavamail.config.ConfigLoader.Property.PROXY_HOST;
import static org.simplejavamail.config.ConfigLoader.Property.PROXY_PASSWORD;
import static org.simplejavamail.config.ConfigLoader.Property.PROXY_USERNAME;
import static org.simplejavamail.config.ConfigLoader.getStringProperty;
import static org.simplejavamail.config.ConfigLoader.hasProperty;
import static org.simplejavamail.config.ConfigLoader.valueOrProperty;
import static org.simplejavamail.config.ConfigLoader.valueOrPropertyAsBoolean;
import static org.simplejavamail.config.ConfigLoader.valueOrPropertyAsInteger;
import static org.simplejavamail.config.ConfigLoader.valueOrPropertyAsString;
import static org.simplejavamail.internal.util.MiscUtil.checkArgumentNotEmpty;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;
import static org.simplejavamail.internal.util.Preconditions.assumeNonNull;

/**
 * @see MailerGenericBuilder
 */
@SuppressWarnings({"UnusedReturnValue", "unchecked"})
abstract class MailerGenericBuilderImpl<T extends MailerGenericBuilderImpl<?>> implements MailerGenericBuilder<T> {
	
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
	 * @see MailerGenericBuilder#withSessionTimeout(Integer)
	 */
	@NotNull
	private Integer sessionTimeout;
	
	/**
	 * @see MailerGenericBuilder#withEmailAddressCriteria(EnumSet)
	 */
	@NotNull
	private EnumSet<EmailAddressCriteria> emailAddressCriteria;

	/**
	 * @see MailerGenericBuilder#withExecutorService(ExecutorService)
	 */
	@NotNull
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
				? UUID.fromString(assumeNonNull(getStringProperty(DEFAULT_CONNECTIONPOOL_CLUSTER_KEY)))
				: UUID.randomUUID(); // <-- this makes sure it won't form a cluster with another mailer

		this.proxyPort 								= assumeNonNull(valueOrPropertyAsInteger(null, Property.PROXY_PORT, DEFAULT_PROXY_PORT));
		this.proxyBridgePort 						= assumeNonNull(valueOrPropertyAsInteger(null, Property.PROXY_SOCKS5BRIDGE_PORT, DEFAULT_PROXY_BRIDGE_PORT));
		this.debugLogging 							= assumeNonNull(valueOrPropertyAsBoolean(null, Property.JAVAXMAIL_DEBUG, DEFAULT_JAVAXMAIL_DEBUG));
		this.sessionTimeout 						= assumeNonNull(valueOrPropertyAsInteger(null, Property.DEFAULT_SESSION_TIMEOUT_MILLIS, DEFAULT_SESSION_TIMEOUT_MILLIS));
		this.trustAllSSLHost 						= assumeNonNull(valueOrPropertyAsBoolean(null, Property.DEFAULT_TRUST_ALL_HOSTS, DEFAULT_TRUST_ALL_HOSTS));
		this.verifyingServerIdentity 				= assumeNonNull(valueOrPropertyAsBoolean(null, Property.DEFAULT_VERIFY_SERVER_IDENTITY, DEFAULT_VERIFY_SERVER_IDENTITY));
		this.threadPoolSize 						= assumeNonNull(valueOrPropertyAsInteger(null, Property.DEFAULT_POOL_SIZE, DEFAULT_POOL_SIZE));
		this.threadPoolKeepAliveTime 				= assumeNonNull(valueOrPropertyAsInteger(null, Property.DEFAULT_POOL_KEEP_ALIVE_TIME, DEFAULT_POOL_KEEP_ALIVE_TIME));
		this.connectionPoolCoreSize 				= assumeNonNull(valueOrPropertyAsInteger(null, Property.DEFAULT_CONNECTIONPOOL_CORE_SIZE, DEFAULT_CONNECTIONPOOL_CORE_SIZE));
		this.connectionPoolMaxSize 					= assumeNonNull(valueOrPropertyAsInteger(null, Property.DEFAULT_CONNECTIONPOOL_MAX_SIZE, DEFAULT_CONNECTIONPOOL_MAX_SIZE));
		this.connectionPoolClaimTimeoutMillis 		= assumeNonNull(valueOrPropertyAsInteger(null, Property.DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT_MILLIS, DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT_MILLIS));
		this.connectionPoolExpireAfterMillis 		= assumeNonNull(valueOrPropertyAsInteger(null, Property.DEFAULT_CONNECTIONPOOL_EXPIREAFTER_MILLIS, DEFAULT_CONNECTIONPOOL_EXPIREAFTER_MILLIS));
		this.connectionPoolLoadBalancingStrategy	= assumeNonNull(valueOrProperty(null, Property.DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY, LoadBalancingStrategy.valueOf(DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY)));
		this.transportModeLoggingOnly 				= assumeNonNull(valueOrPropertyAsBoolean(null, Property.TRANSPORT_MODE_LOGGING_ONLY, DEFAULT_TRANSPORT_MODE_LOGGING_ONLY));

		final String trustedHosts = valueOrPropertyAsString(null, Property.DEFAULT_TRUSTED_HOSTS, null);
		if (trustedHosts != null) {
			this.sslHostsToTrust = Arrays.asList(trustedHosts.split(";"));
		}

		this.emailAddressCriteria = EmailAddressCriteria.RFC_COMPLIANT.clone();

		this.executorService = determineDefaultExecutorService();
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
				getSslHostsToTrust(),
				isTrustAllSSLHost(),
				isVerifyingServerIdentity(),
				getExecutorService(),
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
	 * @see MailerGenericBuilder#withSessionTimeout(Integer)
	 */
	@Override
	public T withSessionTimeout(@NotNull final Integer sessionTimeout) {
		this.sessionTimeout = sessionTimeout;
		return (T) this;
	}
	
	/**
	 * @see MailerGenericBuilder#withEmailAddressCriteria(EnumSet)
	 */
	@Override
	public T withEmailAddressCriteria(@NotNull final EnumSet<EmailAddressCriteria> emailAddressCriteria) {
		this.emailAddressCriteria = emailAddressCriteria.clone();
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
	 * @see MailerGenericBuilder#resetEmailAddressCriteria()
	 */
	@Override
	public T resetEmailAddressCriteria() {
		return withEmailAddressCriteria(EmailAddressCriteria.RFC_COMPLIANT);
	}

	/**
	 * @see MailerGenericBuilder#resetExecutorService()
	 */
	@Override
	public T resetExecutorService() {
		this.executorService = determineDefaultExecutorService();
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
	 * @see MailerGenericBuilder#clearEmailAddressCriteria()
	 */
	@Override
	public T clearEmailAddressCriteria() {
		return withEmailAddressCriteria(EnumSet.noneOf(EmailAddressCriteria.class));
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
	 * @see MailerGenericBuilder#getSessionTimeout()
	 */
	@Override
	@NotNull
	public Integer getSessionTimeout() {
		return sessionTimeout;
	}
	
	/**
	 * @see MailerGenericBuilder#getEmailAddressCriteria()
	 */
	@Override
	@NotNull
	public EnumSet<EmailAddressCriteria> getEmailAddressCriteria() {
		return emailAddressCriteria;
	}

	/**
	 * @see MailerGenericBuilder#getExecutorService()
	 */
	@Override
	@NotNull
	public ExecutorService getExecutorService() {
		return executorService;
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