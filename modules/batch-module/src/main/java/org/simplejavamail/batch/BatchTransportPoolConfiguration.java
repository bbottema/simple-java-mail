package org.simplejavamail.batch;

import java.util.Objects;

/**
 * Immutable connection-pool settings used globally or for one cluster.
 * <p>
 * This type configures the upstream {@code smtp-connection-pool}; the batch module does not implement a second
 * physical pool. A cluster configuration is complete rather than partial, so every value shown by its getters is the
 * value that will be used for that cluster.
 */
public final class BatchTransportPoolConfiguration {

	/** Default number of connections kept allocated. */
	public static final int DEFAULT_CORE_POOL_SIZE = 0;
	/** Default maximum number of connections per registered Session. */
	public static final int DEFAULT_MAX_POOL_SIZE = 4;
	/** Default time to wait for a connection, in milliseconds. */
	public static final int DEFAULT_CLAIM_TIMEOUT_MILLIS = Integer.MAX_VALUE;
	/** Default idle time after which a non-core connection expires, in milliseconds. */
	public static final int DEFAULT_EXPIRE_AFTER_MILLIS = 5000;
	/** Default cluster selection strategy. */
	public static final BatchLoadBalancingStrategy DEFAULT_LOAD_BALANCING_STRATEGY = BatchLoadBalancingStrategy.ROUND_ROBIN;

	private final int corePoolSize;
	private final int maxPoolSize;
	private final int claimTimeoutMillis;
	private final int expireAfterMillis;
	private final BatchLoadBalancingStrategy loadBalancingStrategy;

	private BatchTransportPoolConfiguration(final Builder builder) {
		this.corePoolSize = builder.corePoolSize;
		this.maxPoolSize = builder.maxPoolSize;
		this.claimTimeoutMillis = builder.claimTimeoutMillis;
		this.expireAfterMillis = builder.expireAfterMillis;
		this.loadBalancingStrategy = builder.loadBalancingStrategy;
	}

	/**
	 * Starts a builder with the documented defaults.
	 *
	 * @return a new configuration builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Starts a builder initialized from an existing configuration.
	 *
	 * @param configuration configuration to copy
	 * @return a new configuration builder
	 */
	public static Builder builder(final BatchTransportPoolConfiguration configuration) {
		return new Builder(Objects.requireNonNull(configuration, "configuration"));
	}

	/** @return the number of connections kept allocated for each Session */
	public int getCorePoolSize() {
		return corePoolSize;
	}

	/** @return the maximum number of connections allocated for each Session */
	public int getMaxPoolSize() {
		return maxPoolSize;
	}

	/** @return the maximum wait for an available connection, in milliseconds */
	public int getClaimTimeoutMillis() {
		return claimTimeoutMillis;
	}

	/** @return the idle expiry time for non-core connections, in milliseconds */
	public int getExpireAfterMillis() {
		return expireAfterMillis;
	}

	/** @return the Session-pool selection strategy used within a cluster */
	public BatchLoadBalancingStrategy getLoadBalancingStrategy() {
		return loadBalancingStrategy;
	}

