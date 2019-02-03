package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.OperationalConfig;

import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * @see OperationalConfig
 */
// FIXME don't forget to include async in the operational config examples
public class OperationalConfigImpl implements OperationalConfig {
	/**
	 * @see MailerRegularBuilder#withSessionTimeout(Integer)
	 */
	private final int sessionTimeout;
	
	/**
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
	private final List<String> sslHostsToTrust;
	
	/**
	 * @see MailerRegularBuilder#trustingAllHosts(boolean)
	 */
	private final boolean trustAllSSLHost;
	
	/**
	 * @deprecated For internal use only.
	 */
	@Deprecated
	@SuppressWarnings("DeprecatedIsStillUsed")
	public OperationalConfigImpl(boolean async, Properties properties, int sessionTimeout, int threadPoolSize, boolean transportModeLoggingOnly, boolean debugLogging, List<String> sslHostsToTrust, boolean trustAllSSLHost) {
		this.async = async;
		this.properties = properties;
		this.sessionTimeout = sessionTimeout;
		this.threadPoolSize = threadPoolSize;
		this.transportModeLoggingOnly = transportModeLoggingOnly;
		this.debugLogging = debugLogging;
		this.sslHostsToTrust = Collections.unmodifiableList(sslHostsToTrust);
		this.trustAllSSLHost = trustAllSSLHost;
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
	@Override
	public Properties getProperties() {
		return properties;
	}
}