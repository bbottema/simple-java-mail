package org.simplejavamail.internal.batchsupport;

import jakarta.mail.Session;
import org.bbottema.clusteredobjectpool.core.ClusterConfig;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.config.ConnectionPoolClusterConfig;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.smtpconnectionpool.SessionTransport;
import org.simplejavamail.smtpconnectionpool.SmtpConnectionPoolClustered;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BatchSupportTest {

	@Test
	void registerToClusterUsesSeparatePoolConfigPerClusterKey() throws Exception {
		BatchSupport batchSupport = new BatchSupport();
		UUID clusterA = UUID.randomUUID();
		UUID clusterB = UUID.randomUUID();
		Session sessionA = session();
		Session sessionB = session();

		try {
			batchSupport.registerToCluster(operationalConfig(0, 2, 100, 5000, LoadBalancingStrategy.ROUND_ROBIN), clusterA, sessionA);
			batchSupport.registerToCluster(operationalConfig(0, 7, 200, 6000, LoadBalancingStrategy.RANDOM_ACCESS), clusterB, sessionB);

			SmtpConnectionPoolClustered<UUID> smtpConnectionPool = smtpConnectionPool(batchSupport);
			assertPoolConfig(smtpConnectionPool.getClusterConfig(clusterA), 0, 2, 100, "RoundRobinLoadBalancing");
			assertPoolConfig(smtpConnectionPool.getClusterConfig(clusterB), 0, 7, 200, "RandomAccessLoadBalancing");
		} finally {
			batchSupport.shutdownConnectionPools(sessionA).get();
			batchSupport.shutdownConnectionPools(sessionB).get();
		}
	}

	@Test
	void registerToClusterKeepsFirstPoolConfigForSameClusterKey() throws Exception {
		BatchSupport batchSupport = new BatchSupport();
		UUID cluster = UUID.randomUUID();
		Session sessionA = session();
		Session sessionB = session();

		try {
			batchSupport.registerToCluster(operationalConfig(0, 2, 100, 5000, LoadBalancingStrategy.ROUND_ROBIN), cluster, sessionA);
			batchSupport.registerToCluster(operationalConfig(0, 7, 200, 6000, LoadBalancingStrategy.RANDOM_ACCESS), cluster, sessionB);

			SmtpConnectionPoolClustered<UUID> smtpConnectionPool = smtpConnectionPool(batchSupport);
			assertPoolConfig(smtpConnectionPool.getClusterConfig(cluster), 0, 2, 100, "RoundRobinLoadBalancing");
		} finally {
			batchSupport.shutdownConnectionPools(sessionA).get();
			batchSupport.shutdownConnectionPools(sessionB).get();
		}
	}

	@Test
	void registerToClusterUsesPropertyConfiguredOverridesForMatchingClusterKey() throws Exception {
		BatchSupport batchSupport = new BatchSupport();
		UUID configuredCluster = UUID.randomUUID();
		UUID fallbackCluster = UUID.randomUUID();
		Session configuredSession = session();
		Session fallbackSession = session();
		Map<UUID, ConnectionPoolClusterConfig> configuredClusters = new HashMap<>();
		configuredClusters.put(configuredCluster, ConnectionPoolClusterConfig.builder()
				.coreSize(0)
				.maxSize(9)
				.claimTimeoutMillis(300)
				.expireAfterMillis(7000)
				.loadBalancingStrategy(LoadBalancingStrategy.RANDOM_ACCESS)
				.build());

		try {
			OperationalConfig operationalConfig = operationalConfig(1, 2, 100, 5000, LoadBalancingStrategy.ROUND_ROBIN, configuredClusters);
			batchSupport.registerToCluster(operationalConfig, configuredCluster, configuredSession);
			batchSupport.registerToCluster(operationalConfig, fallbackCluster, fallbackSession);

			SmtpConnectionPoolClustered<UUID> smtpConnectionPool = smtpConnectionPool(batchSupport);
			assertPoolConfig(smtpConnectionPool.getClusterConfig(configuredCluster), 0, 9, 300, "RandomAccessLoadBalancing");
			assertPoolConfig(smtpConnectionPool.getClusterConfig(fallbackCluster), 1, 2, 100, "RoundRobinLoadBalancing");
		} finally {
			batchSupport.shutdownConnectionPools(configuredSession).get();
			batchSupport.shutdownConnectionPools(fallbackSession).get();
		}
	}

	private static OperationalConfig operationalConfig(int corePoolSize, int maxPoolSize, int claimTimeoutMillis, int expireAfterMillis,
													  LoadBalancingStrategy loadBalancingStrategy) {
		return operationalConfig(corePoolSize, maxPoolSize, claimTimeoutMillis, expireAfterMillis, loadBalancingStrategy, Collections.emptyMap());
	}

	private static OperationalConfig operationalConfig(int corePoolSize, int maxPoolSize, int claimTimeoutMillis, int expireAfterMillis,
													  LoadBalancingStrategy loadBalancingStrategy,
													  Map<UUID, ConnectionPoolClusterConfig> connectionPoolClusterConfigs) {
		OperationalConfig operationalConfig = mock(OperationalConfig.class);
		when(operationalConfig.getConnectionPoolCoreSize()).thenReturn(corePoolSize);
		when(operationalConfig.getConnectionPoolMaxSize()).thenReturn(maxPoolSize);
		when(operationalConfig.getConnectionPoolClaimTimeoutMillis()).thenReturn(claimTimeoutMillis);
		when(operationalConfig.getConnectionPoolExpireAfterMillis()).thenReturn(expireAfterMillis);
		when(operationalConfig.getConnectionPoolLoadBalancingStrategy()).thenReturn(loadBalancingStrategy);
		when(operationalConfig.getConnectionPoolClusterConfigs()).thenReturn(connectionPoolClusterConfigs);
		return operationalConfig;
	}

	private static Session session() {
		return Session.getInstance(new Properties());
	}

	private static void assertPoolConfig(ClusterConfig<UUID, Session, SessionTransport> clusterConfig, int corePoolSize, int maxPoolSize,
										 long claimTimeoutMillis, String loadBalancingStrategyClassName) {
		assertThat(clusterConfig.getDefaultCorePoolSize()).isEqualTo(corePoolSize);
		assertThat(clusterConfig.getDefaultMaxPoolSize()).isEqualTo(maxPoolSize);
		assertThat(clusterConfig.getClaimTimeout().getDurationMs()).isEqualTo(claimTimeoutMillis);
		assertThat(clusterConfig.getLoadBalancingStrategy().getClass().getSimpleName()).isEqualTo(loadBalancingStrategyClassName);
	}

	@SuppressWarnings("unchecked")
	private static SmtpConnectionPoolClustered<UUID> smtpConnectionPool(BatchSupport batchSupport) throws Exception {
		Field field = BatchSupport.class.getDeclaredField("smtpConnectionPool");
		field.setAccessible(true);
		return (SmtpConnectionPoolClustered<UUID>) field.get(batchSupport);
	}
}
