package org.simplejavamail.mailer;

import org.hazlewood.connor.bottema.emailaddress.EmailAddressCriteria;
import org.simplejavamail.internal.clisupport.annotation.CliOption;
import org.simplejavamail.mailer.internal.mailsender.OperationalConfig;
import org.simplejavamail.mailer.internal.mailsender.ProxyConfig;
import org.simplejavamail.util.ConfigLoader;
import org.simplejavamail.util.ConfigLoader.Property;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.mail.Session;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.simplejavamail.internal.clisupport.model.CliCommandType.connect;
import static org.simplejavamail.internal.clisupport.model.CliCommandType.send;
import static org.simplejavamail.internal.util.MiscUtil.checkArgumentNotEmpty;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;
import static org.simplejavamail.util.ConfigLoader.Property.PROXY_HOST;
import static org.simplejavamail.util.ConfigLoader.Property.PROXY_PASSWORD;
import static org.simplejavamail.util.ConfigLoader.Property.PROXY_PORT;
import static org.simplejavamail.util.ConfigLoader.Property.PROXY_USERNAME;
import static org.simplejavamail.util.ConfigLoader.getProperty;
import static org.simplejavamail.util.ConfigLoader.hasProperty;

/**
 * Builder superclass which contains API to take care of all generic Mailer properties unrelated to the SMTP server
 * (host, port, username, password and transport strategy).
 * <p>
 * To start a new Mailer builder, refer to {@link MailerBuilder}.
 */
@SuppressWarnings({"UnusedReturnValue", "unchecked", "WeakerAccess"})
public abstract class MailerGenericBuilder<T extends MailerGenericBuilder> {
	
	/**
	 * The default maximum timeout value for the transport socket is {@value #DEFAULT_SESSION_TIMEOUT_MILLIS} milliseconds (affects socket connect-,
	 * read- and write timeouts). Can be overridden from a config file or through System variable.
	 */
	@SuppressWarnings("JavaDoc")
	public static final int DEFAULT_SESSION_TIMEOUT_MILLIS = 60_000;
	
	/**
	 * For multi-threaded scenario's where a batch of emails sent asynchronously, the default maximum number of threads is {@value
	 * #DEFAULT_POOL_SIZE}. Can be overridden from a config file or through System variable.
	 */
	@SuppressWarnings("JavaDoc")
	public static final int DEFAULT_POOL_SIZE = 10;
	
	/**
	 * The temporary intermediary SOCKS5 relay server bridge is a server that sits in between JavaMail and the remote proxy. Default port is {@value
	 * #DEFAULT_PROXY_BRIDGE_PORT}.
	 */
	@SuppressWarnings("JavaDoc")
	public static final int DEFAULT_PROXY_BRIDGE_PORT = 1081;
	
	/**
	 * Defaults to {@value DEFAULT_TRANSPORT_MODE_LOGGING_ONLY}, sending mails rather than just only logging the mails.
	 */
	@SuppressWarnings("JavaDoc")
	public static final boolean DEFAULT_TRANSPORT_MODE_LOGGING_ONLY = false;
	
	/**
	 * @see #withProxyHost(String)
	 */
	private String proxyHost;
	
	/**
	 * @see #withProxyPort(Integer)
	 */
	private Integer proxyPort;
	
	/**
	 * @see #withProxyUsername(String)
	 */
	private String proxyUsername;
	
	/**
	 * @see #withProxyPassword(String)
	 */
	private String proxyPassword;
	
	/**
	 * @see #withProxyBridgePort(Integer)
	 */
	private Integer proxyBridgePort;
	
	/**
	 * @see #withDebugLogging(Boolean)
	 */
	private Boolean debugLogging;
	
	/**
	 * @see #withSessionTimeout(Integer)
	 */
	private Integer sessionTimeout;
	
	/**
	 * @see #withEmailAddressCriteria(EnumSet)
	 */
	private EnumSet<EmailAddressCriteria> emailAddressCriteria;
	
