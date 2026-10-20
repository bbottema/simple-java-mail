package org.simplejavamail.api.mailer.config;

import lombok.Value;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.internal.util.DurationMillis;
import java.time.Duration;
import java.util.UUID;
import static java.util.Objects.requireNonNull;

/**
 * Immutable supplied ConnectionPoolConfig choices. Unset fields inherit the owning Mailer factory snapshot.
 * Construction and inspection never start workers, connect, resolve configuration sources or own borrowed resources.
 * Replacing this group on a Mailer discards its previous supplied choices.
 */
@Value
public class ConnectionPoolConfig {
    @Nullable Integer coreSize;
    @Nullable Integer maxSize;
    @Nullable Duration claimTimeout;
    @Nullable Duration expireAfter;
    @Nullable Duration expireAfterCreation;
    boolean expireAfterCreationCleared;
    @Nullable UUID clusterKey;
    @Nullable LoadBalancingStrategy loadBalancingStrategy;

    private ConnectionPoolConfig(final Builder builder) {
        coreSize = builder.coreSize;
        maxSize = builder.maxSize;
        claimTimeout = builder.claimTimeout;
        DurationMillis.toNullableInt(claimTimeout, "claimTimeout");
        expireAfter = builder.expireAfter;
        DurationMillis.toNullableInt(expireAfter, "expireAfter");
        expireAfterCreation = builder.expireAfterCreation;
        DurationMillis.toNullableInt(expireAfterCreation, "expireAfterCreation");
        expireAfterCreationCleared = builder.expireAfterCreationCleared;
        clusterKey = builder.clusterKey;
        loadBalancingStrategy = builder.loadBalancingStrategy;
    }

    /** @return A builder containing no supplied choices. */
    @NotNull public static Builder builder() { return new Builder(); }
    /** @return An independent builder retaining supplied choices and borrowed-resource identities. */
    @NotNull public Builder toBuilder() { return new Builder(this); }
    /** @see Builder#withCoreSize(Integer) */
    @Nullable public Integer getCoreSize() { return coreSize; }
    /** @see Builder#withMaxSize(Integer) */
    @Nullable public Integer getMaxSize() { return maxSize; }
    /** @see Builder#withClaimTimeout(Duration) */
    @Nullable public Duration getClaimTimeout() { return claimTimeout; }
    /** @see Builder#withExpireAfter(Duration) */
    @Nullable public Duration getExpireAfter() { return expireAfter; }
    /** @see Builder#withExpireAfterCreation(Duration) */
    @Nullable public Duration getExpireAfterCreation() { return expireAfterCreation; }
    /**
     * Whether the supplied group explicitly removes this choice instead of inheriting it.
     * @see Builder#clearExpireAfterCreation()
     */
    public boolean isExpireAfterCreationCleared() { return expireAfterCreationCleared; }
    /** @see Builder#withClusterKey(UUID) */
    @Nullable public UUID getClusterKey() { return clusterKey; }
    /** @see Builder#withLoadBalancingStrategy(LoadBalancingStrategy) */
    @Nullable public LoadBalancingStrategy getLoadBalancingStrategy() { return loadBalancingStrategy; }

