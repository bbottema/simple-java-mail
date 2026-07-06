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
@Builder
public class ConnectionPoolClusterConfig implements Serializable {
	private static final long serialVersionUID = 1L;

	@Nullable Integer coreSize;
	@Nullable Integer maxSize;
	@Nullable Integer claimTimeoutMillis;
	@Nullable Integer expireAfterMillis;
	@Nullable LoadBalancingStrategy loadBalancingStrategy;
}
