package org.simplejavamail.batch;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BatchTransportPoolConfigurationTest {

	@Test
	void rendersTheConfiguredValuesWithoutStaticDefaults() {
		final BatchTransportPoolConfiguration configuration = BatchTransportPoolConfiguration.builder()
				.withCorePoolSize(1)
				.withMaxPoolSize(3)
				.withClaimTimeoutMillis(4000)
				.withExpireAfterMillis(2000)
				.withExpireAfterCreationMillis(15000)
				.withLoadBalancingStrategy(BatchLoadBalancingStrategy.RANDOM_ACCESS)
				.build();

		assertThat(configuration.toString()).isEqualTo("BatchTransportPoolConfiguration(corePoolSize=1, maxPoolSize=3, "
				+ "claimTimeoutMillis=4000, expireAfterMillis=2000, expireAfterCreationMillis=15000, loadBalancingStrategy=RANDOM_ACCESS)");
	}

	@Test
	void rendersAnUnconfiguredCreationExpiryAsNull() {
		final BatchTransportPoolConfiguration configuration = BatchTransportPoolConfiguration.builder().build();

		assertThat(configuration.toString()).contains("expireAfterCreationMillis=null");
	}
}
