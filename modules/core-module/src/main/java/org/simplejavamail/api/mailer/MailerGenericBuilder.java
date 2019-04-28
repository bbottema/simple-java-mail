package org.simplejavamail.api.mailer;

import org.hazlewood.connor.bottema.emailaddress.EmailAddressCriteria;
import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.mail.Session;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Builder superclass which contains API to take care of all generic Mailer properties unrelated to the SMTP server
 * (host, port, username, password and transport strategy).
 * <p>
 * To start a new Mailer builder, refer to {@link MailerRegularBuilder}.
 */
@Cli.BuilderApiNode(builderApiType = CliBuilderApiType.MAILER)
public interface MailerGenericBuilder<T extends MailerGenericBuilder<?>> {
	/**
	 * The default maximum timeout value for the transport socket is <code>{@value}</code> milliseconds (affects socket connect-,
	 * read- and write timeouts). Can be overridden from a config file or through System variable.
	 */
	int DEFAULT_SESSION_TIMEOUT_MILLIS = 60_000;
	/**
	 * {@value}
	 *
	 * @see #withThreadPoolCoreSize(Integer)
	 */
	int DEFAULT_CORE_POOL_SIZE = 10;
	/**
	 * {@value}
	 *
	 * @see #withThreadPoolMaxSize(Integer)
	 */
	int DEFAULT_MAX_POOL_SIZE = 10;
	/**
	 * {@value}
	 *
	 * @see #withThreadPoolKeepAliveTime(Integer)
	 */
	int DEFAULT_POOL_KEEP_ALIVE_TIME = 2000;
	/**
	 * Default port is <code>{@value}</code>.
	 */
	int DEFAULT_PROXY_PORT = 1080;
	/**
	 * The temporary intermediary SOCKS5 relay server bridge is a server that sits in between JavaMail and the remote proxy.
	 * Default port is <code>{@value}</code>.
	 */
	int DEFAULT_PROXY_BRIDGE_PORT = 1081;
	/**
	 * Defaults to <code>{@value}</code>, sending mails rather than just only logging the mails.
	 */
	boolean DEFAULT_TRANSPORT_MODE_LOGGING_ONLY = false;
	/**
	 * Defaults to <code>{@value}</code>, sending mails rather than just only logging the mails.
	 */
	boolean DEFAULT_JAVAXMAIL_DEBUG = false;
	
	/**
	 * Sets flag that send or server connection test should run in the background returning immediately.
	 * <p>
	 * In case of asynchronous mode, make sure you configure logging to file, as otherwise you won't know if there was an error.
	 */
	T async();
	
	/**
	 * Delegates to {@link #withProxyHost(String)} and {@link #withProxyPort(Integer)}.
	 */
	@Cli.ExcludeApi(reason = "API is a subset of a more detailed API")
	T withProxy(@Nullable String proxyHost, @Nullable Integer proxyPort);
	
	/**
	 * Sets proxy server settings, by delegating to:
	 * <ol>
	 * <li>{@link #withProxyHost(String)}</li>
	 * <li>{@link #withProxyPort(Integer)}</li>
	 * <li>{@link #withProxyUsername(String)}</li>
	 * <li>{@link #withProxyPassword(String)}</li>
	 * </ol>
	 *
	 * @param proxyHost See linked documentation above.
	 * @param proxyPort See linked documentation above.
	 * @param proxyUsername See linked documentation above.
	 * @param proxyPassword See linked documentation above.
	 */
	T withProxy(@Nullable String proxyHost, @Nullable Integer proxyPort, @Nullable String proxyUsername, @Nullable String proxyPassword);
	
	/**
	 * Sets the optional proxy host, which will override any default that might have been set (through properties file or programmatically).
	 */
	@Cli.ExcludeApi(reason = "API is a subset of a more details API")
	T withProxyHost(@Nullable String proxyHost);
	
	/**
	 * Sets the proxy port, which will override any default that might have been set (through properties file or programmatically).
	 * <p>
	 * Defaults to {@value DEFAULT_PROXY_PORT} if no custom default property was configured.
	 */
	@Cli.ExcludeApi(reason = "API is a subset of a more details API")
	T withProxyPort(@Nullable Integer proxyPort);
	
