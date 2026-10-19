package org.simplejavamail.api.mailer.config;

import java.time.Duration;

import lombok.Value;
import org.simplejavamail.internal.util.DurationMillis;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.mailer.MailerGenericBuilder;

import java.io.Serializable;

/**
 * Optional connection-pool settings for a single batch-module cluster key.
 * <p>
 * Null values mean the regular {@link OperationalConfig} connection-pool defaults remain effective for that setting.
 * The batch module resolves these overrides and validates the resulting settings when this cluster is used, before creating pool resources.
 * This object only retains supplied values; unused cluster settings do not affect a Mailer without pooling.
 * Durations must be exactly representable as integer milliseconds; fractional milliseconds and overflow are rejected when built.
 */
@Value
public class ConnectionPoolClusterConfig implements Serializable {
	private static final long serialVersionUID = 1L;

	@Nullable Integer coreSize;
	@Nullable Integer maxSize;
	@Nullable Duration claimTimeout;
	@Nullable Duration expireAfter;
	@Nullable Duration expireAfterCreation;
	@Nullable LoadBalancingStrategy loadBalancingStrategy;

	private ConnectionPoolClusterConfig(@Nullable final Integer coreSize, @Nullable final Integer maxSize,
			@Nullable final Duration claimTimeout, @Nullable final Duration expireAfter,
			@Nullable final Duration expireAfterCreation,
			@Nullable final LoadBalancingStrategy loadBalancingStrategy) {
		this.coreSize = coreSize;
		this.maxSize = maxSize;
		DurationMillis.toNullableInt(claimTimeout, "claimTimeout");
		this.claimTimeout = claimTimeout;
		DurationMillis.toNullableInt(expireAfter, "expireAfter");
		this.expireAfter = expireAfter;
		DurationMillis.toNullableInt(expireAfterCreation, "expireAfterCreation");
		this.expireAfterCreation = expireAfterCreation;
		this.loadBalancingStrategy = loadBalancingStrategy;
	}

	/** @see MailerGenericBuilder#withConnectionPoolCoreSize(Integer) */
	@Nullable
	public Integer getCoreSize() {
		return coreSize;
	}

	/** @see MailerGenericBuilder#withConnectionPoolMaxSize(Integer) */
	@Nullable
	public Integer getMaxSize() {
		return maxSize;
	}

	/** @see MailerGenericBuilder#withConnectionPoolClaimTimeout(Duration) */
	@Nullable
	public Duration getClaimTimeout() {
		return claimTimeout;
	}

	/** @see MailerGenericBuilder#withConnectionPoolExpireAfter(Duration) */
	@Nullable
	public Duration getExpireAfter() {
		return expireAfter;
	}

	/** @see MailerGenericBuilder#withConnectionPoolExpireAfterCreation(Duration) */
	@Nullable
	public Duration getExpireAfterCreation() {
		return expireAfterCreation;
	}

	/** @see MailerGenericBuilder#withConnectionPoolLoadBalancingStrategy(LoadBalancingStrategy) */
	@Nullable
	public LoadBalancingStrategy getLoadBalancingStrategy() {
		return loadBalancingStrategy;
	}

	/** @return A builder that collects supplied values without performing any mail operation. */
	@NotNull
	public static ConnectionPoolClusterConfigBuilder builder() {
		return new ConnectionPoolClusterConfigBuilder();
	}

	/** Explicit construction contract for supplied values; each setter replaces its previous value. */
	public static final class ConnectionPoolClusterConfigBuilder {
		private Integer coreSize;
		private Integer maxSize;
		private Duration claimTimeout;
		private Duration expireAfter;
		private Duration expireAfterCreation;
		private LoadBalancingStrategy loadBalancingStrategy;

		private ConnectionPoolClusterConfigBuilder() {
		}

		/**
		 * Overrides this cluster's setting; null leaves the regular Mailer setting effective. See {@link
		 * MailerGenericBuilder#withConnectionPoolCoreSize(Integer)} for its meaning and constraints. Replaces the previous value.
		 */
		@NotNull
		public ConnectionPoolClusterConfigBuilder coreSize(@Nullable final Integer value) {
			this.coreSize = value;
			return this;
		}

		/** @see #coreSize(Integer) */
		@Nullable
		public Integer getCoreSize() {
			return coreSize;
		}

