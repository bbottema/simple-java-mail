package org.simplejavamail.mailer.internal;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.hazlewood.connor.bottema.emailaddress.EmailAddressCriteria;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.config.ConfigLoader.Property;
import org.simplejavamail.internal.modules.ModuleLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.lang.String.format;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_CONNECTIONPOOL_CLUSTER_KEY;
import static org.simplejavamail.config.ConfigLoader.Property.PROXY_HOST;
import static org.simplejavamail.config.ConfigLoader.Property.PROXY_PASSWORD;
import static org.simplejavamail.config.ConfigLoader.Property.PROXY_USERNAME;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_SIGNING_KEYSTORE;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_SIGNING_KEYSTORE_PASSWORD;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_SIGNING_KEY_ALIAS;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_SIGNING_KEY_PASSWORD;
import static org.simplejavamail.config.ConfigLoader.getStringProperty;
import static org.simplejavamail.config.ConfigLoader.hasProperty;
import static org.simplejavamail.config.ConfigLoader.valueOrProperty;
import static org.simplejavamail.config.ConfigLoader.valueOrPropertyAsBoolean;
import static org.simplejavamail.config.ConfigLoader.valueOrPropertyAsInteger;
import static org.simplejavamail.config.ConfigLoader.valueOrPropertyAsString;
import static org.simplejavamail.internal.util.MiscUtil.checkArgumentNotEmpty;
import static org.simplejavamail.internal.util.MiscUtil.readInputStreamToBytes;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;
import static org.simplejavamail.internal.util.Preconditions.verifyNonnull;
import static org.simplejavamail.internal.util.Preconditions.verifyNonnullOrEmpty;
import static org.simplejavamail.mailer.internal.MailerException.ERROR_READING_FROM_FILE;
import static org.simplejavamail.mailer.internal.MailerException.ERROR_READING_SMIME_FROM_INPUTSTREAM;

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
	 * @see MailerGenericBuilder#signByDefaultWithSmime(Pkcs12Config)
	 */
	@Nullable
	private Pkcs12Config pkcs12ConfigForSmimeSigning;

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

		this.emailAddressCriteria = EmailAddressCriteria.RFC_COMPLIANT.clone();

		if (hasProperty(SMIME_SIGNING_KEYSTORE)) {
			signByDefaultWithSmime(Pkcs12Config.builder()
					.pkcs12Store(verifyNonnullOrEmpty(getStringProperty(SMIME_SIGNING_KEYSTORE)))
					.storePassword(checkNonEmptyArgument(getStringProperty(SMIME_SIGNING_KEYSTORE_PASSWORD), "Keystore password property"))
					.keyAlias(checkNonEmptyArgument(getStringProperty(SMIME_SIGNING_KEY_ALIAS), "Key alias property"))
					.keyPassword(checkNonEmptyArgument(getStringProperty(SMIME_SIGNING_KEY_PASSWORD), "Key password property"))
					.build());
		}
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
		return new EmailGovernanceImpl(verifyNonnull(getEmailAddressCriteria()), getPkcs12ConfigForSmimeSigning());
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
	 * @param pkcs12StoreFile The file containing the keystore
	 * @param storePassword  The password to get keys from the store
	 * @param keyAlias The key we need for signing
	 * @param keyPassword The password for the key
	 *
	 * @see MailerGenericBuilder#signByDefaultWithSmime(File, String, String, String)
	 */
	@Override
	@SuppressFBWarnings(value = "OBL_UNSATISFIED_OBLIGATION", justification = "Input stream being created should not be closed here")
	public T signByDefaultWithSmime(@NotNull final File pkcs12StoreFile, @NotNull final String storePassword, @NotNull final String keyAlias, @NotNull final String keyPassword) {
		try {
			return signByDefaultWithSmime(new FileInputStream(pkcs12StoreFile), storePassword, keyAlias, keyPassword);
		} catch (IOException e) {
			throw new MailerException(format(ERROR_READING_FROM_FILE, pkcs12StoreFile), e);
		}
	}

	/**
	 * @param pkcs12StoreStream The data (file) input stream containing the keystore
	 * @param storePassword  The password to get keys from the store
	 * @param keyAlias The key we need for signing
	 * @param keyPassword The password for the key
	 *
	 * @see MailerGenericBuilder#signByDefaultWithSmime(InputStream, String, String, String)
	 */
	@Override
	public T signByDefaultWithSmime(@NotNull final InputStream pkcs12StoreStream, @NotNull final String storePassword, @NotNull final String keyAlias, @NotNull final String keyPassword) {
		final byte[] pkcs12StoreData;
		try {
			pkcs12StoreData = readInputStreamToBytes(pkcs12StoreStream);
		} catch (IOException e) {
			throw new MailerException(ERROR_READING_SMIME_FROM_INPUTSTREAM, e);
		}
		return signByDefaultWithSmime(pkcs12StoreData, storePassword, keyAlias, keyPassword);
	}

	/**
	 * @param pkcs12StoreData The data (file) input stream containing the keystore
	 * @param storePassword  The password to get keys from the store
	 * @param keyAlias The key we need for signing
	 * @param keyPassword The password for the key
	 *
	 * @see MailerGenericBuilder#signByDefaultWithSmime(InputStream, String, String, String)
	 */
	@Override
	public T signByDefaultWithSmime(@NotNull final byte[] pkcs12StoreData, @NotNull final String storePassword, @NotNull final String keyAlias, @NotNull final String keyPassword) {
		return signByDefaultWithSmime(Pkcs12Config.builder()
				.pkcs12Store(pkcs12StoreData)
				.storePassword(storePassword)
				.keyAlias(keyAlias)
				.keyPassword(keyPassword)
				.build());
	}

	/**
	 * @see MailerGenericBuilder#signByDefaultWithSmime(Pkcs12Config)
	 */
	@Override
	public T signByDefaultWithSmime(@NotNull final Pkcs12Config pkcs12Config) {
		this.pkcs12ConfigForSmimeSigning = pkcs12Config;
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
	 * @see MailerGenericBuilder#clearEmailAddressCriteria()
	 */
	@Override
	public T clearEmailAddressCriteria() {
		return withEmailAddressCriteria(EnumSet.noneOf(EmailAddressCriteria.class));
	}

	/**
	 * @see MailerGenericBuilder#clearSignByDefaultWithSmime()
	 */
	@Override
	public T clearSignByDefaultWithSmime() {
		this.pkcs12ConfigForSmimeSigning = null;
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
	 * @see MailerGenericBuilder#getPkcs12ConfigForSmimeSigning()
	 */
	@Override
	@Nullable
	public Pkcs12Config getPkcs12ConfigForSmimeSigning() {
		return pkcs12ConfigForSmimeSigning;
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