	/**
	 * Sets the optional username to authenticate with the proxy. If set, Simple Java Mail will use its built in proxy bridge to
	 * perform the SOCKS authentication, as the underlying JavaMail framework doesn't support this directly. The execution path
	 * then will be:
	 * <p>
	 * {@code Simple Java Mail client -> JavaMail -> anonymous authentication with local proxy bridge -> full authentication with remote SOCKS proxy -> SMTP server}.
	 */
	@Cli.ExcludeApi(reason = "API is a subset of a more details API")
	T withProxyUsername(@Nullable String proxyUsername);
	
	/**
	 * Sets the optional password to authenticate with the proxy.
	 *
	 * @see #withProxyUsername(String)
	 */
	@Cli.ExcludeApi(reason = "API is a subset of a more details API")
	T withProxyPassword(@Nullable String proxyPassword);
	
	/**
	 * Relevant only when using username authentication with a proxy.
	 * <p>
	 * Overrides the default for the intermediary SOCKS5 relay server bridge, which is a server that sits in between JavaMail and the remote proxy.
	 * <p>
	 * Defaults to {@value DEFAULT_PROXY_BRIDGE_PORT} if no custom default property was configured.
	 *
	 * @param proxyBridgePort The port to use for the proxy bridging server.
	 *
	 * @see #withProxyUsername(String)
	 */
	T withProxyBridgePort(@Nonnull Integer proxyBridgePort);
	
	/**
	 * This flag is set on the Session instance through {@link Session#setDebug(boolean)} so that it generates debug information. To get more
	 * information out of the underlying JavaMail framework or out of Simple Java Mail, increase logging config of your chosen logging framework.
	 *
	 * @param debugLogging Enables or disables debug logging with {@code true} or {@code false}.
	 */
	T withDebugLogging(@Nonnull Boolean debugLogging);
	
	/**
	 * Controls the timeout to use when sending emails (affects socket connect-, read- and write timeouts).
	 *
	 * @param sessionTimeout Duration to use for session timeout.
	 */
	T withSessionTimeout(@Nonnull Integer sessionTimeout);
	
	/**
	 * Sets the email address validation restrictions when validating and sending emails using the current <code>Mailer</code> instance.
	 * <p>
	 * Defaults to {@link EmailAddressCriteria#RFC_COMPLIANT} if not overridden with a ({@code null}) value.
	 *
	 * @see EmailAddressCriteria
	 * @see #clearEmailAddressCriteria()
	 * @see #resetEmailAddressCriteria()
	 */
	T withEmailAddressCriteria(@Nonnull EnumSet<EmailAddressCriteria> emailAddressCriteria);

	/**
	 * Sets both core thread pool size and max thread pool size to the given size.
	 *
	 * @param threadPoolSize See main description.
	 *
	 * @see #resetThreadpoolSize()
	 * @see #withThreadPoolCoreSize(Integer)
	 * @see #withThreadPoolMaxSize(Integer)
	 */
	T withThreadPoolSize(@Nonnull Integer threadPoolSize);

	/**
	 * When sending in async / batch mode, controls the minimum number of threads when starting up and when idling. However, by default a keepAlivetime is also configured, so the core threads
	 * actually die off, as to not block the JVM from exiting.
	 * <p>
	 * To revert to normal maxCoreThreads behavior, configure keepAlivetime to zero.
	 * <p>
	 * For more details on this, refer to {@link java.util.concurrent.ThreadPoolExecutor#setCorePoolSize(int)}, {@link java.util.concurrent.ThreadPoolExecutor#setKeepAliveTime(long, TimeUnit)} and
	 * particularly {@link java.util.concurrent.ThreadPoolExecutor#allowCoreThreadTimeOut(boolean)}
	 * <p>
	 * Defaults to {@value #DEFAULT_CORE_POOL_SIZE}.
	 *
	 * @param threadPoolCoreSize See main description.
	 *
	 * @see #resetThreadpoolCoreSize()
	 */
	T withThreadPoolCoreSize(@Nonnull Integer threadPoolCoreSize);

