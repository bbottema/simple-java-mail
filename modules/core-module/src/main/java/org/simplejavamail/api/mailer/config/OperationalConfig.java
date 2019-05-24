package org.simplejavamail.api.mailer.config;

import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.MailerRegularBuilder;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;

/**
 * Contains all the configuration that affect how a {@link Mailer} operates. This includes connection settings such as
 * timeouts, debug mode and which hosts to trust.
 * <p>
 * All of these settings are configured on the {@link MailerGenericBuilder}.
 */
public interface OperationalConfig {
	/**
	 * @see MailerRegularBuilder#async()
	 */
	boolean isAsync();
	
	/**
	 * @see MailerRegularBuilder#withSessionTimeout(Integer)
	 */
	int getSessionTimeout();

	/**
	 * @see MailerRegularBuilder#withThreadPoolSize(Integer)
	 */
	int getThreadPoolSize();

	/**
	 * @see MailerRegularBuilder#withThreadPoolKeepAliveTime(Integer)
	 */
	int getThreadPoolKeepAliveTime();
	
	/**
	 * @see MailerRegularBuilder#withTransportModeLoggingOnly(Boolean)
	 */
	boolean isTransportModeLoggingOnly();
	
	/**
	 * @see MailerRegularBuilder#withDebugLogging(Boolean)
	 */
	boolean isDebugLogging();
	
	/**
	 * @see MailerRegularBuilder#trustingSSLHosts(String...)
	 */
	@Nonnull
	List<String> getSslHostsToTrust();
	
	/**
	 * @see MailerRegularBuilder#trustingAllHosts(boolean)
	 */
	boolean isTrustAllSSLHost();
	
	/**
	 * @see MailerRegularBuilder#withProperties(Properties)
	 */
	@Nonnull
	Properties getProperties();

	/**
	 * @see MailerRegularBuilder#withExecutorService(ExecutorService)
	 */
	@Nonnull
	ExecutorService getExecutorService();
}
