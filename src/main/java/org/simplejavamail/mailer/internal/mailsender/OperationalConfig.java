package org.simplejavamail.mailer.internal.mailsender;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

public class OperationalConfig {
	/**
	 * Refer to {@link org.simplejavamail.mailer.MailerGenericBuiler#DEFAULT_SESSION_TIMEOUT_MILLIS}.
	 */
	private final int sessionTimeout;
	
	/**
	 * Refer to {@link org.simplejavamail.mailer.MailerGenericBuiler#withProperties(Properties)}.
	 */
	private final Properties properties;
	
	/**
	 * The number of concurrent threads sending an email each. Used only when sending emails asynchronously (batch job mode).
	 */
	private final int threadPoolSize;
	
	/**
	 * Determines whether at the very last moment an email is sent out using JavaMail's native API or whether the email is simply only logged.
	 */
	private final boolean transportModeLoggingOnly;
	
	// FIXME JavaDoc
	private final boolean debugLogging;
	// FIXME JavaDoc
	private final List<String> sslHostsToTrust;
	// FIXME JavaDoc
	private final boolean trustAllSSLHost;
	
	public OperationalConfig(@Nonnull Properties properties, int sessionTimeout, int threadPoolSize, boolean transportModeLoggingOnly, boolean debugLogging, List<String> sslHostsToTrust, boolean trustAllSSLHost) {
		this.properties = properties;
		this.sessionTimeout = sessionTimeout;
		this.threadPoolSize = threadPoolSize;
		this.transportModeLoggingOnly = transportModeLoggingOnly;
		this.debugLogging = debugLogging;
		this.sslHostsToTrust = Collections.unmodifiableList(sslHostsToTrust);
		this.trustAllSSLHost = trustAllSSLHost;
	}
	
	public int getSessionTimeout() {
		return sessionTimeout;
	}
	
	public int getThreadPoolSize() {
		return threadPoolSize;
	}
	
	public boolean isTransportModeLoggingOnly() {
		return transportModeLoggingOnly;
	}
	
	public boolean isDebugLogging() {
		return debugLogging;
	}
	
	public List<String> getSslHostsToTrust() {
		return sslHostsToTrust;
	}
	
	public boolean isTrustAllSSLHost() {
		return trustAllSSLHost;
	}
	
	public Properties getProperties() {
		return properties;
	}
}