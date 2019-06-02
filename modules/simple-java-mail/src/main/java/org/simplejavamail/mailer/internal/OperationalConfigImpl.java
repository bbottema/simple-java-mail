package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.OperationalConfig;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;

/**
 * @see OperationalConfig
 */
// FIXME Lombok
class OperationalConfigImpl implements OperationalConfig {
	/**
	 * @see MailerRegularBuilder#withSessionTimeout(Integer)
	 */
	private final int sessionTimeout;
	
	/**
	 * Can be overridden when calling {@code mailer.send(async = true)}.
	 *
	 * @see MailerRegularBuilder#async()
	 */
	private final boolean async;
	/**
	 * @see MailerRegularBuilder#withProperties(Properties)
	 */
	private final Properties properties;

	/**
	 * @see MailerRegularBuilder#withThreadPoolSize(Integer)
	 */
	private final int threadPoolSize;

	/**
	 * @see MailerRegularBuilder#withThreadPoolKeepAliveTime(Integer)
	 */
	private final int threadPoolKeepAliveTime;
	
	/**
	 * @see MailerRegularBuilder#withTransportModeLoggingOnly(Boolean)
	 */
	private final boolean transportModeLoggingOnly;
	
	/**
	 * @see MailerRegularBuilder#withDebugLogging(Boolean)
	 */
	private final boolean debugLogging;
	
	/**
	 * @see MailerRegularBuilder#trustingSSLHosts(String...)
	 */
	@Nonnull
	private final List<String> sslHostsToTrust;

	/**
	 * @see MailerRegularBuilder#trustingAllHosts(boolean)
	 */
	private final boolean trustAllSSLHost;

	/**
	 * @see MailerRegularBuilder#withExecutorService(ExecutorService)
	 */
	@Nonnull
	private final ExecutorService executorService;
	
	OperationalConfigImpl(final boolean async, Properties properties, int sessionTimeout, int threadPoolSize, int threadPoolKeepAliveTime, boolean transportModeLoggingOnly,
			boolean debugLogging, @Nonnull List<String> sslHostsToTrust, boolean trustAllSSLHost, @Nonnull final ExecutorService executorService) {
		this.async = async; // can be overridden when calling {@code mailer.send(async = true)}
		this.properties = properties;
		this.sessionTimeout = sessionTimeout;
		this.threadPoolSize = threadPoolSize;
		this.threadPoolKeepAliveTime = threadPoolKeepAliveTime;
		this.transportModeLoggingOnly = transportModeLoggingOnly;
		this.debugLogging = debugLogging;
		this.sslHostsToTrust = Collections.unmodifiableList(sslHostsToTrust);
		this.trustAllSSLHost = trustAllSSLHost;
		this.executorService = executorService;
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
	@Nonnull
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
	 * @see OperationalConfig#getProperties()
	 */
	@Nonnull
	@Override
	public Properties getProperties() {
		return properties;
	}

	@Nonnull
	@Override
	public ExecutorService getExecutorService() {
		return executorService;
	}
}