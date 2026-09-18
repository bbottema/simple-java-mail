package org.simplejavamail.internal.batchsupport;

import org.bbottema.genericobjectpool.ExpirationPolicy;
import org.bbottema.genericobjectpool.PoolableObject;
import org.bbottema.genericobjectpool.expirypolicies.CombinedExpirationPolicies;
import org.bbottema.genericobjectpool.expirypolicies.TimeoutSinceCreationExpirationPolicy;
import org.bbottema.genericobjectpool.expirypolicies.TimeoutSinceLastAllocationExpirationPolicy;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.config.ConnectionPoolClusterConfig;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.batch.BatchTransportPoolConfiguration;
import org.simplejavamail.smtpconnectionpool.SessionTransport;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PoolSettingsTest {

	@Test
	void selectsTheConfiguredExpirationPolicies() {
		assertThat(policy(0, null).hasExpired(poolable(100, 100))).isFalse();

		ExpirationPolicy<SessionTransport> idleOnly = policy(50, null);
		assertThat(idleOnly).isInstanceOf(TimeoutSinceLastAllocationExpirationPolicy.class);
		assertThat(idleOnly.hasExpired(poolable(0, 49))).isFalse();
		assertThat(idleOnly.hasExpired(poolable(0, 50))).isTrue();

		ExpirationPolicy<SessionTransport> creationOnly = policy(0, 50);
		assertThat(creationOnly).isInstanceOf(TimeoutSinceCreationExpirationPolicy.class);
		assertThat(creationOnly.hasExpired(poolable(49, 0))).isFalse();
		assertThat(creationOnly.hasExpired(poolable(50, 0))).isTrue();

		ExpirationPolicy<SessionTransport> combined = policy(50, 50);
		assertThat(combined).isInstanceOf(CombinedExpirationPolicies.class);
		assertThat(combined.hasExpired(poolable(50, 0))).isTrue();
	}

	@Test
	void clusterCanInheritOrOverrideGlobalCreationAgeExpiration() {
		UUID inherited = UUID.randomUUID();
		UUID overridden = UUID.randomUUID();
		Map<UUID, ConnectionPoolClusterConfig> clusters = new LinkedHashMap<>();
		clusters.put(inherited, ConnectionPoolClusterConfig.builder().build());
		clusters.put(overridden, ConnectionPoolClusterConfig.builder().expireAfterCreationMillis(75).build());

		OperationalConfig operationalConfig = mock(OperationalConfig.class);
		when(operationalConfig.getConnectionPoolCoreSize()).thenReturn(0);
		when(operationalConfig.getConnectionPoolMaxSize()).thenReturn(1);
		when(operationalConfig.getConnectionPoolClaimTimeoutMillis()).thenReturn(100);
		when(operationalConfig.getConnectionPoolExpireAfterMillis()).thenReturn(0);
		when(operationalConfig.getConnectionPoolExpireAfterCreationMillis()).thenReturn(50);
		when(operationalConfig.getConnectionPoolLoadBalancingStrategy()).thenReturn(LoadBalancingStrategy.ROUND_ROBIN);
		when(operationalConfig.getConnectionPoolClusterConfigs()).thenReturn(clusters);

		assertThat(policy(operationalConfig, inherited).hasExpired(poolable(50, 0))).isTrue();
		assertThat(policy(operationalConfig, overridden).hasExpired(poolable(50, 0))).isFalse();
		assertThat(policy(operationalConfig, overridden).hasExpired(poolable(75, 0))).isTrue();
	}

	private static ExpirationPolicy<SessionTransport> policy(final int expireAfterMillis,
			final Integer expireAfterCreationMillis) {
		BatchTransportPoolConfiguration.Builder builder = BatchTransportPoolConfiguration.builder()
				.withExpireAfterMillis(expireAfterMillis);
		if (expireAfterCreationMillis != null) {
			builder.withExpireAfterCreationMillis(expireAfterCreationMillis);
		}
		BatchTransportPoolConfiguration configuration = builder.build();
		return PoolSettings.from(configuration).<String>toSmtpClusterConfig()
				.getConfigBuilder().build().getDefaultExpirationPolicy();
	}

	private static ExpirationPolicy<SessionTransport> policy(final OperationalConfig operationalConfig,
			final UUID clusterKey) {
		return PoolSettings.from(operationalConfig, clusterKey).<UUID>toSmtpClusterConfig()
				.getConfigBuilder().build().getDefaultExpirationPolicy();
	}

	@SuppressWarnings("unchecked")
	private static PoolableObject<SessionTransport> poolable(final long ageMillis, final long allocationAgeMillis) {
		PoolableObject<SessionTransport> poolableObject = mock(PoolableObject.class);
		when(poolableObject.getExpiriesMs()).thenReturn(new HashMap<>());
		when(poolableObject.ageMs()).thenReturn(ageMillis);
		when(poolableObject.allocationAgeMs()).thenReturn(allocationAgeMillis);
		return poolableObject;
	}
}
