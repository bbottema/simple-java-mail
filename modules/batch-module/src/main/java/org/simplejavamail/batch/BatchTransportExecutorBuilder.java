package org.simplejavamail.batch;

import org.simplejavamail.internal.util.DurationMillis;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * Builds a {@link BatchTransportExecutor} with explicit connection-pool and asynchronous-executor ownership.
 *
 * @param <K> cluster-key type
 */
public final class BatchTransportExecutorBuilder<K> {

	/** Default number of worker threads when the batch module owns the executor. */
	public static final int DEFAULT_THREAD_POOL_SIZE = 10;
	/** Default worker keep-alive of one millisecond when the batch module owns the executor. */
	public static final Duration DEFAULT_THREAD_POOL_KEEP_ALIVE = Duration.ofMillis(1);

	private BatchTransportPoolConfiguration.Builder defaultPoolConfiguration = BatchTransportPoolConfiguration.builder();
	private final Map<K, BatchTransportPoolConfiguration> clusterConfigurations = new LinkedHashMap<>();
	private ExecutorService executorService;
	private int threadPoolSize = DEFAULT_THREAD_POOL_SIZE;
	private Duration threadPoolKeepAliveTime = DEFAULT_THREAD_POOL_KEEP_ALIVE;

	BatchTransportExecutorBuilder() {
	}

	/**
	 * Replaces the default pool settings used by clusters without an explicit override.
	 *
	 * @param configuration complete default configuration
	 * @return this builder
	 */
	public BatchTransportExecutorBuilder<K> withDefaultPoolConfiguration(final BatchTransportPoolConfiguration configuration) {
		defaultPoolConfiguration = BatchTransportPoolConfiguration.builder(Objects.requireNonNull(configuration, "configuration"));
		return this;
	}

	/**
	 * Sets the default core connection count.
	 *
	 * @param corePoolSize a nonnegative size no greater than the maximum
	 * @return this builder
	 */
	public BatchTransportExecutorBuilder<K> withCorePoolSize(final int corePoolSize) {
		defaultPoolConfiguration.withCorePoolSize(corePoolSize);
		return this;
	}

	/**
	 * Sets the default maximum connection count per Session.
	 *
	 * @param maxPoolSize a positive size
	 * @return this builder
	 */
	public BatchTransportExecutorBuilder<K> withMaxPoolSize(final int maxPoolSize) {
		defaultPoolConfiguration.withMaxPoolSize(maxPoolSize);
		return this;
	}

	/**
	 * Sets the default claim timeout.
	 *
	 * @param claimTimeout a nonnegative duration exactly representable as integer milliseconds
	 * @return this builder
	 */
	public BatchTransportExecutorBuilder<K> withClaimTimeout(final Duration claimTimeout) {
		defaultPoolConfiguration.withClaimTimeout(claimTimeout);
		return this;
	}

	/**
	 * Sets the default idle expiry time.
	 *
	 * @param expireAfter a nonnegative duration exactly representable as integer milliseconds; zero disables idle expiry
	 * @return this builder
	 */
	public BatchTransportExecutorBuilder<K> withExpireAfter(final Duration expireAfter) {
		defaultPoolConfiguration.withExpireAfter(expireAfter);
		return this;
	}

	/**
	 * Sets the default creation-age retirement threshold for available pooled connections.
	 * <p>
	 * Expiration is asynchronous and does not interrupt active leases, so this is an eligibility threshold rather than a
	 * strict maximum connection lifetime.
	 *
	 * @param expireAfterCreation a positive duration exactly representable as integer milliseconds
	 * @return this builder
	 */
	public BatchTransportExecutorBuilder<K> withExpireAfterCreation(final Duration expireAfterCreation) {
		defaultPoolConfiguration.withExpireAfterCreation(expireAfterCreation);
		return this;
	}

	/**
	 * Removes the default creation-age retirement threshold.
	 *
	 * @return this builder
	 */
	public BatchTransportExecutorBuilder<K> clearExpireAfterCreation() {
		defaultPoolConfiguration.clearExpireAfterCreation();
		return this;
	}

	/**
	 * Sets the default Session-pool selection strategy.
	 *
	 * @param loadBalancingStrategy selection strategy
	 * @return this builder
	 */
	public BatchTransportExecutorBuilder<K> withLoadBalancingStrategy(final BatchLoadBalancingStrategy loadBalancingStrategy) {
		defaultPoolConfiguration.withLoadBalancingStrategy(loadBalancingStrategy);
		return this;
	}

	/**
	 * Supplies a complete pool configuration for one cluster. The first Session registered for a cluster fixes that
	 * cluster's upstream pool settings.
	 *
	 * @param clusterKey non-null cluster key
	 * @param configuration complete configuration for that cluster
	 * @return this builder
	 */
	public BatchTransportExecutorBuilder<K> withClusterConfiguration(final K clusterKey,
			final BatchTransportPoolConfiguration configuration) {
		clusterConfigurations.put(Objects.requireNonNull(clusterKey, "clusterKey"),
				Objects.requireNonNull(configuration, "configuration"));
		return this;
	}

	/**
	 * Uses a caller-owned executor for asynchronous submissions. The batch facade never shuts this executor down.
	 * Thread-pool size and keep-alive settings are ignored when an executor is supplied.
	 *
	 * @param executorService caller-owned executor
	 * @return this builder
	 */
	public BatchTransportExecutorBuilder<K> withExecutorService(final ExecutorService executorService) {
		this.executorService = Objects.requireNonNull(executorService, "executorService");
		return this;
	}

	/**
	 * Sets the number of worker threads for the module-owned asynchronous executor.
	 *
	 * @param threadPoolSize a positive number of threads
	 * @return this builder
	 */
	public BatchTransportExecutorBuilder<K> withThreadPoolSize(final int threadPoolSize) {
		this.threadPoolSize = threadPoolSize;
		return this;
	}

	/**
	 * Sets the keep-alive time for module-owned worker threads. A positive value also lets core threads time out.
	 *
	 * @param threadPoolKeepAliveTime a nonnegative duration exactly representable as integer milliseconds
	 * @return this builder
	 */
	public BatchTransportExecutorBuilder<K> withThreadPoolKeepAliveTime(final Duration threadPoolKeepAliveTime) {
		DurationMillis.toInt(threadPoolKeepAliveTime, "threadPoolKeepAliveTime");
		this.threadPoolKeepAliveTime = threadPoolKeepAliveTime;
		return this;
	}

	/**
	 * Validates all settings and creates an open facade. No physical SMTP connection is opened until work is claimed.
	 *
	 * @return a new batch transport executor
	 */
	public BatchTransportExecutor<K> build() {
		if (threadPoolSize < 1) {
			throw new IllegalArgumentException("threadPoolSize must be at least one");
		}
		if (threadPoolKeepAliveTime.isNegative()) {
			throw new IllegalArgumentException("threadPoolKeepAliveTime must not be negative");
		}
		return new BatchTransportExecutor<>(defaultPoolConfiguration.build(),
				Collections.unmodifiableMap(new LinkedHashMap<>(clusterConfigurations)), executorService,
				threadPoolSize, DurationMillis.toInt(threadPoolKeepAliveTime, "threadPoolKeepAliveTime"));
	}
}
