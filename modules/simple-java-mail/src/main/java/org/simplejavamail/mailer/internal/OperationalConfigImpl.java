package org.simplejavamail.mailer.internal;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.api.mailer.config.OperationalConfig;

import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * @see OperationalConfig
 */
@AllArgsConstructor
@ToString
@Getter
class OperationalConfigImpl implements OperationalConfig {

	/**
	 * Can be overridden when calling {@code mailer.send(async = true)}.
	 *
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#async()
	 */
	private final boolean async;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withProperties(Properties)
	 */
	private final Properties properties;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withSessionTimeout(Integer)
	 */
	private final int sessionTimeout;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withThreadPoolSize(Integer)
	 */
	private final int threadPoolSize;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withThreadPoolKeepAliveTime(Integer)
	 */
	private final int threadPoolKeepAliveTime;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withClusterKey(UUID)
	 */
	@NotNull
	private final UUID clusterKey;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withConnectionPoolCoreSize(Integer)
	 */
	private final int connectionPoolCoreSize;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withConnectionPoolMaxSize(Integer)
	 */
	private final int connectionPoolMaxSize;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withConnectionPoolClaimTimeoutMillis(Integer)
	 */
	private final int connectionPoolClaimTimeoutMillis;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withConnectionPoolExpireAfterMillis(Integer)
	 */
	private final int connectionPoolExpireAfterMillis;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withConnectionPoolLoadBalancingStrategy(LoadBalancingStrategy)
	 */
	@NotNull
	private final LoadBalancingStrategy connectionPoolLoadBalancingStrategy;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withTransportModeLoggingOnly(Boolean)
	 */
	private final boolean transportModeLoggingOnly;
	
	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withDebugLogging(Boolean)
	 */
	private final boolean debugLogging;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#disablingAllClientValidation(Boolean)
	 */
	private final boolean disableAllClientValidation;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#trustingSSLHosts(String...)
	 */
	@NotNull
	private final List<String> sslHostsToTrust;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#trustingAllHosts(boolean)
	 */
	private final boolean trustAllSSLHost;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#verifyingServerIdentity(boolean)
	 */
	private final boolean verifyingServerIdentity;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withExecutorService(ExecutorService)
	 */
	@NotNull
	private final ExecutorService executorService;

	/**
	 * @see InternalMailerBuilder#isExecutorServiceUserProvided()
	 */
	private final boolean executorServiceIsUserProvided;

	/**
	 * @see org.simplejavamail.api.mailer.MailerGenericBuilder#withCustomMailer(CustomMailer)
	 */
	@Nullable
	private final CustomMailer customMailer;
}