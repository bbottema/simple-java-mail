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
package org.simplejavamail.internal.batchsupport;

import org.bbottema.clusteredobjectpool.core.ClusterConfig;
import org.bbottema.clusteredobjectpool.core.api.LoadBalancingStrategy;
import org.bbottema.clusteredobjectpool.cyclingstrategies.RandomAccessLoadBalancing;
import org.bbottema.clusteredobjectpool.cyclingstrategies.RoundRobinLoadBalancing;
import org.bbottema.genericobjectpool.expirypolicies.TimeoutSinceLastAllocationExpirationPolicy;
import org.bbottema.genericobjectpool.util.Timeout;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.smtpconnectionpool.SmtpClusterConfig;

import org.jetbrains.annotations.NotNull;
import javax.mail.Session;
import javax.mail.Transport;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.simplejavamail.api.mailer.config.LoadBalancingStrategy.ROUND_ROBIN;

final class ClusterHelper {
	private ClusterHelper() {
		// utility class
	}

	@NotNull
	static SmtpClusterConfig configureSmtpClusterConfig(@NotNull final OperationalConfig operationalConfig) {
		SmtpClusterConfig smtpClusterConfig = new SmtpClusterConfig();
		smtpClusterConfig.getConfigBuilder()
				.defaultCorePoolSize(operationalConfig.getConnectionPoolCoreSize())
				.defaultMaxPoolSize(operationalConfig.getConnectionPoolMaxSize())
				.claimTimeout(new Timeout(operationalConfig.getConnectionPoolClaimTimeoutMillis(), MILLISECONDS))
				.loadBalancingStrategy(operationalConfig.getConnectionPoolLoadBalancingStrategy() == ROUND_ROBIN
						? new RoundRobinLoadBalancing<>()
						: new RandomAccessLoadBalancing<>())
				.defaultExpirationPolicy(new TimeoutSinceLastAllocationExpirationPolicy<Transport>(operationalConfig.getConnectionPoolExpireAfterMillis(), MILLISECONDS));
		return smtpClusterConfig;
	}

	static boolean compareClusterConfig(@NotNull final OperationalConfig operationalConfig, final ClusterConfig<Session, Transport> config) {
		return config.getDefaultCorePoolSize() != operationalConfig.getConnectionPoolCoreSize() ||
				config.getDefaultMaxPoolSize() != operationalConfig.getConnectionPoolCoreSize() ||
				config.getLoadBalancingStrategy().getClass() != determineLoadBalancingStrategy(operationalConfig).getClass() ||
				!config.getDefaultExpirationPolicy().equals(new TimeoutSinceLastAllocationExpirationPolicy<Transport>(operationalConfig.getConnectionPoolExpireAfterMillis(), MILLISECONDS));
	}

	@SuppressWarnings("rawtypes")
	@NotNull
	private static LoadBalancingStrategy determineLoadBalancingStrategy(@NotNull final OperationalConfig operationalConfig) {
		return operationalConfig.getConnectionPoolLoadBalancingStrategy() == ROUND_ROBIN
				? new RoundRobinLoadBalancing<>()
				: new RandomAccessLoadBalancing<>();
	}
}
