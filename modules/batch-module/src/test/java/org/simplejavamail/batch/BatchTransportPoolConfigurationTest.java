package org.simplejavamail.batch;

import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BatchTransportPoolConfigurationTest {

	@Test
	void rendersTheConfiguredValuesWithoutStaticDefaults() {
		final BatchTransportPoolConfiguration configuration = BatchTransportPoolConfiguration.builder()
				.withCorePoolSize(1)
				.withMaxPoolSize(3)
				.withClaimTimeout(Duration.ofMillis(4000))
				.withExpireAfter(Duration.ofMillis(2000))
				.withExpireAfterCreation(Duration.ofMillis(15000))
				.withLoadBalancingStrategy(BatchLoadBalancingStrategy.RANDOM_ACCESS)
				.build();

		assertThat(configuration.toString()).isEqualTo("BatchTransportPoolConfiguration(corePoolSize=1, maxPoolSize=3, "
				+ "claimTimeout=PT4S, expireAfter=PT2S, expireAfterCreation=PT15S, loadBalancingStrategy=RANDOM_ACCESS)");
	}

	@Test
	void rendersAnUnconfiguredCreationExpiryAsNull() {
		final BatchTransportPoolConfiguration configuration = BatchTransportPoolConfiguration.builder().build();

		assertThat(configuration.toString()).contains("expireAfterCreation=null");
	}
}
