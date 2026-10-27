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
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.batch.BatchLoadBalancingStrategy;
import org.simplejavamail.batch.BatchTransportPoolConfiguration;
import org.simplejavamail.internal.configresolution.ConfigurationChoice;
import org.simplejavamail.internal.configresolution.ConfigurationDeclaration;
import org.simplejavamail.internal.configresolution.ConfigurationOrigin;
import org.simplejavamail.internal.configresolution.ConfigurationResolver;
import org.simplejavamail.internal.configresolution.ConfigurationSetting;
import org.simplejavamail.internal.util.MailTransportLifecycleResolver;
import org.simplejavamail.internal.util.DurationMillis;
import org.simplejavamail.smtpconnectionpool.SessionTransport;
import org.simplejavamail.smtpconnectionpool.SmtpClusterConfig;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/** Internal, immutable mapping shared by the public facade and the Mailer adapter. */
final class PoolSettings {
	private static final List<ConfigurationOrigin.Kind> CLUSTER_BEFORE_RESOLVED_DEFAULT = List.of(
			ConfigurationOrigin.Kind.OVERRIDE, ConfigurationOrigin.Kind.DERIVED);
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
		ConnectionPoolValidation.validateResolved(corePoolSize, maxPoolSize, claimTimeoutMillis, expireAfterMillis, expireAfterCreationMillis);
		this.corePoolSize = corePoolSize;
		this.maxPoolSize = maxPoolSize;
		this.claimTimeoutMillis = claimTimeoutMillis;
		this.expireAfterMillis = expireAfterMillis;
		this.expireAfterCreationMillis = expireAfterCreationMillis;
		this.loadBalancingStrategy = loadBalancingStrategy;
	}

	static PoolSettings from(final BatchTransportPoolConfiguration configuration) {
		return new PoolSettings(configuration.getCorePoolSize(), configuration.getMaxPoolSize(),
				DurationMillis.toInt(configuration.getClaimTimeout(), "claimTimeout"), DurationMillis.toInt(configuration.getExpireAfter(), "expireAfter"),
				DurationMillis.toNullableInt(configuration.getExpireAfterCreation(), "expireAfterCreation"),
				configuration.getLoadBalancingStrategy());
	}

	static PoolSettings from(final OperationalConfig operationalConfig, final UUID clusterKey) {
		final ConnectionPoolClusterConfig clusterConfig = clusterKey == null
				? null
				: operationalConfig.getConnectionPoolClusterConfigs().get(clusterKey);
		return resolveClusterConfiguration(clusterConfig, operationalConfig.getConnectionPoolCoreSize(), operationalConfig.getConnectionPoolMaxSize(),
				operationalConfig.getConnectionPoolClaimTimeout(), operationalConfig.getConnectionPoolExpireAfter(),
				operationalConfig.getConnectionPoolExpireAfterCreation(), operationalConfig.getConnectionPoolLoadBalancingStrategy());
	}

	/** Preflight and registration interpret the same completed defaults and selected partial cluster before applying pool constraints. */
	static PoolSettings from(final ConnectionPoolConfig defaults, @Nullable final ConnectionPoolClusterConfig clusterConfig) {
		return resolveClusterConfiguration(clusterConfig, defaults.getCoreSize(), defaults.getMaxSize(), defaults.getClaimTimeout(),
				defaults.getExpireAfter(), defaults.getExpireAfterCreation(), defaults.getLoadBalancingStrategy());
	}

	private static PoolSettings resolveClusterConfiguration(@Nullable final ConnectionPoolClusterConfig clusterConfig,
			final int coreSize, final int maxSize, final Duration claimTimeout, final Duration expireAfter,
			@Nullable final Duration expireAfterCreation, final org.simplejavamail.api.mailer.config.LoadBalancingStrategy strategy) {
		return new PoolSettings(
				resolveClusterMember("coreSize", clusterConfig == null ? null : clusterConfig.getCoreSize(), coreSize),
				resolveClusterMember("maxSize", clusterConfig == null ? null : clusterConfig.getMaxSize(), maxSize),
				DurationMillis.toInt(resolveClusterMember("claimTimeout", clusterConfig == null ? null : clusterConfig.getClaimTimeout(),
						claimTimeout), "connectionPoolClaimTimeout"),
				DurationMillis.toInt(resolveClusterMember("expireAfter", clusterConfig == null ? null : clusterConfig.getExpireAfter(),
						expireAfter), "connectionPoolExpireAfter"),
				DurationMillis.toNullableInt(resolveClusterMember("expireAfterCreation", clusterConfig == null ? null : clusterConfig.getExpireAfterCreation(),
						expireAfterCreation), "connectionPoolExpireAfterCreation"),
				toBatchStrategy(resolveClusterMember("loadBalancingStrategy", clusterConfig == null ? null : clusterConfig.getLoadBalancingStrategy(),
						strategy)));
	}

	/** Cluster omissions inherit already-resolved Mailer values; a configured zero remains an explicit cluster choice. */
	@Nullable
	private static <T> T resolveClusterMember(final String member, @Nullable final T clusterChoice, @Nullable final T resolvedDefault) {
		final ConfigurationSetting<T> setting = ConfigurationSetting.of("batch.pool", member);
		return ConfigurationResolver.resolve(setting, List.of(
				ConfigurationDeclaration.of(setting, clusterChoice == null ? ConfigurationChoice.inherit() : ConfigurationChoice.set(clusterChoice),
						ConfigurationOrigin.of(ConfigurationOrigin.Kind.OVERRIDE, member, "cluster-specific pool configuration")),
				ConfigurationDeclaration.of(setting, resolvedDefault == null ? ConfigurationChoice.remove() : ConfigurationChoice.set(resolvedDefault),
						ConfigurationOrigin.of(ConfigurationOrigin.Kind.DERIVED, member, "resolved Mailer pool configuration"))),
				CLUSTER_BEFORE_RESOLVED_DEFAULT).valueOrNull();
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
		config.withTransportCancellationSupport(MailTransportLifecycleResolver::findAbortAction);
		config.getConfigBuilder()
				.defaultCorePoolSize(corePoolSize)
				.defaultMaxPoolSize(maxPoolSize)
				.claimTimeout(new Timeout(claimTimeoutMillis, MILLISECONDS))
				.loadBalancingStrategy(balancing)
				.defaultExpirationPolicy(expirationPolicy());
		return config;
	}

	int getClaimTimeoutMillis() {
		return claimTimeoutMillis;
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
