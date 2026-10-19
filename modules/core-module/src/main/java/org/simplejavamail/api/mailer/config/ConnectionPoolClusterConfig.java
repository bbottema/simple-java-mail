package org.simplejavamail.api.mailer.config;

import lombok.Value;
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

	private ConnectionPoolClusterConfig(@Nullable final Integer coreSize, @Nullable final Integer maxSize,
			@Nullable final Integer claimTimeoutMillis, @Nullable final Integer expireAfterMillis,
			@Nullable final Integer expireAfterCreationMillis,
			@Nullable final LoadBalancingStrategy loadBalancingStrategy) {
		this.coreSize = coreSize;
		this.maxSize = maxSize;
		this.claimTimeoutMillis = claimTimeoutMillis;
		this.expireAfterMillis = expireAfterMillis;
		this.expireAfterCreationMillis = expireAfterCreationMillis;
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

	/** @see MailerGenericBuilder#withConnectionPoolClaimTimeoutMillis(Integer) */
	@Nullable
	public Integer getClaimTimeoutMillis() {
		return claimTimeoutMillis;
	}

	/** @see MailerGenericBuilder#withConnectionPoolExpireAfterMillis(Integer) */
	@Nullable
	public Integer getExpireAfterMillis() {
		return expireAfterMillis;
	}

	/** @see MailerGenericBuilder#withConnectionPoolExpireAfterCreationMillis(Integer) */
	@Nullable
	public Integer getExpireAfterCreationMillis() {
		return expireAfterCreationMillis;
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
		private Integer claimTimeoutMillis;
		private Integer expireAfterMillis;
		private Integer expireAfterCreationMillis;
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
		 * MailerGenericBuilder#withConnectionPoolClaimTimeoutMillis(Integer)} for its meaning and constraints. Replaces the previous value.
		 */
		@NotNull
		public ConnectionPoolClusterConfigBuilder claimTimeoutMillis(@Nullable final Integer value) {
			this.claimTimeoutMillis = value;
			return this;
		}

		/** @see #claimTimeoutMillis(Integer) */
		@Nullable
		public Integer getClaimTimeoutMillis() {
			return claimTimeoutMillis;
		}

		/** @see #claimTimeoutMillis(Integer) */
		@NotNull
		public ConnectionPoolClusterConfigBuilder clearClaimTimeoutMillis() {
			this.claimTimeoutMillis = null;
			return this;
		}

		/**
		 * Overrides this cluster's setting; null leaves the regular Mailer setting effective. See {@link
		 * MailerGenericBuilder#withConnectionPoolExpireAfterMillis(Integer)} for its meaning and constraints. Replaces the previous value.
		 */
		@NotNull
		public ConnectionPoolClusterConfigBuilder expireAfterMillis(@Nullable final Integer value) {
			this.expireAfterMillis = value;
			return this;
		}

		/** @see #expireAfterMillis(Integer) */
		@Nullable
		public Integer getExpireAfterMillis() {
			return expireAfterMillis;
		}

		/** @see #expireAfterMillis(Integer) */
		@NotNull
		public ConnectionPoolClusterConfigBuilder clearExpireAfterMillis() {
			this.expireAfterMillis = null;
			return this;
		}

		/**
		 * Overrides this cluster's setting; null leaves the regular Mailer setting effective. See {@link
		 * MailerGenericBuilder#withConnectionPoolExpireAfterCreationMillis(Integer)} for its meaning and constraints. Replaces the previous value.
		 */
		@NotNull
		public ConnectionPoolClusterConfigBuilder expireAfterCreationMillis(@Nullable final Integer value) {
			this.expireAfterCreationMillis = value;
			return this;
		}

		/** @see #expireAfterCreationMillis(Integer) */
		@Nullable
		public Integer getExpireAfterCreationMillis() {
			return expireAfterCreationMillis;
		}

		/** @see #expireAfterCreationMillis(Integer) */
		@NotNull
		public ConnectionPoolClusterConfigBuilder clearExpireAfterCreationMillis() {
			this.expireAfterCreationMillis = null;
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
			return new ConnectionPoolClusterConfig(coreSize, maxSize, claimTimeoutMillis, expireAfterMillis,
					expireAfterCreationMillis, loadBalancingStrategy);
		}
	}
}
