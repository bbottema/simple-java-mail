package org.simplejavamail.internal.batchsupport;

import jakarta.mail.Session;
import org.bbottema.clusteredobjectpool.core.ClusterConfig;
import org.bbottema.clusteredobjectpool.core.api.LoadBalancingStrategy;
import org.bbottema.clusteredobjectpool.cyclingstrategies.RandomAccessLoadBalancing;
import org.bbottema.clusteredobjectpool.cyclingstrategies.RoundRobinLoadBalancing;
import org.bbottema.genericobjectpool.expirypolicies.TimeoutSinceLastAllocationExpirationPolicy;
import org.bbottema.genericobjectpool.util.Timeout;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.config.ConnectionPoolClusterConfig;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.smtpconnectionpool.SessionTransport;
import org.simplejavamail.smtpconnectionpool.SmtpClusterConfig;

import java.util.UUID;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.simplejavamail.api.mailer.config.LoadBalancingStrategy.ROUND_ROBIN;

final class ClusterHelper {
	private ClusterHelper() {
		// utility class
	}

	@NotNull
	static SmtpClusterConfig<UUID> configureSmtpClusterConfig(@NotNull final OperationalConfig operationalConfig) {
		return configureSmtpClusterConfig(operationalConfig, null);
	}

	@NotNull
	static SmtpClusterConfig<UUID> configureSmtpClusterConfig(@NotNull final OperationalConfig operationalConfig, @Nullable final UUID clusterKey) {
		final ConnectionPoolClusterConfig clusterConfig = determineClusterConfig(operationalConfig, clusterKey);
		SmtpClusterConfig<UUID> smtpClusterConfig = new SmtpClusterConfig<>();
		smtpClusterConfig.getConfigBuilder()
				.defaultCorePoolSize(determineCoreSize(operationalConfig, clusterConfig))
				.defaultMaxPoolSize(determineMaxSize(operationalConfig, clusterConfig))
				.claimTimeout(new Timeout(determineClaimTimeoutMillis(operationalConfig, clusterConfig), MILLISECONDS))
				.loadBalancingStrategy(createPoolLoadBalancingStrategy(determineConfiguredLoadBalancingStrategy(operationalConfig, clusterConfig)))
				.defaultExpirationPolicy(new TimeoutSinceLastAllocationExpirationPolicy<>(determineExpireAfterMillis(operationalConfig, clusterConfig), MILLISECONDS));
		return smtpClusterConfig;
	}

	static boolean compareClusterConfig(@NotNull final OperationalConfig operationalConfig, final ClusterConfig<UUID, Session, SessionTransport> config) {
		return compareClusterConfig(operationalConfig, null, config);
	}

	static boolean compareClusterConfig(@NotNull final OperationalConfig operationalConfig, @Nullable final UUID clusterKey, final ClusterConfig<UUID, Session, SessionTransport> config) {
		final ConnectionPoolClusterConfig clusterConfig = determineClusterConfig(operationalConfig, clusterKey);
		return config.getDefaultCorePoolSize() != determineCoreSize(operationalConfig, clusterConfig) ||
				config.getDefaultMaxPoolSize() != determineMaxSize(operationalConfig, clusterConfig) ||
				!config.getClaimTimeout().equals(new Timeout(determineClaimTimeoutMillis(operationalConfig, clusterConfig), MILLISECONDS)) ||
				config.getLoadBalancingStrategy().getClass() != createPoolLoadBalancingStrategy(determineConfiguredLoadBalancingStrategy(operationalConfig, clusterConfig)).getClass() ||
				!config.getDefaultExpirationPolicy().equals(new TimeoutSinceLastAllocationExpirationPolicy<SessionTransport>(determineExpireAfterMillis(operationalConfig, clusterConfig), MILLISECONDS));
	}

	@Nullable
	private static ConnectionPoolClusterConfig determineClusterConfig(@NotNull final OperationalConfig operationalConfig, @Nullable final UUID clusterKey) {
		return clusterKey != null
				? operationalConfig.getConnectionPoolClusterConfigs().get(clusterKey)
				: null;
	}

	private static int determineCoreSize(@NotNull final OperationalConfig operationalConfig, @Nullable final ConnectionPoolClusterConfig clusterConfig) {
		return clusterConfig != null && clusterConfig.getCoreSize() != null
				? clusterConfig.getCoreSize()
				: operationalConfig.getConnectionPoolCoreSize();
	}

	private static int determineMaxSize(@NotNull final OperationalConfig operationalConfig, @Nullable final ConnectionPoolClusterConfig clusterConfig) {
		return clusterConfig != null && clusterConfig.getMaxSize() != null
				? clusterConfig.getMaxSize()
				: operationalConfig.getConnectionPoolMaxSize();
	}

	private static int determineClaimTimeoutMillis(@NotNull final OperationalConfig operationalConfig, @Nullable final ConnectionPoolClusterConfig clusterConfig) {
		return clusterConfig != null && clusterConfig.getClaimTimeoutMillis() != null
				? clusterConfig.getClaimTimeoutMillis()
				: operationalConfig.getConnectionPoolClaimTimeoutMillis();
	}

	private static int determineExpireAfterMillis(@NotNull final OperationalConfig operationalConfig, @Nullable final ConnectionPoolClusterConfig clusterConfig) {
		return clusterConfig != null && clusterConfig.getExpireAfterMillis() != null
				? clusterConfig.getExpireAfterMillis()
				: operationalConfig.getConnectionPoolExpireAfterMillis();
	}

	@NotNull
	private static org.simplejavamail.api.mailer.config.LoadBalancingStrategy determineConfiguredLoadBalancingStrategy(@NotNull final OperationalConfig operationalConfig,
																													   @Nullable final ConnectionPoolClusterConfig clusterConfig) {
		return clusterConfig != null && clusterConfig.getLoadBalancingStrategy() != null
				? clusterConfig.getLoadBalancingStrategy()
				: operationalConfig.getConnectionPoolLoadBalancingStrategy();
	}

	@SuppressWarnings("rawtypes")
	@NotNull
	private static LoadBalancingStrategy createPoolLoadBalancingStrategy(@NotNull final org.simplejavamail.api.mailer.config.LoadBalancingStrategy loadBalancingStrategy) {
		return loadBalancingStrategy == ROUND_ROBIN
				? new RoundRobinLoadBalancing<>()
				: new RandomAccessLoadBalancing<>();
	}
}
