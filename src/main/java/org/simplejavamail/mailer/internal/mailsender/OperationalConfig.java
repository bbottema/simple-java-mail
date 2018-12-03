package org.simplejavamail.mailer.internal.mailsender;

import org.simplejavamail.mailer.MailerGenericBuilder;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * Contains all the configuration that affect how a {@link org.simplejavamail.mailer.Mailer} operates. This includes connection settings such as
 * timeouts, debug mode and which hosts to trust.
 * <p>
 * All of these settings are configured on the {@link MailerGenericBuilder}.
 */
// FIXME don't forget to include async in the operational config examples
public class OperationalConfig {
	/**
	 * @see org.simplejavamail.mailer.MailerBuilder.MailerRegularBuilder#withSessionTimeout(Integer)
	 */
	private final int sessionTimeout;
	
	/**
	 * @see org.simplejavamail.mailer.MailerBuilder.MailerRegularBuilder#async(boolean)
	 */
	private final boolean async;
	/**
	 * @see org.simplejavamail.mailer.MailerBuilder.MailerRegularBuilder#withProperties(Properties)
	 */
	private final Properties properties;
	
	/**
	 * @see org.simplejavamail.mailer.MailerBuilder.MailerRegularBuilder#withThreadPoolSize(Integer)
	 */
	private final int threadPoolSize;
	
	/**
	 * @see org.simplejavamail.mailer.MailerBuilder.MailerRegularBuilder#withTransportModeLoggingOnly(boolean)
	 */
	private final boolean transportModeLoggingOnly;
	
	/**
	 * @see org.simplejavamail.mailer.MailerBuilder.MailerRegularBuilder#withDebugLogging(Boolean)
	 */
	private final boolean debugLogging;
	
	/**
	 * @see org.simplejavamail.mailer.MailerBuilder.MailerRegularBuilder#trustingSSLHosts(String...)
	 */
	private final List<String> sslHostsToTrust;
	
	/**
	 * @see org.simplejavamail.mailer.MailerBuilder.MailerRegularBuilder#trustingAllHosts(boolean)
	 */
	private final boolean trustAllSSLHost;
	
	/**
	 * For internal use only.
	 */
	public OperationalConfig(boolean async, @Nonnull Properties properties, int sessionTimeout, int threadPoolSize, boolean transportModeLoggingOnly, boolean debugLogging, List<String> sslHostsToTrust, boolean trustAllSSLHost) {
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
	 * @see org.simplejavamail.mailer.MailerBuilder.MailerRegularBuilder#async(boolean)
	 */
	public boolean isAsync() {
		return async;
	}
	
	/**
	 * @see org.simplejavamail.mailer.MailerBuilder.MailerRegularBuilder#withSessionTimeout(Integer)
	 */
	public int getSessionTimeout() {
		return sessionTimeout;
	}
	
	/**
	 * @see org.simplejavamail.mailer.MailerBuilder.MailerRegularBuilder#withThreadPoolSize(Integer)
	 */
	public int getThreadPoolSize() {
		return threadPoolSize;
	}
	
	/**
	 * @see org.simplejavamail.mailer.MailerBuilder.MailerRegularBuilder#withTransportModeLoggingOnly(boolean)
	 */
	public boolean isTransportModeLoggingOnly() {
		return transportModeLoggingOnly;
	}
	
	/**
	 * @see org.simplejavamail.mailer.MailerBuilder.MailerRegularBuilder#withDebugLogging(Boolean)
	 */
	public boolean isDebugLogging() {
		return debugLogging;
	}
	
	/**
	 * @see org.simplejavamail.mailer.MailerBuilder.MailerRegularBuilder#trustingSSLHosts(String...)
	 */
	public List<String> getSslHostsToTrust() {
		return sslHostsToTrust;
	}
	
	/**
	 * @see org.simplejavamail.mailer.MailerBuilder.MailerRegularBuilder#trustingAllHosts(boolean)
	 */
	public boolean isTrustAllSSLHost() {
		return trustAllSSLHost;
	}
	
	/**
	 * @see org.simplejavamail.mailer.MailerBuilder.MailerRegularBuilder#withProperties(Properties)
	 */
	public Properties getProperties() {
		return properties;
	}
}