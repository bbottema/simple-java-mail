package org.simplejavamail.internal.batchsupport;

import org.bbottema.clusteredobjectpool.core.api.LoadBalancingStrategy;
import org.bbottema.clusteredobjectpool.cyclingstrategies.RandomAccessLoadBalancing;
import org.bbottema.clusteredobjectpool.cyclingstrategies.RoundRobinLoadBalancing;
import org.bbottema.genericobjectpool.ExpirationPolicy;
import org.bbottema.genericobjectpool.expirypolicies.CombinedExpirationPolicies;
import org.bbottema.genericobjectpool.expirypolicies.TimeoutSinceCreationExpirationPolicy;
import org.bbottema.genericobjectpool.expirypolicies.TimeoutSinceLastAllocationExpirationPolicy;
import org.bbottema.genericobjectpool.util.Timeout;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.config.ConnectionPoolClusterConfig;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.batch.BatchLoadBalancingStrategy;
import org.simplejavamail.batch.BatchTransportPoolConfiguration;
import org.simplejavamail.smtpconnectionpool.SessionTransport;
import org.simplejavamail.smtpconnectionpool.SmtpClusterConfig;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/** Internal, immutable mapping shared by the public facade and the Mailer adapter. */
final class PoolSettings {
	private final int corePoolSize;
	private final int maxPoolSize;
	private final int claimTimeoutMillis;
	private final int expireAfterMillis;
	@Nullable
	private final Integer expireAfterCreationMillis;
	private final BatchLoadBalancingStrategy loadBalancingStrategy;

	private PoolSettings(final int corePoolSize, final int maxPoolSize, final int claimTimeoutMillis,
			final int expireAfterMillis, @Nullable final Integer expireAfterCreationMillis,
			final BatchLoadBalancingStrategy loadBalancingStrategy) {
		if (expireAfterCreationMillis != null && expireAfterCreationMillis < 1) {
			throw new IllegalArgumentException("expireAfterCreationMillis must be positive");
		}
		this.corePoolSize = corePoolSize;
		this.maxPoolSize = maxPoolSize;
		this.claimTimeoutMillis = claimTimeoutMillis;
		this.expireAfterMillis = expireAfterMillis;
		this.expireAfterCreationMillis = expireAfterCreationMillis;
		this.loadBalancingStrategy = loadBalancingStrategy;
	}

	static PoolSettings from(final BatchTransportPoolConfiguration configuration) {
		return new PoolSettings(configuration.getCorePoolSize(), configuration.getMaxPoolSize(),
				configuration.getClaimTimeoutMillis(), configuration.getExpireAfterMillis(),
				configuration.getExpireAfterCreationMillis(),
				configuration.getLoadBalancingStrategy());
	}

	static PoolSettings from(final OperationalConfig operationalConfig, final UUID clusterKey) {
		final ConnectionPoolClusterConfig clusterConfig = clusterKey == null
				? null
				: operationalConfig.getConnectionPoolClusterConfigs().get(clusterKey);
		return new PoolSettings(
				clusterConfig != null && clusterConfig.getCoreSize() != null
						? clusterConfig.getCoreSize() : operationalConfig.getConnectionPoolCoreSize(),
				clusterConfig != null && clusterConfig.getMaxSize() != null
						? clusterConfig.getMaxSize() : operationalConfig.getConnectionPoolMaxSize(),
				clusterConfig != null && clusterConfig.getClaimTimeoutMillis() != null
						? clusterConfig.getClaimTimeoutMillis() : operationalConfig.getConnectionPoolClaimTimeoutMillis(),
				clusterConfig != null && clusterConfig.getExpireAfterMillis() != null
						? clusterConfig.getExpireAfterMillis() : operationalConfig.getConnectionPoolExpireAfterMillis(),
				clusterConfig != null && clusterConfig.getExpireAfterCreationMillis() != null
						? clusterConfig.getExpireAfterCreationMillis() : operationalConfig.getConnectionPoolExpireAfterCreationMillis(),
				toBatchStrategy(clusterConfig != null && clusterConfig.getLoadBalancingStrategy() != null
						? clusterConfig.getLoadBalancingStrategy() : operationalConfig.getConnectionPoolLoadBalancingStrategy()));
	}

	private static BatchLoadBalancingStrategy toBatchStrategy(
			final org.simplejavamail.api.mailer.config.LoadBalancingStrategy strategy) {
		return strategy == org.simplejavamail.api.mailer.config.LoadBalancingStrategy.ROUND_ROBIN
				? BatchLoadBalancingStrategy.ROUND_ROBIN
				: BatchLoadBalancingStrategy.RANDOM_ACCESS;
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	<K> SmtpClusterConfig<K> toSmtpClusterConfig() {
		final LoadBalancingStrategy balancing = loadBalancingStrategy == BatchLoadBalancingStrategy.ROUND_ROBIN
				? new RoundRobinLoadBalancing<>()
				: new RandomAccessLoadBalancing<>();
		final SmtpClusterConfig<K> config = new SmtpClusterConfig<>();
		config.getConfigBuilder()
				.defaultCorePoolSize(corePoolSize)
				.defaultMaxPoolSize(maxPoolSize)
				.claimTimeout(new Timeout(claimTimeoutMillis, MILLISECONDS))
				.loadBalancingStrategy(balancing)
				.defaultExpirationPolicy(expirationPolicy());
		return config;
	}

	private ExpirationPolicy<SessionTransport> expirationPolicy() {
		if (expireAfterMillis == 0 && expireAfterCreationMillis == null) {
			return poolableObject -> false;
		}
		if (expireAfterMillis == 0) {
			return new TimeoutSinceCreationExpirationPolicy<>(expireAfterCreationMillis, MILLISECONDS);
		}
		if (expireAfterCreationMillis == null) {
			return new TimeoutSinceLastAllocationExpirationPolicy<>(expireAfterMillis, MILLISECONDS);
		}
		final Set<ExpirationPolicy<SessionTransport>> policies = new LinkedHashSet<>();
		policies.add(new TimeoutSinceLastAllocationExpirationPolicy<>(expireAfterMillis, MILLISECONDS));
		policies.add(new TimeoutSinceCreationExpirationPolicy<>(expireAfterCreationMillis, MILLISECONDS));
		return new CombinedExpirationPolicies<>(policies);
	}

	@Override
	public boolean equals(final Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof PoolSettings)) {
			return false;
		}
		final PoolSettings that = (PoolSettings) other;
		return corePoolSize == that.corePoolSize
				&& maxPoolSize == that.maxPoolSize
				&& claimTimeoutMillis == that.claimTimeoutMillis
				&& expireAfterMillis == that.expireAfterMillis
				&& Objects.equals(expireAfterCreationMillis, that.expireAfterCreationMillis)
				&& loadBalancingStrategy == that.loadBalancingStrategy;
	}

	@Override
	public int hashCode() {
		return Objects.hash(corePoolSize, maxPoolSize, claimTimeoutMillis, expireAfterMillis,
				expireAfterCreationMillis, loadBalancingStrategy);
	}
}
