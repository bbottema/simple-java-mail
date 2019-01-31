package org.simplejavamail.mailer.internal;

import org.hazlewood.connor.bottema.emailaddress.EmailAddressCriteria;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.ConfigLoader.Property;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.simplejavamail.internal.util.MiscUtil.checkArgumentNotEmpty;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;
import static org.simplejavamail.internal.util.Preconditions.assumeNonNull;
import static org.simplejavamail.config.ConfigLoader.Property.PROXY_HOST;
import static org.simplejavamail.config.ConfigLoader.Property.PROXY_PASSWORD;
import static org.simplejavamail.config.ConfigLoader.Property.PROXY_PORT;
import static org.simplejavamail.config.ConfigLoader.Property.PROXY_USERNAME;
import static org.simplejavamail.config.ConfigLoader.hasProperty;

/**
 * @see MailerGenericBuilder
 */
@SuppressWarnings({"UnusedReturnValue", "unchecked"})
public abstract class MailerGenericBuilderImpl<T extends MailerGenericBuilderImpl<?>> implements MailerGenericBuilder<T> {
	
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
	@Nonnull
	private Integer proxyBridgePort;
	
	/**
	 * @see MailerGenericBuilder#withDebugLogging(Boolean)
	 */
	private boolean debugLogging;
	
	/**
	 * @see MailerGenericBuilder#withSessionTimeout(Integer)
	 */
	@Nonnull
	private Integer sessionTimeout;
	
	/**
	 * @see MailerGenericBuilder#withEmailAddressCriteria(EnumSet)
	 */
	@Nonnull
	private EnumSet<EmailAddressCriteria> emailAddressCriteria;
	
	/**
	 * @see MailerGenericBuilder#withThreadPoolSize(Integer)
	 */
	@Nonnull
	private Integer threadPoolSize;
	
	/**
	 * @see MailerGenericBuilder#trustingSSLHosts(String...)
	 */
	@Nonnull
	private List<String> sslHostsToTrust = new ArrayList<>();
	
	/**
	 * @see MailerGenericBuilder#trustingAllHosts(boolean)
	 */
	private boolean trustAllSSLHost;
	
	/**
	 * @see MailerGenericBuilder#withProperties(Properties)
	 */
	@Nonnull
	private final Properties properties = new Properties();
	
	/**
	 * @see MailerGenericBuilder#withTransportModeLoggingOnly(Boolean)
	 */
	private boolean transportModeLoggingOnly;
	
	/**
	 * Sets defaults configured for proxy host, proxy port, proxy username, proxy password and proxy bridge port (used in authenticated proxy).
	 * <p>
	 * <strong>Note:</strong> Any builder methods invoked after this will override the default value.
	 */
	MailerGenericBuilderImpl() {
		if (hasProperty(PROXY_HOST)) {
			withProxyHost(ConfigLoader.getStringProperty(PROXY_HOST));
		}
		if (hasProperty(PROXY_PORT)) {
			withProxyPort(ConfigLoader.getIntegerProperty(PROXY_PORT));
		}
		if (hasProperty(PROXY_USERNAME)) {
			withProxyUsername(ConfigLoader.getStringProperty(PROXY_USERNAME));
		}
		if (hasProperty(PROXY_PASSWORD)) {
			withProxyPassword(ConfigLoader.getStringProperty(PROXY_PASSWORD));
		}
		
		this.proxyBridgePort = assumeNonNull(ConfigLoader.valueOrPropertyAsInteger(null, Property.PROXY_SOCKS5BRIDGE_PORT, DEFAULT_PROXY_BRIDGE_PORT));
		this.debugLogging = assumeNonNull(ConfigLoader.valueOrPropertyAsBoolean(null, Property.JAVAXMAIL_DEBUG, DEFAULT_JAVAXMAIL_DEBUG));
		this.sessionTimeout = assumeNonNull(ConfigLoader.valueOrPropertyAsInteger(null, Property.DEFAULT_SESSION_TIMEOUT_MILLIS, DEFAULT_SESSION_TIMEOUT_MILLIS));
		this.threadPoolSize = assumeNonNull(ConfigLoader.valueOrPropertyAsInteger(null, Property.DEFAULT_POOL_SIZE, DEFAULT_POOL_SIZE));
		this.transportModeLoggingOnly = assumeNonNull(ConfigLoader.valueOrPropertyAsBoolean(null, Property.TRANSPORT_MODE_LOGGING_ONLY, DEFAULT_TRANSPORT_MODE_LOGGING_ONLY));
		
		this.emailAddressCriteria = EmailAddressCriteria.RFC_COMPLIANT.clone();
		this.trustAllSSLHost = true;
	}
	
