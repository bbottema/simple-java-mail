package org.simplejavamail.internal.batchsupport;

import jakarta.mail.Session;
import jakarta.mail.Transport;
import org.bbottema.clusteredobjectpool.core.ClusterConfig;
import org.bbottema.clusteredobjectpool.core.api.LoadBalancingStrategy;
import org.bbottema.clusteredobjectpool.cyclingstrategies.RandomAccessLoadBalancing;
import org.bbottema.clusteredobjectpool.cyclingstrategies.RoundRobinLoadBalancing;
import org.bbottema.genericobjectpool.expirypolicies.TimeoutSinceLastAllocationExpirationPolicy;
import org.bbottema.genericobjectpool.util.Timeout;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.smtpconnectionpool.SmtpClusterConfig;

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
				config.getDefaultMaxPoolSize() != operationalConfig.getConnectionPoolMaxSize() ||
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