		/** @see #coreSize(Integer) */
		@NotNull
		public ConnectionPoolClusterConfigBuilder clearCoreSize() {
			this.coreSize = null;
			return this;
		}

		/**
		 * Overrides this cluster's setting; null leaves the regular Mailer setting effective. See {@link
		 * MailerGenericBuilder#withConnectionPoolMaxSize(Integer)} for its meaning and constraints. Replaces the previous value.
		 */
		@NotNull
		public ConnectionPoolClusterConfigBuilder maxSize(@Nullable final Integer value) {
			this.maxSize = value;
			return this;
		}

		/** @see #maxSize(Integer) */
		@Nullable
		public Integer getMaxSize() {
			return maxSize;
		}

		/** @see #maxSize(Integer) */
		@NotNull
		public ConnectionPoolClusterConfigBuilder clearMaxSize() {
			this.maxSize = null;
			return this;
		}

		/**
		 * Overrides this cluster's setting; null leaves the regular Mailer setting effective. See {@link
		 * MailerGenericBuilder#withConnectionPoolClaimTimeout(Duration)} for its meaning and constraints. Replaces the previous value.
		 */
		@NotNull
		public ConnectionPoolClusterConfigBuilder claimTimeout(@Nullable final Duration value) {
			this.claimTimeout = value;
			return this;
		}

		/** @see #claimTimeout(Duration) */
		@Nullable
		public Duration getClaimTimeout() {
			return claimTimeout;
		}

		/** @see #claimTimeout(Duration) */
		@NotNull
		public ConnectionPoolClusterConfigBuilder clearClaimTimeout() {
			this.claimTimeout = null;
			return this;
		}

		/**
		 * Overrides this cluster's setting; null leaves the regular Mailer setting effective. See {@link
		 * MailerGenericBuilder#withConnectionPoolExpireAfter(Duration)} for its meaning and constraints. Replaces the previous value.
		 */
		@NotNull
		public ConnectionPoolClusterConfigBuilder expireAfter(@Nullable final Duration value) {
			this.expireAfter = value;
			return this;
		}

		/** @see #expireAfter(Duration) */
		@Nullable
		public Duration getExpireAfter() {
			return expireAfter;
		}

		/** @see #expireAfter(Duration) */
		@NotNull
		public ConnectionPoolClusterConfigBuilder clearExpireAfter() {
			this.expireAfter = null;
			return this;
		}

		/**
		 * Overrides this cluster's setting; null leaves the regular Mailer setting effective. See {@link
		 * MailerGenericBuilder#withConnectionPoolExpireAfterCreation(Duration)} for its meaning and constraints. Replaces the previous value.
		 */
		@NotNull
		public ConnectionPoolClusterConfigBuilder expireAfterCreation(@Nullable final Duration value) {
			this.expireAfterCreation = value;
			return this;
		}

		/** @see #expireAfterCreation(Duration) */
		@Nullable
		public Duration getExpireAfterCreation() {
			return expireAfterCreation;
		}

		/** @see #expireAfterCreation(Duration) */
		@NotNull
		public ConnectionPoolClusterConfigBuilder clearExpireAfterCreation() {
			this.expireAfterCreation = null;
			return this;
		}

		/**
		 * Overrides this cluster's setting; null leaves the regular Mailer setting effective. See {@link
		 * MailerGenericBuilder#withConnectionPoolLoadBalancingStrategy(LoadBalancingStrategy)} for its meaning and constraints. Replaces the previous
		 * value.
		 */
		@NotNull
		public ConnectionPoolClusterConfigBuilder loadBalancingStrategy(@Nullable final LoadBalancingStrategy value) {
			this.loadBalancingStrategy = value;
			return this;
		}

		/** @see #loadBalancingStrategy(LoadBalancingStrategy) */
		@Nullable
		public LoadBalancingStrategy getLoadBalancingStrategy() {
			return loadBalancingStrategy;
		}

		/** @see #loadBalancingStrategy(LoadBalancingStrategy) */
		@NotNull
		public ConnectionPoolClusterConfigBuilder clearLoadBalancingStrategy() {
			this.loadBalancingStrategy = null;
			return this;
		}

		/** @return A validated snapshot with defensive copies of supplied mutable data. */
		@NotNull
		public ConnectionPoolClusterConfig build() {
			return new ConnectionPoolClusterConfig(coreSize, maxSize, claimTimeout, expireAfter,
					expireAfterCreation, loadBalancingStrategy);
		}
	}
}