	/**
	 * For internal use.
	 */
	@SuppressWarnings("deprecation")
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
	@SuppressWarnings("deprecation")
	OperationalConfig buildOperationalConfig() {
		return new OperationalConfigImpl(isAsync(), getProperties(), getSessionTimeout(), getThreadPoolSize(), isTransportModeLoggingOnly(), isDebugLogging(),
				getSslHostsToTrust(), isTrustAllSSLHost());
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
	// TODO take default port from transport strategy
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
	public T withProxyBridgePort(@Nonnull final Integer proxyBridgePort) {
		this.proxyBridgePort = proxyBridgePort;
		return (T) this;
	}
	
	/**
	 * @see MailerGenericBuilder#withDebugLogging(Boolean)
	 */
	@Override
	public T withDebugLogging(@Nonnull final Boolean debugLogging) {
		this.debugLogging = debugLogging;
		return (T) this;
	}
	
	/**
	 * @see MailerGenericBuilder#withSessionTimeout(Integer)
	 */
	@Override
	public T withSessionTimeout(@Nonnull final Integer sessionTimeout) {
		this.sessionTimeout = sessionTimeout;
		return (T) this;
	}
	
	/**
	 * @see MailerGenericBuilder#withEmailAddressCriteria(EnumSet)
	 */
	@Override
	public T withEmailAddressCriteria(@Nonnull final EnumSet<EmailAddressCriteria> emailAddressCriteria) {
		this.emailAddressCriteria = emailAddressCriteria.clone();
		return (T) this;
	}
	
	/**
	 * @see MailerGenericBuilder#withThreadPoolSize(Integer)
	 */
	@Override
	public T withThreadPoolSize(@Nonnull final Integer defaultPoolSize) {
		this.threadPoolSize = defaultPoolSize;
		return (T) this;
	}
	
	/**
	 * @see MailerGenericBuilder#withTransportModeLoggingOnly(Boolean)
	 */
	@Override
	public T withTransportModeLoggingOnly(@Nonnull final Boolean transportModeLoggingOnly) {
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
	 * @see MailerGenericBuilder#withProperties(Properties)
	 */
	@Override
	public T withProperties(@Nonnull final Properties properties) {
		for (Map.Entry<Object, Object> property : properties.entrySet()) {
			this.properties.put(property.getKey(), property.getValue());
		}
		return (T) this;
	}
	
	/**
	 * @see MailerGenericBuilder#withProperties(Map)
	 */
	@Override
	public T withProperties(@Nonnull final Map<String, String> properties) {
		for (Map.Entry<String, String> property : properties.entrySet()) {
			this.properties.put(property.getKey(), property.getValue());
		}
		return (T) this;
	}
	
	/**
	 * @see MailerGenericBuilder#withProperty(String, Object)
	 */
	@Override
	public T withProperty(@Nonnull final String propertyName, @Nullable final Object propertyValue) {
		if (propertyValue == null) {
			this.properties.remove(propertyName);
		} else {
			this.properties.put(propertyName, propertyValue.toString());
		}
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
	 * @see MailerGenericBuilder#resetEmailAddressCriteria()
	 */
	@Override
	public T resetEmailAddressCriteria() {
		return withEmailAddressCriteria(EmailAddressCriteria.RFC_COMPLIANT);
	}
	
	/**
	 * @see MailerGenericBuilder#resetThreadpoolSize()
	 */
	@Override
	public T resetThreadpoolSize() {
		return withThreadPoolSize(DEFAULT_POOL_SIZE);
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
	@Nonnull
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
	@Nonnull
	public Integer getSessionTimeout() {
		return sessionTimeout;
	}
	
	/**
	 * @see MailerGenericBuilder#getEmailAddressCriteria()
	 */
	@Override
	@Nonnull
	public EnumSet<EmailAddressCriteria> getEmailAddressCriteria() {
		return emailAddressCriteria;
	}
	
	/**
	 * @see MailerGenericBuilder#getThreadPoolSize()
	 */
	@Override
	@Nonnull
	public Integer getThreadPoolSize() {
		return threadPoolSize;
	}
	
	/**
	 * @see MailerGenericBuilder#getSslHostsToTrust()
	 */
	@Override
	@Nonnull
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
	@Nonnull
	public Properties getProperties() {
		return properties;
	}
}