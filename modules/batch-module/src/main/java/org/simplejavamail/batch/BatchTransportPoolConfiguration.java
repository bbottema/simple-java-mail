package org.simplejavamail.batch;

import java.time.Duration;

import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.internal.batchsupport.ConnectionPoolValidation;
import org.simplejavamail.internal.util.DurationMillis;

import java.util.Objects;

/**
 * Immutable connection-pool settings used globally or for one cluster.
 * <p>
 * This type configures the upstream {@code smtp-connection-pool}; the batch module does not implement a second
 * physical pool. A cluster configuration is complete rather than partial, so every value shown by its getters is the
 * value that will be used for that cluster.
 */
@ToString(onlyExplicitlyIncluded = true)
public final class BatchTransportPoolConfiguration {

	/** Default number of connections kept allocated. */
	public static final int DEFAULT_CORE_POOL_SIZE = 0;
	/** Default maximum number of connections per registered Session. */
	public static final int DEFAULT_MAX_POOL_SIZE = 4;
	/** Default time to wait for a connection: approximately 24.9 days. */
	public static final Duration DEFAULT_CLAIM_TIMEOUT = Duration.ofMillis(Integer.MAX_VALUE);
	/** Default idle duration after which a non-core connection expires: five seconds. */
	public static final Duration DEFAULT_EXPIRE_AFTER = Duration.ofSeconds(5);
	/** Default cluster selection strategy. */
	public static final BatchLoadBalancingStrategy DEFAULT_LOAD_BALANCING_STRATEGY = BatchLoadBalancingStrategy.ROUND_ROBIN;

	@ToString.Include
	private final int corePoolSize;
	@ToString.Include
	private final int maxPoolSize;
	@ToString.Include
	private final Duration claimTimeout;
	@ToString.Include
	private final Duration expireAfter;
	@ToString.Include
	@Nullable
	private final Duration expireAfterCreation;
	@ToString.Include
	private final BatchLoadBalancingStrategy loadBalancingStrategy;

	private BatchTransportPoolConfiguration(final Builder builder) {
		this.corePoolSize = builder.corePoolSize;
		this.maxPoolSize = builder.maxPoolSize;
		this.claimTimeout = builder.claimTimeout;
		this.expireAfter = builder.expireAfter;
		this.expireAfterCreation = builder.expireAfterCreation;
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

	/** @return the maximum wait for an available connection */
	public Duration getClaimTimeout() {
		return claimTimeout;
	}

	/** @return the idle expiry time for non-core connections */
	public Duration getExpireAfter() {
		return expireAfter;
	}

	/**
	 * @return the creation-age retirement eligibility threshold, or {@code null} when not configured
	 */
	@Nullable
	public Duration getExpireAfterCreation() {
		return expireAfterCreation;
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
				&& claimTimeout.equals(that.claimTimeout)
				&& expireAfter.equals(that.expireAfter)
				&& Objects.equals(expireAfterCreation, that.expireAfterCreation)
				&& loadBalancingStrategy == that.loadBalancingStrategy;
	}

	@Override
	public int hashCode() {
		return Objects.hash(corePoolSize, maxPoolSize, claimTimeout, expireAfter,
				expireAfterCreation, loadBalancingStrategy);
	}

	/**
	 * Builder for an immutable pool configuration.
	 */
	public static final class Builder {
		private int corePoolSize = DEFAULT_CORE_POOL_SIZE;
		private int maxPoolSize = DEFAULT_MAX_POOL_SIZE;
		private Duration claimTimeout = DEFAULT_CLAIM_TIMEOUT;
		private Duration expireAfter = DEFAULT_EXPIRE_AFTER;
		@Nullable
		private Duration expireAfterCreation;
		private BatchLoadBalancingStrategy loadBalancingStrategy = DEFAULT_LOAD_BALANCING_STRATEGY;

		private Builder() {
		}

		private Builder(final BatchTransportPoolConfiguration configuration) {
			this.corePoolSize = configuration.corePoolSize;
			this.maxPoolSize = configuration.maxPoolSize;
			this.claimTimeout = configuration.claimTimeout;
			this.expireAfter = configuration.expireAfter;
			this.expireAfterCreation = configuration.expireAfterCreation;
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
		 * @param claimTimeout a nonnegative duration exactly representable as integer milliseconds
		 * @return this builder
		 */
		public Builder withClaimTimeout(@NotNull final Duration claimTimeout) {
			this.claimTimeout = Objects.requireNonNull(claimTimeout, "claimTimeout");
			return this;
		}

		/**
		 * Sets when an idle non-core connection expires.
		 *
		 * @param expireAfter a nonnegative duration exactly representable as integer milliseconds; zero disables idle expiry
		 * @return this builder
		 */
		public Builder withExpireAfter(@NotNull final Duration expireAfter) {
			this.expireAfter = Objects.requireNonNull(expireAfter, "expireAfter");
			return this;
		}

		/**
		 * Sets the creation age after which an available pooled connection becomes eligible for retirement.
		 * <p>
		 * The pool checks asynchronously and does not interrupt active leases. Rapid reuse can delay retirement, so this
		 * is not a strict maximum connection lifetime. Claiming or releasing a connection does not reset its creation age.
		 *
		 * @param expireAfterCreation a positive duration exactly representable as integer milliseconds
		 * @return this builder
		 */
		public Builder withExpireAfterCreation(@NotNull final Duration expireAfterCreation) {
			this.expireAfterCreation = Objects.requireNonNull(expireAfterCreation, "expireAfterCreation");
			return this;
		}

		/**
		 * Removes the creation-age retirement threshold.
		 *
		 * @return this builder
		 */
		public Builder clearExpireAfterCreation() {
			this.expireAfterCreation = null;
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
		 * @throws IllegalArgumentException when a size or duration is invalid, has fractional milliseconds or exceeds the integer-millisecond range
		 */
		public BatchTransportPoolConfiguration build() {
			ConnectionPoolValidation.validateResolved(corePoolSize, maxPoolSize,
					DurationMillis.toInt(claimTimeout, "claimTimeout"), DurationMillis.toInt(expireAfter, "expireAfter"),
					DurationMillis.toNullableInt(expireAfterCreation, "expireAfterCreation"));
			Objects.requireNonNull(loadBalancingStrategy, "loadBalancingStrategy");
			return new BatchTransportPoolConfiguration(this);
		}
	}
}
