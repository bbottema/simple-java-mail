/*
 * Copyright (C) 2009 Benny Bottema (benny@bennybottema.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.simplejavamail.api.mailer.config;

import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.MailerRegularBuilder;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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
	 * @see MailerGenericBuilder#withConnectionPoolClaimTimeoutMillis(Integer)
	 */
	int getConnectionPoolClaimTimeoutMillis();

	/**
	 * @see MailerGenericBuilder#withConnectionPoolExpireAfterMillis(Integer)
	 */
	int getConnectionPoolExpireAfterMillis();

	/**
	 * @see MailerGenericBuilder#withConnectionPoolLoadBalancingStrategy(LoadBalancingStrategy)
	 */
	@NotNull
	LoadBalancingStrategy getConnectionPoolLoadBalancingStrategy();
	
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
	@NotNull
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
	@NotNull
	Properties getProperties();

	/**
	 * @see MailerGenericBuilder#withClusterKey(UUID)
	 */
	@NotNull
	UUID getClusterKey();

	/**
	 * @see MailerGenericBuilder#withExecutorService(ExecutorService)
	 */
	@NotNull
	ExecutorService getExecutorService();

	/**
	 * @see MailerGenericBuilder#withCustomMailer(CustomMailer)
	 */
	@Nullable
	CustomMailer getCustomMailer();
}