	/**
	 * When sending in async / batch mode, controls the maximum number of concurrent threads when ramping up mail sessions.
	 * by default a keepAlivetime is also configured, so these threads die off automatically, as to not block the JVM from exiting.
	 * <p>
	 * To revert to normal maxThreads behavior, configure keepAliveTime to zero.
	 * <p>
	 * For more details on this, refer to {@link java.util.concurrent.ThreadPoolExecutor#setMaximumPoolSize(int)}, {@link java.util.concurrent.ThreadPoolExecutor#setKeepAliveTime(long, TimeUnit)}.
	 * <p>
	 * Defaults to {@value #DEFAULT_MAX_POOL_SIZE}.
	 *
	 * @param threadPoolMaxSize See main description.
	 *
	 * @see #resetThreadpoolMaxSize()
	 */
	T withThreadPoolMaxSize(@Nonnull Integer threadPoolMaxSize);

	/**
	 * When set to a non-zero value (milliseconds), this keepAlivetime is applied to <em>both</em> core and extra threads. This is so that
	 * these threads can never block the JVM from exiting once they finish their task. This is different from daemon threads,
	 * which are abandonded without waiting for them to finish the tasks.
	 * <p>
	 * When set to zero, this keepAliveTime is applied only to extra threads, not core threads. This is the classic executor
	 * behavior, but this blocks the JVM from exiting.
	 * <p>
	 * Defaults to {@value #DEFAULT_POOL_KEEP_ALIVE_TIME}.
	 *
	 * @param threadPoolKeepAliveTime Value in milliseconds. See main description for details.
	 *
	 * @see #resetThreadpoolKeepAliveTime()
	 */
	T withThreadPoolKeepAliveTime(@Nonnull Integer threadPoolKeepAliveTime);
	
	/**
	 * Determines whether at the very last moment an email is sent out using JavaMail's native API or whether the email is simply only logged.
	 *
	 * @param transportModeLoggingOnly Flag {@code true} or {@code false} that enables or disables logging only mode when sending emails.
	 *
	 * @see #resetTransportModeLoggingOnly()
	 */
	T withTransportModeLoggingOnly(@Nonnull Boolean transportModeLoggingOnly);
	
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
	 * @see #trustingAllHosts(boolean)
	 */
	T trustingSSLHosts(String... sslHostsToTrust);
	
	/**
	 * Configures the current session to trust all hosts and don't validate any SSL keys. The property "mail.smtp(s).ssl.trust" is set to "*".
	 *
	 * @param trustAllHosts Flag {@code true} or {@code false} that enables or disables trusting of <strong>all</strong> hosts.
	 *
	 * @see <a href="https://javamail.java.net/nonav/docs/api/com/sun/mail/smtp/package-summary.html#mail.smtp.ssl.trust">mail.smtp.ssl.trust</a>
	 * @see #trustingSSLHosts(String...)
	 */
	T trustingAllHosts(boolean trustAllHosts);
	
	/**
	 * Adds the given properties to the total list applied to the {@link Session} when building a mailer.
	 *
	 * @see #withProperties(Map)
	 * @see #withProperty(String, Object)
	 * @see #clearProperties()
	 */
	T withProperties(@Nonnull Properties properties);
	
	/**
	 * @see #withProperties(Properties)
	 * @see #clearProperties()
	 */
	T withProperties(@Nonnull Map<String, String> properties);
	
	/**
	 * Sets property or removes it if the provided value is <code>null</code>. If provided, the value is always converted <code>toString()</code>.
	 *
	 * @param propertyName  The name of the property that wil be set on the internal Session object.
	 * @param propertyValue The text value of the property that wil be set on the internal Session object.
	 *
	 * @see #withProperties(Properties)
	 * @see #clearProperties()
	 */
	T withProperty(@Nonnull String propertyName, @Nullable Object propertyValue);
	
	/**
	 * Resets session time to its default ({@value DEFAULT_SESSION_TIMEOUT_MILLIS}).
	 *
	 * @see #withSessionTimeout(Integer)
	 */
	T resetSessionTimeout();
	
	/**
	 * Resets emailAddressCriteria to {@link EmailAddressCriteria#RFC_COMPLIANT}.
	 *
	 * @see #withEmailAddressCriteria(EnumSet)
	 * @see #clearEmailAddressCriteria()
	 */
	T resetEmailAddressCriteria();

