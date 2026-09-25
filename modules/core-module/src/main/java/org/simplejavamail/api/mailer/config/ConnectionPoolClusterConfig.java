package org.simplejavamail.api.mailer.config;

import lombok.Builder;
import lombok.Value;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;

/**
 * Optional connection-pool settings for a single batch-module cluster key.
 * <p>
 * Null values mean the regular {@link OperationalConfig} connection-pool defaults remain effective for that setting.
 */
@Value
public class ConnectionPoolClusterConfig implements Serializable {
	private static final long serialVersionUID = 1L;

	@Nullable Integer coreSize;
	@Nullable Integer maxSize;
	@Nullable Integer claimTimeoutMillis;
	@Nullable Integer expireAfterMillis;
	@Nullable Integer expireAfterCreationMillis;
	@Nullable LoadBalancingStrategy loadBalancingStrategy;

	@Builder
	ConnectionPoolClusterConfig(@Nullable final Integer coreSize, @Nullable final Integer maxSize,
			@Nullable final Integer claimTimeoutMillis, @Nullable final Integer expireAfterMillis,
			@Nullable final Integer expireAfterCreationMillis,
			@Nullable final LoadBalancingStrategy loadBalancingStrategy) {
		if (expireAfterCreationMillis != null && expireAfterCreationMillis < 1) {
			throw new IllegalArgumentException("expireAfterCreationMillis must be positive");
		}
		this.coreSize = coreSize;
		this.maxSize = maxSize;
		this.claimTimeoutMillis = claimTimeoutMillis;
		this.expireAfterMillis = expireAfterMillis;
		this.expireAfterCreationMillis = expireAfterCreationMillis;
		this.loadBalancingStrategy = loadBalancingStrategy;
	}
}