	/**
	 * @see #withThreadPoolSize(Integer)
	 */
	private Integer threadPoolSize;
	
	/**
	 * @see #trustingSSLHosts(String...)
	 */
	private List<String> sslHostsToTrust = new ArrayList<>();
	
	/**
	 * @see #trustingAllHosts(Boolean)
	 */
	private Boolean trustAllSSLHost;
	
	/**
	 * @see #withProperties(Properties)
	 */
	private final Properties properties = new Properties();
	
	/**
	 * Determines whether at the very last moment an email is sent out using JavaMail's native API or whether the email is simply only logged.
	 */
	private Boolean transportModeLoggingOnly;
	
	/**
	 * Sets defaults configured for proxy host, proxy port, proxy username, proxy password and proxy bridge port (used in authenticated proxy).
	 * <p>
	 * <strong>Note:</strong> Any builder methods invoked after this will override the default value.
	 */
	MailerGenericBuilder() {
		if (hasProperty(PROXY_HOST)) {
			withProxyHost((String) getProperty(PROXY_HOST));
		}
		if (hasProperty(PROXY_PORT)) {
			withProxyPort((Integer) getProperty(PROXY_PORT));
		}
		if (hasProperty(PROXY_USERNAME)) {
			withProxyUsername((String) getProperty(PROXY_USERNAME));
		}
		if (hasProperty(PROXY_PASSWORD)) {
			withProxyPassword((String) getProperty(PROXY_PASSWORD));
		}
		
		withProxyBridgePort(ConfigLoader.valueOrProperty(null, Property.PROXY_SOCKS5BRIDGE_PORT, DEFAULT_PROXY_BRIDGE_PORT));
		withDebugLogging(ConfigLoader.valueOrProperty(null, Property.JAVAXMAIL_DEBUG, false));
		withSessionTimeout(ConfigLoader.valueOrProperty(null, Property.DEFAULT_SESSION_TIMEOUT_MILLIS, DEFAULT_SESSION_TIMEOUT_MILLIS));
		withThreadPoolSize(ConfigLoader.valueOrProperty(null, Property.DEFAULT_POOL_SIZE, DEFAULT_POOL_SIZE));
		withTransportModeLoggingOnly(ConfigLoader.valueOrProperty(null, Property.TRANSPORT_MODE_LOGGING_ONLY, DEFAULT_TRANSPORT_MODE_LOGGING_ONLY));
		
		withEmailAddressCriteria(EmailAddressCriteria.RFC_COMPLIANT);
		trustingAllHosts(true);
	}
	