	/**
	 * Resets both thread pool max and core size to their defaults.
	 *
	 * @see #withThreadPoolSize(Integer)
	 * @see #resetThreadpoolCoreSize()
	 * @see #resetThreadpoolMaxSize()
	 */
	T resetThreadpoolSize();

	/**
	 * Resets thread pool core size to its default ({@value #DEFAULT_CORE_POOL_SIZE}).
	 *
	 * @see #withThreadPoolCoreSize(Integer)
	 */
	T resetThreadpoolCoreSize();

	/**
	 * Resets thread pool max size to its default ({@value #DEFAULT_MAX_POOL_SIZE}).
	 *
	 * @see #withThreadPoolMaxSize(Integer)
	 */
	T resetThreadpoolMaxSize();

	/**
	 * Resets threadPoolMaxSize to its default ({@value #DEFAULT_POOL_KEEP_ALIVE_TIME}).
	 *
	 * @see #withThreadPoolKeepAliveTime(Integer)
	 */
	T resetThreadpoolKeepAliveTime();
	
	/**
	 * Resets transportModeLoggingOnly to {@value #DEFAULT_TRANSPORT_MODE_LOGGING_ONLY}.
	 *
	 * @see #withTransportModeLoggingOnly(Boolean)
	 */
	T resetTransportModeLoggingOnly();
	
	/**
	 * Empties all proxy configuration.
	 */
	T clearProxy();
	
	/**
	 * Removes all email address criteria, meaning validation won't take place.
	 *
	 * @see #withEmailAddressCriteria(EnumSet)
	 * @see #resetEmailAddressCriteria()
	 */
	T clearEmailAddressCriteria();
	
	/**
	 * Removes all trusted hosts from the list.
	 *
	 * @see #trustingSSLHosts(String...)
	 */
	T clearTrustedSSLHosts();
	
	/**
	 * Removes all properties.
	 *
	 * @see #withProperties(Properties)
	 */
	T clearProperties();
	
	@Cli.ExcludeApi(reason = "This API is specifically for Java use")
	Mailer buildMailer();
	
	/**
	 * @see #async()
	 */
	boolean isAsync();
	
	/**
	 * @see #withProxyHost(String)
	 */
	@Nullable
	String getProxyHost();
	
	/**
	 * @see #withProxyPort(Integer)
	 */
	@Nullable
	Integer getProxyPort();
	
	/**
	 * @see #withProxyUsername(String)
	 */
	@Nullable
	String getProxyUsername();
	
	/**
	 * @see #withProxyPassword(String)
	 */
	@Nullable
	String getProxyPassword();
	
	/**
	 * @see #withProxyBridgePort(Integer)
	 */
	@Nullable
	Integer getProxyBridgePort();
	
	/**
	 * @see #withDebugLogging(Boolean)
	 */
	boolean isDebugLogging();
	
	/**
	 * @see #withSessionTimeout(Integer)
	 */
	@Nullable
	Integer getSessionTimeout();
	
	/**
	 * @see #withEmailAddressCriteria(EnumSet)
	 */
	@Nullable
	EnumSet<EmailAddressCriteria> getEmailAddressCriteria();

	/**
	 * @see #withThreadPoolCoreSize(Integer)
	 */
	@Nonnull
	Integer getThreadPoolCoreSize();

	/**
	 * @see #withThreadPoolMaxSize(Integer)
	 */
	@Nonnull
	Integer getThreadPoolMaxSize();

	/**
	 * @see #withThreadPoolKeepAliveTime(Integer)
	 */
	@Nonnull
	Integer getThreadPoolKeepAliveTime();
	
	/**
	 * @see #trustingSSLHosts(String...)
	 */
	@Nullable
	List<String> getSslHostsToTrust();
	
	/**
	 * @see #trustingAllHosts(boolean)
	 */
	boolean isTrustAllSSLHost();
	
	/**
	 * @see #withTransportModeLoggingOnly(Boolean)
	 */
	boolean isTransportModeLoggingOnly();
	
	/**
	 * @see #withProperties(Properties)
	 */
	@Nullable
	Properties getProperties();
}