	@Override
	public boolean equals(final Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof BatchTransportPoolConfiguration)) {
			return false;
		}
		final BatchTransportPoolConfiguration that = (BatchTransportPoolConfiguration) other;
		return corePoolSize == that.corePoolSize
				&& maxPoolSize == that.maxPoolSize
				&& claimTimeoutMillis == that.claimTimeoutMillis
				&& expireAfterMillis == that.expireAfterMillis
				&& loadBalancingStrategy == that.loadBalancingStrategy;
	}

	@Override
	public int hashCode() {
		return Objects.hash(corePoolSize, maxPoolSize, claimTimeoutMillis, expireAfterMillis, loadBalancingStrategy);
	}

	@Override
	public String toString() {
		return "BatchTransportPoolConfiguration{" +
				"corePoolSize=" + corePoolSize +
				", maxPoolSize=" + maxPoolSize +
				", claimTimeoutMillis=" + claimTimeoutMillis +
				", expireAfterMillis=" + expireAfterMillis +
				", loadBalancingStrategy=" + loadBalancingStrategy +
				'}';
	}

	/**
	 * Builder for an immutable pool configuration.
	 */
	public static final class Builder {
		private int corePoolSize = DEFAULT_CORE_POOL_SIZE;
		private int maxPoolSize = DEFAULT_MAX_POOL_SIZE;
		private int claimTimeoutMillis = DEFAULT_CLAIM_TIMEOUT_MILLIS;
		private int expireAfterMillis = DEFAULT_EXPIRE_AFTER_MILLIS;
		private BatchLoadBalancingStrategy loadBalancingStrategy = DEFAULT_LOAD_BALANCING_STRATEGY;

		private Builder() {
		}

		private Builder(final BatchTransportPoolConfiguration configuration) {
			this.corePoolSize = configuration.corePoolSize;
			this.maxPoolSize = configuration.maxPoolSize;
			this.claimTimeoutMillis = configuration.claimTimeoutMillis;
			this.expireAfterMillis = configuration.expireAfterMillis;
			this.loadBalancingStrategy = configuration.loadBalancingStrategy;
		}

		/**
		 * Sets the number of connections kept allocated for each Session.
		 *
		 * @param corePoolSize a nonnegative size no greater than the maximum
		 * @return this builder
		 */
		public Builder withCorePoolSize(final int corePoolSize) {
			this.corePoolSize = corePoolSize;
			return this;
		}

		/**
		 * Sets the maximum number of connections allocated for each Session.
		 *
		 * @param maxPoolSize a positive size no smaller than the core size
		 * @return this builder
		 */
		public Builder withMaxPoolSize(final int maxPoolSize) {
			this.maxPoolSize = maxPoolSize;
			return this;
		}

		/**
		 * Sets how long a claim may wait for an available connection.
		 *
		 * @param claimTimeoutMillis a nonnegative duration in milliseconds
		 * @return this builder
		 */
		public Builder withClaimTimeoutMillis(final int claimTimeoutMillis) {
			this.claimTimeoutMillis = claimTimeoutMillis;
			return this;
		}

		/**
		 * Sets when an idle non-core connection expires.
		 *
		 * @param expireAfterMillis a nonnegative duration in milliseconds; zero disables idle expiry
		 * @return this builder
		 */
		public Builder withExpireAfterMillis(final int expireAfterMillis) {
			this.expireAfterMillis = expireAfterMillis;
			return this;
		}

		/**
		 * Sets how registered Session pools are selected within a cluster.
		 *
		 * @param loadBalancingStrategy selection strategy
		 * @return this builder
		 */
		public Builder withLoadBalancingStrategy(final BatchLoadBalancingStrategy loadBalancingStrategy) {
			this.loadBalancingStrategy = Objects.requireNonNull(loadBalancingStrategy, "loadBalancingStrategy");
			return this;
		}

		/**
		 * Validates and creates the immutable configuration.
		 *
		 * @return the configuration
		 * @throws IllegalArgumentException when a size or duration is invalid
		 */
		public BatchTransportPoolConfiguration build() {
			if (corePoolSize < 0) {
				throw new IllegalArgumentException("corePoolSize must not be negative");
			}
			if (maxPoolSize < 1) {
				throw new IllegalArgumentException("maxPoolSize must be at least one");
			}
			if (corePoolSize > maxPoolSize) {
				throw new IllegalArgumentException("corePoolSize must not exceed maxPoolSize");
			}
			if (claimTimeoutMillis < 0) {
				throw new IllegalArgumentException("claimTimeoutMillis must not be negative");
			}
			if (expireAfterMillis < 0) {
				throw new IllegalArgumentException("expireAfterMillis must not be negative");
			}
			Objects.requireNonNull(loadBalancingStrategy, "loadBalancingStrategy");
			return new BatchTransportPoolConfiguration(this);
		}
	}
}