    /** Explicit choices for this group; inspection does not build or validate an incomplete configuration. */
    public static final class Builder {
        private Integer coreSize;
        private Integer maxSize;
        private Duration claimTimeout;
        private Duration expireAfter;
        private Duration expireAfterCreation;
        private boolean expireAfterCreationCleared;
        private UUID clusterKey;
        private LoadBalancingStrategy loadBalancingStrategy;
        private Builder() { }
        private Builder(final ConnectionPoolConfig config) {
            coreSize = config.coreSize;
            maxSize = config.maxSize;
            claimTimeout = config.claimTimeout;
            expireAfter = config.expireAfter;
            expireAfterCreation = config.expireAfterCreation;
            expireAfterCreationCleared = config.expireAfterCreationCleared;
            clusterKey = config.clusterKey;
            loadBalancingStrategy = config.loadBalancingStrategy;
        }
        /**
         * Configures the connection pool's core size (default {@value org.simplejavamail.api.mailer.MailerGenericBuilder#DEFAULT_CONNECTIONPOOL_CORE_SIZE}), which means the SMTP connection pool will keep X connections open at all times until shut down.
         * Use zero to disable core replenishment; negative sizes are invalid. The effective core size cannot exceed the maximum after cluster overrides.
         * The batch module checks the effective pool settings when the Mailer registers with it, before creating pool resources.
         * Without that module, these settings are unused and do not prevent direct sending.
         * Note that this also means that if you configure an auto-expiry timeout, these connections die off and new ones are created immediately to maintain core size.
         * <p>
         * When using clustered batch sending, the first {@link Mailer} registered for a cluster determines this setting for that cluster.
         * <p>
         * <strong>Note:</strong> this is only used in combination with the {@value org.simplejavamail.internal.modules.BatchModule#NAME}.
         *
         * @param coreSize See main description.
         */
        @NotNull public Builder withCoreSize(final Integer coreSize) { this.coreSize = requireNonNull(coreSize, "coreSize"); return this; }
        /** @see #withCoreSize(Integer) */
        @Nullable public Integer getCoreSize() { return coreSize; }
        /**
         * Selects the published library fallback.
         * @see #withCoreSize(Integer)
         */
        @NotNull public Builder resetCoreSize() { return withCoreSize(MailerGenericBuilder.DEFAULT_CONNECTIONPOOL_CORE_SIZE); }
        /**
         * Restores captured configuration for this member.
         * @see #withCoreSize(Integer)
         */
        @NotNull public Builder useConfiguredCoreSize() { coreSize = null; return this; }
        /**
         * Sets the maximum number of connections to one server (default {@value org.simplejavamail.api.mailer.MailerGenericBuilder#DEFAULT_CONNECTIONPOOL_MAX_SIZE}). It must be at least one and no
         * smaller than the effective core size after cluster overrides. Make sure your server can handle the load: a hundred concurrent connections
         * are not useful if they degrade its performance through CPU throttling or network congestion.
         * <p>
         * In addition, if your server makes connections wait, it means threads will be waiting on the {@link jakarta.mail.Transport} instance to start their work load, instead of threads being blocked
         * on a <em>claim</em> for an available {@code Transport} instance. In other words: by having an oversized connection pool, you inadvertently bypass the blocking claim mechanism of the
         * connection pool and wait on the Transport directly instead.
         * <p>
         * When using clustered batch sending, the first {@link Mailer} registered for a cluster determines this setting for that cluster.
         * <p>
         * <strong>Note:</strong> this is only used in combination with the {@value org.simplejavamail.internal.modules.BatchModule#NAME}.
         *
         * @param maxSize See main description.
         */
        @NotNull public Builder withMaxSize(final Integer maxSize) { this.maxSize = requireNonNull(maxSize, "maxSize"); return this; }
        /** @see #withMaxSize(Integer) */
        @Nullable public Integer getMaxSize() { return maxSize; }
        /**
         * Selects the published library fallback.
         * @see #withMaxSize(Integer)
         */
        @NotNull public Builder resetMaxSize() { return withMaxSize(MailerGenericBuilder.DEFAULT_CONNECTIONPOOL_MAX_SIZE); }
        /**
         * Restores captured configuration for this member.
         * @see #withMaxSize(Integer)
         */
        @NotNull public Builder useConfiguredMaxSize() { maxSize = null; return this; }
        /**
         * If {@code >0}, configures the connection pool to wait for a limited time after which the attempt to claim a Transport connection errors out.
         * Use zero for a non-waiting claim; negative timeouts are invalid.
         * The default {@link org.simplejavamail.api.mailer.MailerGenericBuilder#DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT} is approximately 24.9 days.
         * <p>
         * When using clustered batch sending, the first {@link Mailer} registered for a cluster determines this setting for that cluster.
         * <p>
         * <strong>Note:</strong> this is only used in combination with the {@value org.simplejavamail.internal.modules.BatchModule#NAME}.
         *
         * @param claimTimeout Nonnegative duration, exactly representable as integer milliseconds.
         * @throws IllegalArgumentException If the duration has fractional milliseconds or exceeds the integer-millisecond range.
         */
        @NotNull public Builder withClaimTimeout(final Duration claimTimeout) { DurationMillis.toNullableInt(claimTimeout, "claimTimeout"); this.claimTimeout = requireNonNull(claimTimeout, "claimTimeout"); return this; }
        /** @see #withClaimTimeout(Duration) */
        @Nullable public Duration getClaimTimeout() { return claimTimeout; }
        /**
         * Selects the published library fallback.
         * @see #withClaimTimeout(Duration)
         */
        @NotNull public Builder resetClaimTimeout() { return withClaimTimeout(MailerGenericBuilder.DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT); }
        /**
         * Restores captured configuration for this member.
         * @see #withClaimTimeout(Duration)
         */
        @NotNull public Builder useConfiguredClaimTimeout() { claimTimeout = null; return this; }
        /**
         * If {@code >0}, configures the connection pool to automatically close connections after the configured idle duration (default {@link org.simplejavamail.api.mailer.MailerGenericBuilder#DEFAULT_CONNECTIONPOOL_EXPIREAFTER}).
         * Use zero to disable idle expiration; negative durations are invalid.
         * <p>
         * Note that if you combine this with {@link #withCoreSize(Integer)} also {@code >0} (default is {@value org.simplejavamail.api.mailer.MailerGenericBuilder#DEFAULT_CONNECTIONPOOL_CORE_SIZE}), connections will keep
         * closing and opening to keep core pool populated until shut down.
         * <p>
         * When using clustered batch sending, the first {@link Mailer} registered for a cluster determines this setting for that cluster.
         * <p>
         * <strong>Note:</strong> this is only used in combination with the {@value org.simplejavamail.internal.modules.BatchModule#NAME}.
         *
         * @param expireAfter Nonnegative duration, exactly representable as integer milliseconds.
         * @throws IllegalArgumentException If the duration has fractional milliseconds or exceeds the integer-millisecond range.
         */
        @NotNull public Builder withExpireAfter(final Duration expireAfter) { DurationMillis.toNullableInt(expireAfter, "expireAfter"); this.expireAfter = requireNonNull(expireAfter, "expireAfter"); return this; }
        /** @see #withExpireAfter(Duration) */
        @Nullable public Duration getExpireAfter() { return expireAfter; }
        /**
         * Selects the published library fallback.
         * @see #withExpireAfter(Duration)
         */
        @NotNull public Builder resetExpireAfter() { return withExpireAfter(MailerGenericBuilder.DEFAULT_CONNECTIONPOOL_EXPIREAFTER); }
        /**
         * Restores captured configuration for this member.
         * @see #withExpireAfter(Duration)
         */
        @NotNull public Builder useConfiguredExpireAfter() { expireAfter = null; return this; }
        /**
         * Configures the age after which an available pooled connection becomes eligible for retirement, measured from the
         * connection's creation time. Creation-age expiration is disabled when no value is configured.
         * <p>
         * This threshold is independent of {@link #withExpireAfter(Duration) idle expiration}. When both
         * thresholds are enabled, either can retire an available connection. Claiming or releasing a connection does not reset
         * its creation age.
         * <p>
         * Expiration is checked asynchronously while a connection is available. Active connections are not interrupted and
         * rapid reuse can postpone retirement, so this setting is not a strict maximum connection lifetime or a guarantee that
         * a connection will be retired before a server-side timeout.
         * <p>
         * When using clustered batch sending, the first {@link Mailer} registered for a cluster determines this setting for that
         * cluster.
         * <p>
         * <strong>Note:</strong> this is only used in combination with the
         * {@value org.simplejavamail.internal.modules.BatchModule#NAME}.
         *
         * @param expireAfterCreation Positive duration, exactly representable as integer milliseconds.
         * @throws IllegalArgumentException If the duration has fractional milliseconds or exceeds the integer-millisecond range.
         * @see #clearExpireAfterCreation()
         */
        @NotNull public Builder withExpireAfterCreation(@NotNull final Duration expireAfterCreation) { DurationMillis.toInt(expireAfterCreation, "expireAfterCreation"); this.expireAfterCreation = expireAfterCreation; expireAfterCreationCleared = false; return this; }
        /** @see #withExpireAfterCreation(Duration) */
        @Nullable public Duration getExpireAfterCreation() { return expireAfterCreation; }
        /**
         * Disables creation-age expiration by removing the duration, including a captured choice.
         * Idle expiration is unchanged; this setting is used only by the batch module.
         * @see #withExpireAfterCreation(Duration)
         */
        @NotNull public Builder clearExpireAfterCreation() { expireAfterCreation = null; expireAfterCreationCleared = true; return this; }
        /**
         * Restores captured configuration for this member.
         * @see #withExpireAfterCreation(Duration)
         */
        @NotNull public Builder useConfiguredExpireAfterCreation() { expireAfterCreation = null; expireAfterCreationCleared = false; return this; }
        /**
         * By defining a clusterKey, you can form clusters where other {@link Mailer} instances represent
         * individual connection pools within the same cluster. Having multiple mailers using the same clusterKey
         * means those mailes form a cluster where mail-send action are rotated over connection pools stemming from these
         * mailer instances (this has implications for mailers defining connections differently from eachother, see documentation).
         * <p>
         * By default, a cluster key is uniquely generated, so for a single new mailer a new cluster is always generated,
         * thus effectively nothing is clustered.
         *
         * @see <a href="https://www.simplejavamail.org/configuration.html#section-batch-and-clustering">Clustering with Simple Java Mail</a>
         *
         * @param clusterKey See main description.
         */
        @NotNull public Builder withClusterKey(final UUID clusterKey) { this.clusterKey = requireNonNull(clusterKey, "clusterKey"); return this; }
        /** @see #withClusterKey(UUID) */
        @Nullable public UUID getClusterKey() { return clusterKey; }
        /**
         * Selects a fresh, unique cluster key now, keeping this configuration apart from other Mailer clusters.
         * Reusing this immutable configuration deliberately reuses that generated key; this does not restore a captured cluster key.
         * @see #withClusterKey(UUID)
         */
        @NotNull public Builder resetClusterKey() { return withClusterKey(UUID.randomUUID()); }
        /**
         * Restores captured configuration for this member.
         * @see #withClusterKey(UUID)
         */
        @NotNull public Builder useConfiguredClusterKey() { clusterKey = null; return this; }
        /**
         * Defines the various types of load balancing modes supported by the connection pool ion the
         * <a href="https://www.simplejavamail.org/configuration.html#section-batch-and-clustering">batch-module</a>.
         * <p>
         * This is only relevant if you have multiple mail servers in one or more clusters. When using clustered batch sending,
         * the first {@link Mailer} registered for a cluster determines this setting for that cluster.
         * <p>
         * <strong>Note:</strong> this is only used in combination with the {@value org.simplejavamail.internal.modules.BatchModule#NAME}.
         *
         * @param loadBalancingStrategy See main description.
         * @see MailerGenericBuilder#DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY
         */
        @NotNull public Builder withLoadBalancingStrategy(final LoadBalancingStrategy loadBalancingStrategy) { this.loadBalancingStrategy = requireNonNull(loadBalancingStrategy, "loadBalancingStrategy"); return this; }
        /** @see #withLoadBalancingStrategy(LoadBalancingStrategy) */
        @Nullable public LoadBalancingStrategy getLoadBalancingStrategy() { return loadBalancingStrategy; }
        /**
         * Selects the published library fallback.
         * @see #withLoadBalancingStrategy(LoadBalancingStrategy)
         */
        @NotNull public Builder resetLoadBalancingStrategy() { return withLoadBalancingStrategy(LoadBalancingStrategy.valueOf(MailerGenericBuilder.DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY)); }
        /**
         * Restores captured configuration for this member.
         * @see #withLoadBalancingStrategy(LoadBalancingStrategy)
         */
        @NotNull public Builder useConfiguredLoadBalancingStrategy() { loadBalancingStrategy = null; return this; }
        /** @return An immutable snapshot; feature validation remains with its owning runtime. */
        @NotNull public ConnectionPoolConfig build() { return new ConnectionPoolConfig(this); }
    }
}