	/**
	 * For internal use.
	 */
	ProxyConfig buildProxyConfig() {
		validateProxy();
		return new ProxyConfig(getProxyHost(), getProxyPort(), getProxyUsername(), getProxyPassword(), getProxyBridgePort());
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
		}
	}
	
	/**
	 * For internal use.
	 */
	OperationalConfig buildOperationalConfig() {
		return new OperationalConfig(getProperties(), getSessionTimeout(), getThreadPoolSize(), getTransportModeLoggingOnly(), getDebugLogging(),
				getSslHostsToTrust(), getTrustAllSSLHost());
	}
	
	/**
	 * Delegates to {@link #withProxyHost(String)} and {@link #withProxyPort(Integer)}.
	 */
	public T withProxy(@Nullable final String proxyHost, @Nullable final Integer proxyPort) {
		return (T) withProxyHost(proxyHost)
				.withProxyPort(proxyPort);
	}
	
	/**
	 * Delegates to:
	 * <ol>
	 * <li>{@link #withProxyHost(String)}</li>
	 * <li>{@link #withProxyPort(Integer)}</li>
	 * <li>{@link #withProxyUsername(String)}</li>
	 * <li>{@link #withProxyPassword(String)}</li>
	 * </ol>
	 */
	public T withProxy(@Nullable final String proxyHost, @Nullable final Integer proxyPort, @Nullable final String proxyUsername, @Nullable final String proxyPassword) {
		return (T) withProxyHost(proxyHost)
				.withProxyPort(proxyPort)
				.withProxyUsername(proxyUsername)
				.withProxyPassword(proxyPassword);
	}
	
	/**
	 * Sets the optional proxy host, which will override any default that might have been set (through properties file or programmatically).
	 */
	public T withProxyHost(@Nullable final String proxyHost) {
		this.proxyHost = proxyHost;
		return (T) this;
	}
	
	/**
	 * Sets the proxy port, which will override any default that might have been set (through properties file or programmatically).
	 * <p>
	 * Proxy port is required if a proxyHost has been configured.
	 */
	// TODO take default port from transport strategy
	public T withProxyPort(@Nullable final Integer proxyPort) {
		this.proxyPort = proxyPort;
		return (T) this;
	}
	
	/**
	 * Sets the optional username to authenticate with the proxy.
	 * <p>
	 * If set, Simple Java Mail will use its built in proxy bridge to perform the SOCKS authentication, as the underlying JavaMail framework doesn't
	 * support this directly.
	 * <p>
	 * The path will be: <br>
	 * {@code Simple Java Mail -> JavaMail -> anonymous authentication with local proxy bridge -> full authentication with remote SOCKS
	 * proxy}.
	 */
	public T withProxyUsername(@Nullable final String proxyUsername) {
		this.proxyUsername = proxyUsername;
		return (T) this;
	}
	
	/**
	 * Sets the optional password to authenticate with the proxy.
	 *
	 * @see #withProxyUsername(String)
	 */
	public T withProxyPassword(@Nullable final String proxyPassword) {
		this.proxyPassword = proxyPassword;
		return (T) this;
	}
	
	/**
	 * Relevant only when using username authentication with a proxy.
	 * <p>
	 * Overrides the default for the intermediary SOCKS5 relay server bridge, which is a server that sits in between JavaMail and the remote proxy.
	 * Defaults to {@value DEFAULT_PROXY_BRIDGE_PORT} if no custom default property was configured.
	 *
	 * @see #withProxyUsername(String)
	 */
	public T withProxyBridgePort(@Nullable final Integer proxyBridgePort) {
		this.proxyBridgePort = proxyBridgePort;
		return (T) this;
	}
	
	/**
	 * This flag is set on the Session instance through {@link Session#setDebug(boolean)} so that it generates debug information. To get more
	 * information out of the underlying JavaMail framework or out of Simple Java Mail, increase logging config of your chosen logging framework.
	 */
	public T withDebugLogging(@Nullable final Boolean debugLogging) {
		this.debugLogging = debugLogging;
		return (T) this;
	}
	
	/**
	 * Controls the timeout to use when sending emails (affects socket connect-, read- and write timeouts).
	 */
	public T withSessionTimeout(@Nullable final Integer sessionTimeout) {
		this.sessionTimeout = sessionTimeout;
		return (T) this;
	}
	
	/**
	 * Sets the email address validation restrictions when validating and sending emails using the current <code>Mailer</code> instance.
	 * <p>
	 * Defaults to {@link EmailAddressCriteria#RFC_COMPLIANT} if not overridden with a ({@code null}) value.
	 *
	 * @see EmailAddressCriteria
	 * @see #clearEmailAddressCriteria()
	 * @see #resetEmailAddressCriteria()
	 */
	public T withEmailAddressCriteria(@Nonnull final EnumSet<EmailAddressCriteria> emailAddressCriteria) {
		this.emailAddressCriteria = emailAddressCriteria.clone();
		return (T) this;
	}
	
	/**
	 * Controls the maximum number of threads when sending emails in async fashion. Defaults to {@link #DEFAULT_POOL_SIZE}.
	 *
	 * @see #resetThreadpoolSize()
	 */
	public T withThreadPoolSize(@Nonnull final Integer defaultPoolSize) {
		this.threadPoolSize = defaultPoolSize;
		return (T) this;
	}
	
	/**
	 * Determines whether at the very last moment an email is sent out using JavaMail's native API or whether the email is simply only logged.
	 *
	 * @see #resetTransportModeLoggingOnly()
	 */
	public T withTransportModeLoggingOnly(@Nonnull final Boolean transportModeLoggingOnly) {
		this.transportModeLoggingOnly = transportModeLoggingOnly;
		return (T) this;
	}
	
	/**
	 * Configures the new session to only accept server certificates issued to one of the provided hostnames, <strong>and disables certificate issuer
	 * validation.</strong>
	 * <p>
	 * Passing an empty list resets the current session's trust behavior to the default, and is equivalent to never calling this method in the first
	 * place.
	 * <p>
	 * <strong>Security warning:</strong> Any certificate matching any of the provided host names will be accepted, regardless of the certificate
	 * issuer; attackers can abuse this behavior by serving a matching self-signed certificate during a man-in-the-middle attack.
	 * <p>
	 * This method sets the property {@code mail.smtp.ssl.trust} to a space-separated list of the provided {@code hosts}. If the provided list is
	 * empty, {@code mail.smtp.ssl.trust} is unset.
	 *
	 * @see <a href="https://javaee.github.io/javamail/docs/api/com/sun/mail/smtp/package-summary.html#mail.smtp.ssl.trust"><code>mail.smtp.ssl.trust</code></a>
	 * @see #trustingAllHosts(Boolean)
	 */
	public T trustingSSLHosts(String... sslHostsToTrust) {
		this.sslHostsToTrust = Arrays.asList(sslHostsToTrust);
		return (T) this;
	}
	
	/**
	 * Configures the current session to trust all hosts and don't validate any SSL keys. The property "mail.smtp(s).ssl.trust" is set to "*".
	 * <p>
	 * Refer to https://javamail.java.net/nonav/docs/api/com/sun/mail/smtp/package-summary.html#mail.smtp.ssl.trust
	 *
	 * @see #trustingSSLHosts(String...)
	 */
	public T trustingAllHosts(@Nonnull final Boolean trustAllHosts) {
		this.trustAllSSLHost = trustAllHosts;
		return (T) this;
	}
	
	/**
	 * Adds the given properties to the total list applied to the {@link Session} when building a mailer.
	 *
	 * @see #withProperties(Map)
	 * @see #withProperty(String, String)
	 * @see #clearProperties()
	 */
	public T withProperties(@Nonnull final Properties properties) {
		for (Map.Entry<Object, Object> property : properties.entrySet()) {
			this.properties.put(property.getKey(), property.getValue());
		}
		return (T) this;
	}
	
	/**
	 * @see #withProperties(Properties)
	 * @see #clearProperties()
	 */
	public T withProperties(@Nonnull final Map<String, String> properties) {
		for (Map.Entry<String, String> property : properties.entrySet()) {
			this.properties.put(property.getKey(), property.getValue());
		}
		return (T) this;
	}
	
	/**
	 * Sets property or removes it if the provided value is <code>null</code>. If provided, the value is always converted <code>toString()</code>.
	 *
	 * @see #withProperties(Properties)
	 * @see #clearProperties()
	 */
	public T withProperty(@Nonnull final String propertyName, @Nullable final Object propertyValue) {
		if (propertyValue == null) {
			this.properties.remove(propertyName);
		} else {
			this.properties.put(propertyName, propertyValue.toString());
		}
		return (T) this;
	}
	
	/**
	 * Resets session time to its default ({@value DEFAULT_SESSION_TIMEOUT_MILLIS}).
	 *
	 * @see #withSessionTimeout(Integer)
	 */
	public T resetSessionTimeout() {
		return withSessionTimeout(DEFAULT_SESSION_TIMEOUT_MILLIS);
	}
	
	/**
	 * Resets emailAddressCriteria to {@link EmailAddressCriteria#RFC_COMPLIANT}.
	 *
	 * @see #withEmailAddressCriteria(EnumSet)
	 * @see #clearEmailAddressCriteria()
	 */
	public T resetEmailAddressCriteria() {
		return withEmailAddressCriteria(EmailAddressCriteria.RFC_COMPLIANT);
	}
	
	/**
	 * Resets threadPoolSize to its default ({@value #DEFAULT_POOL_SIZE}).
	 *
	 * @see #withThreadPoolSize(Integer)
	 */
	public T resetThreadpoolSize() {
		return withThreadPoolSize(DEFAULT_POOL_SIZE);
	}
	
	/**
	 * Resets transportModeLoggingOnly to {@value #DEFAULT_TRANSPORT_MODE_LOGGING_ONLY}.
	 *
	 * @see #withTransportModeLoggingOnly(Boolean)
	 */
	public T resetTransportModeLoggingOnly() {
		return withTransportModeLoggingOnly(DEFAULT_TRANSPORT_MODE_LOGGING_ONLY);
	}
	
	/**
	 * Empties all proxy configuration.
	 */
	@CliOption(description = "Empties all proxy configuration.", applicableRootCommands = { send, connect })
	public T clearProxy() {
		return (T) withProxy(null, null, null, null)
				.withProxyBridgePort(null);
	}
	
	/**
	 * Removes all email address criteria, meaning validation won't take place.
	 *
	 * @see #withEmailAddressCriteria(EnumSet)
	 * @see #resetEmailAddressCriteria()
	 */
	public T clearEmailAddressCriteria() {
		return withEmailAddressCriteria(EnumSet.noneOf(EmailAddressCriteria.class));
	}
	
	/**
	 * Removes all trusted hosts from the list.
	 *
	 * @see #trustingSSLHosts(String...)
	 */
	public T clearTrustedSSLHosts() {
		return trustingSSLHosts();
	}
	
	/**
	 * Removes all properties.
	 *
	 * @see #withProperties(Properties)
	 */
	public T clearProperties() {
		properties.clear();
		return (T) this;
	}
	
	public abstract Mailer buildMailer();
	
	/**
	 * @see #withProxyHost(String)
	 */
	public String getProxyHost() {
		return proxyHost;
	}
	
	/**
	 * @see #withProxyPort(Integer)
	 */
	public Integer getProxyPort() {
		return proxyPort;
	}
	
	/**
	 * @see #withProxyUsername(String)
	 */
	public String getProxyUsername() {
		return proxyUsername;
	}
	
	/**
	 * @see #withProxyPassword(String)
	 */
	public String getProxyPassword() {
		return proxyPassword;
	}
	
	/**
	 * @see #withProxyBridgePort(Integer)
	 */
	public Integer getProxyBridgePort() {
		return proxyBridgePort;
	}
	
	/**
	 * @see #withDebugLogging(Boolean)
	 */
	public Boolean getDebugLogging() {
		return debugLogging;
	}
	
	/**
	 * @see #withSessionTimeout(Integer)
	 */
	public Integer getSessionTimeout() {
		return sessionTimeout;
	}
	
	/**
	 * @see #withEmailAddressCriteria(EnumSet)
	 */
	public EnumSet<EmailAddressCriteria> getEmailAddressCriteria() {
		return emailAddressCriteria;
	}
	
	/**
	 * @see #withThreadPoolSize(Integer)
	 */
	public Integer getThreadPoolSize() {
		return threadPoolSize;
	}
	
	/**
	 * @see #trustingSSLHosts(String...)
	 */
	public List<String> getSslHostsToTrust() {
		return sslHostsToTrust;
	}
	
	/**
	 * @see #trustingAllHosts(Boolean)
	 */
	public Boolean getTrustAllSSLHost() {
		return trustAllSSLHost;
	}
	
	/**
	 * @see #withTransportModeLoggingOnly(Boolean)
	 */
	public boolean getTransportModeLoggingOnly() {
		return transportModeLoggingOnly;
	}
	
	/**
	 * @see #withProperties(Properties)
	 */
	public Properties getProperties() {
		return properties;
	}
}