package org.simplejavamail.api.mailer.config;

import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.MailerRegularBuilder;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * Contains all the configuration that affect how a {@link Mailer} operates. This includes connection settings such as
 * timeouts, debug mode and which hosts to trust.
 * <p>
 * All of these settings are configured on the {@link MailerGenericBuilder}.
 */
public interface OperationalConfig {
	/**
	 * @see MailerGenericBuilder#async()
	 */
	boolean isAsync();
	
	/**
	 * @see MailerGenericBuilder#withSessionTimeout(Integer)
	 */
	int getSessionTimeout();

	/**
	 * @see MailerGenericBuilder#withThreadPoolSize(Integer)
	 */
	int getThreadPoolSize();

	/**
	 * @see MailerGenericBuilder#withThreadPoolKeepAliveTime(Integer)
	 */
	int getThreadPoolKeepAliveTime();

	/**
	 * @see MailerGenericBuilder#withConnectionPoolCoreSize(Integer)
	 */
	int getConnectionPoolCoreSize();

	/**
	 * @see MailerGenericBuilder#withConnectionPoolMaxSize(Integer)
	 */
	int getConnectionPoolMaxSize();

	/**
	 * @see MailerGenericBuilder#withConnectionPoolExpireAfterMillis(Integer)
	 */
	int getConnectionPoolExpireAfterMillis();
	
	/**
	 * @see MailerGenericBuilder#withTransportModeLoggingOnly(Boolean)
	 */
	boolean isTransportModeLoggingOnly();
	
	/**
	 * @see MailerGenericBuilder#withDebugLogging(Boolean)
	 */
	boolean isDebugLogging();
	
	/**
	 * @see MailerGenericBuilder#trustingSSLHosts(String...)
	 */
	@Nonnull
	List<String> getSslHostsToTrust();

	/**
	 * @see MailerGenericBuilder#trustingAllHosts(boolean)
	 */
	boolean isTrustAllSSLHost();

	/**
	 * @see MailerRegularBuilder#verifyingServerIdentity(boolean)
	 */
	boolean isVerifyingServerIdentity();
	
	/**
	 * @see MailerGenericBuilder#withProperties(Properties)
	 */
	@Nonnull
	Properties getProperties();

	/**
	 * @see MailerGenericBuilder#withExecutorService(ExecutorService)
	 */
	@Nonnull
	ExecutorService getExecutorService();

	/**
	 * @see MailerGenericBuilder#withClusterKey(UUID)
	 */
	@Nullable
	UUID getClusterKey();
}
