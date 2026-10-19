package org.simplejavamail.api.mailer.config;

import lombok.Value;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailerGenericBuilder;

import java.io.Serializable;
import java.time.Duration;

import static java.util.Objects.requireNonNull;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_FAILOVER_ON_AUTHENTICATION_FAILURE;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_FAILOVER_ON_CERTIFICATE_VERIFICATION_FAILURE;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_FAILOVER_PRIMARY_CONNECTION_ATTEMPTS;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_FAILOVER_PRIMARY_CONNECTION_RETRY_DELAY;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_FAILOVER_PRIMARY_RECHECK_BACKOFF_MULTIPLIER;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_FAILOVER_PRIMARY_RECHECK_INTERVAL;

/**
 * Rules for obtaining a usable connection before moving to a configured failover Mailer. They never authorize
 * replaying an email after submission begins. A busy connection pool or a sending-limit wait is not an outage.
 * Configure the relationship and policy together with
 * {@link org.simplejavamail.api.mailer.MailerGenericBuilder#withFailoverMailer(org.simplejavamail.api.mailer.Mailer, FailoverPolicy)}.
 * This immutable value contains no connection history, counters or live Mailer references.
 */
@Value
public class FailoverPolicy implements Serializable {
    private static final long serialVersionUID = 1L;

    int primaryConnectionAttempts;
    @NotNull Duration primaryConnectionRetryDelay;
    @NotNull Duration primaryRecheckInterval;
    int primaryRecheckBackoffMultiplier;
    @NotNull Duration primaryMaximumRecheckInterval;
    boolean failoverOnAuthenticationFailure;
    boolean failoverOnCertificateVerificationFailure;

    /**
     * Constructs a policy from supplied values. Prefer {@link #builder()} for the documented defaults.
     *
     * @throws NullPointerException If a duration is {@code null}.
     * @throws IllegalArgumentException If attempts or the recheck schedule are invalid, or a duration cannot be measured in nanoseconds.
     */
    public FailoverPolicy(final int primaryConnectionAttempts, @NotNull final Duration primaryConnectionRetryDelay,
            @NotNull final Duration primaryRecheckInterval, final int primaryRecheckBackoffMultiplier,
            @NotNull final Duration primaryMaximumRecheckInterval, final boolean failoverOnAuthenticationFailure,
            final boolean failoverOnCertificateVerificationFailure) {
        validatePrimaryConnectionAttempts(primaryConnectionAttempts);
        validateDuration(primaryConnectionRetryDelay, "Connection retry delay", true);
        validateDuration(primaryRecheckInterval, "Primary recheck interval", false);
        validateDuration(primaryMaximumRecheckInterval, "Maximum primary recheck interval", false);
        validateRecheckSchedule(primaryRecheckBackoffMultiplier, primaryMaximumRecheckInterval, primaryRecheckInterval);
        this.primaryConnectionAttempts = primaryConnectionAttempts;
        this.primaryConnectionRetryDelay = primaryConnectionRetryDelay;
        this.primaryRecheckInterval = primaryRecheckInterval;
        this.primaryRecheckBackoffMultiplier = primaryRecheckBackoffMultiplier;
        this.primaryMaximumRecheckInterval = primaryMaximumRecheckInterval;
        this.failoverOnAuthenticationFailure = failoverOnAuthenticationFailure;
        this.failoverOnCertificateVerificationFailure = failoverOnCertificateVerificationFailure;
    }

    @NotNull
    public static Builder builder() {
        return new Builder();
    }

    /** @see Builder#withPrimaryConnectionAttempts(int) */
    public int getPrimaryConnectionAttempts() {
        return primaryConnectionAttempts;
    }

    /** @see Builder#withPrimaryConnectionRetryDelay(Duration) */
    @NotNull
    public Duration getPrimaryConnectionRetryDelay() {
        return primaryConnectionRetryDelay;
    }

    /** @see Builder#withPrimaryRecheckInterval(Duration) */
    @NotNull
    public Duration getPrimaryRecheckInterval() {
        return primaryRecheckInterval;
    }

    /** @see Builder#withPrimaryRecheckBackoff(int, Duration) */
    public int getPrimaryRecheckBackoffMultiplier() {
        return primaryRecheckBackoffMultiplier;
    }

    /**
     * Returns the supplied maximum interval; with fixed rechecks, the builder supplies the initial interval.
     *
     * @see Builder#withPrimaryRecheckBackoff(int, Duration)
     * @see Builder#resetPrimaryRecheckBackoff()
     */
    @NotNull
    public Duration getPrimaryMaximumRecheckInterval() {
        return primaryMaximumRecheckInterval;
    }

    /** @see Builder#withFailoverOnAuthenticationFailure(boolean) */
    public boolean isFailoverOnAuthenticationFailure() {
        return failoverOnAuthenticationFailure;
    }

    /** @see Builder#withFailoverOnCertificateVerificationFailure(boolean) */
    public boolean isFailoverOnCertificateVerificationFailure() {
        return failoverOnCertificateVerificationFailure;
    }

    private static void validatePrimaryConnectionAttempts(final int primaryConnectionAttempts) {
        if (primaryConnectionAttempts < 1) {
            throw new IllegalArgumentException("Allow at least one connection attempt; the count includes the initial attempt.");
        }
    }

    private static void validateDuration(final Duration value, final String name, final boolean allowZero) {
        requireNonNull(value, name);
        if (value.isNegative() || !allowZero && value.isZero()) {
            throw new IllegalArgumentException(name + (allowZero ? " must be zero or positive." : " must be positive."));
        }
        try {
            value.toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(name + " is too large to measure; choose a shorter duration.", overflow);
        }
    }

    private static void validateRecheckSchedule(final int primaryRecheckBackoffMultiplier,
            final Duration primaryMaximumRecheckInterval, final Duration primaryRecheckInterval) {
        if (primaryRecheckBackoffMultiplier < 1 || primaryMaximumRecheckInterval.compareTo(primaryRecheckInterval) < 0) {
            throw new IllegalArgumentException("Use a recheck multiplier of at least one and a maximum interval no shorter than the initial interval.");
        }
    }

    private Object readResolve() {
        return new FailoverPolicy(primaryConnectionAttempts, primaryConnectionRetryDelay, primaryRecheckInterval,
                primaryRecheckBackoffMultiplier, primaryMaximumRecheckInterval, failoverOnAuthenticationFailure, failoverOnCertificateVerificationFailure);
    }

    /**
     * Resolves the defaults published on {@link MailerGenericBuilder} and validates the combined policy when {@link #build()} is called.
     * Repeated setters replace the previous choice.
     */
    public static final class Builder {
        private int primaryConnectionAttempts = DEFAULT_FAILOVER_PRIMARY_CONNECTION_ATTEMPTS;
        private Duration primaryConnectionRetryDelay = DEFAULT_FAILOVER_PRIMARY_CONNECTION_RETRY_DELAY;
        private Duration primaryRecheckInterval = DEFAULT_FAILOVER_PRIMARY_RECHECK_INTERVAL;
        private int primaryRecheckBackoffMultiplier = DEFAULT_FAILOVER_PRIMARY_RECHECK_BACKOFF_MULTIPLIER;
        private Duration primaryMaximumRecheckInterval;
        private boolean failoverOnAuthenticationFailure = DEFAULT_FAILOVER_ON_AUTHENTICATION_FAILURE;
        private boolean failoverOnCertificateVerificationFailure = DEFAULT_FAILOVER_ON_CERTIFICATE_VERIFICATION_FAILURE;

        private Builder() { }

        /**
         * Total setup attempts per candidate in one visit, including the first; not attempts to submit message content.
         * Defaults to {@link MailerGenericBuilder#DEFAULT_FAILOVER_PRIMARY_CONNECTION_ATTEMPTS one setup attempt}.
         */
        @NotNull
        public Builder withPrimaryConnectionAttempts(final int primaryConnectionAttempts) {
            this.primaryConnectionAttempts = primaryConnectionAttempts;
            return this;
        }

        /** @see #withPrimaryConnectionAttempts(int) */
        @NotNull
        public Builder resetPrimaryConnectionAttempts() {
            return withPrimaryConnectionAttempts(DEFAULT_FAILOVER_PRIMARY_CONNECTION_ATTEMPTS);
        }

        /**
         * Non-negative delay between additional setup attempts explicitly enabled with {@link #withPrimaryConnectionAttempts(int)}.
         * Defaults to {@link MailerGenericBuilder#DEFAULT_FAILOVER_PRIMARY_CONNECTION_RETRY_DELAY no delay}.
         */
        @NotNull
        public Builder withPrimaryConnectionRetryDelay(@NotNull final Duration primaryConnectionRetryDelay) {
            this.primaryConnectionRetryDelay = requireNonNull(primaryConnectionRetryDelay, "primaryConnectionRetryDelay");
            return this;
        }

        /** @see #withPrimaryConnectionRetryDelay(Duration) */
        @NotNull
        public Builder resetPrimaryConnectionRetryDelay() {
            return withPrimaryConnectionRetryDelay(DEFAULT_FAILOVER_PRIMARY_CONNECTION_RETRY_DELAY);
        }

        /**
         * Positive initial interval before reconsidering a failed destination. For example, 30 seconds lets other sends
         * continue through a working failover Mailer while only one operation checks the primary when it is due again.
         * Defaults to {@link MailerGenericBuilder#DEFAULT_FAILOVER_PRIMARY_RECHECK_INTERVAL thirty seconds}.
         * This is not a delay before trying the failover Mailer.
         */
        @NotNull
        public Builder withPrimaryRecheckInterval(@NotNull final Duration primaryRecheckInterval) {
            this.primaryRecheckInterval = requireNonNull(primaryRecheckInterval, "primaryRecheckInterval");
            return this;
        }

        /** @see #withPrimaryRecheckInterval(Duration) */
        @NotNull
        public Builder resetPrimaryRecheckInterval() {
            return withPrimaryRecheckInterval(DEFAULT_FAILOVER_PRIMARY_RECHECK_INTERVAL);
        }

        /**
         * Increases the reconsideration interval once per failed recheck visit, up to the supplied maximum.
         * Additional connection attempts within that visit do not increase it again. The initial failed visit still
         * uses {@link #withPrimaryRecheckInterval(Duration)} before its first recheck.
         * The multiplier must exceed one and the maximum must cover the initial interval. Disabled by default.
         */
        @NotNull
        public Builder withPrimaryRecheckBackoff(final int primaryRecheckBackoffMultiplier,
                @NotNull final Duration primaryMaximumRecheckInterval) {
            if (primaryRecheckBackoffMultiplier <= 1) {
                throw new IllegalArgumentException("Increasing recheck backoff needs a multiplier greater than one; "
                        + "use resetPrimaryRecheckBackoff() for fixed intervals.");
            }
            this.primaryRecheckBackoffMultiplier = primaryRecheckBackoffMultiplier;
            this.primaryMaximumRecheckInterval = requireNonNull(primaryMaximumRecheckInterval, "primaryMaximumRecheckInterval");
            return this;
        }

        /** @see #withPrimaryRecheckBackoff(int, Duration) */
        @NotNull
        public Builder resetPrimaryRecheckBackoff() {
            primaryRecheckBackoffMultiplier = DEFAULT_FAILOVER_PRIMARY_RECHECK_BACKOFF_MULTIPLIER;
            primaryMaximumRecheckInterval = null;
            return this;
        }

        /**
         * Allows a separately configured destination to take over when the primary rejects its credentials.
         * Defaults to {@link MailerGenericBuilder#DEFAULT_FAILOVER_ON_AUTHENTICATION_FAILURE disabled}.
         */
        @NotNull
        public Builder withFailoverOnAuthenticationFailure(final boolean enabled) {
            failoverOnAuthenticationFailure = enabled;
            return this;
        }

        /** @see #withFailoverOnAuthenticationFailure(boolean) */
        @NotNull
        public Builder resetFailoverOnAuthenticationFailure() {
            return withFailoverOnAuthenticationFailure(DEFAULT_FAILOVER_ON_AUTHENTICATION_FAILURE);
        }

        /**
         * Allows another configured destination when this one fails certificate or hostname verification, for example
         * after an expired certificate. Both destinations retain their own TLS requirements; verification is never disabled.
         * Defaults to {@link MailerGenericBuilder#DEFAULT_FAILOVER_ON_CERTIFICATE_VERIFICATION_FAILURE disabled}, independently of the authentication choice.
         */
        @NotNull
        public Builder withFailoverOnCertificateVerificationFailure(final boolean enabled) {
            failoverOnCertificateVerificationFailure = enabled;
            return this;
        }

        /** @see #withFailoverOnCertificateVerificationFailure(boolean) */
        @NotNull
        public Builder resetFailoverOnCertificateVerificationFailure() {
            return withFailoverOnCertificateVerificationFailure(DEFAULT_FAILOVER_ON_CERTIFICATE_VERIFICATION_FAILURE);
        }

        /** @see #withPrimaryConnectionAttempts(int) */
        public int getPrimaryConnectionAttempts() {
            return primaryConnectionAttempts;
        }

        /** @see #withPrimaryConnectionRetryDelay(Duration) */
        @NotNull
        public Duration getPrimaryConnectionRetryDelay() {
            return primaryConnectionRetryDelay;
        }

        /** @see #withPrimaryRecheckInterval(Duration) */
        @NotNull
        public Duration getPrimaryRecheckInterval() {
            return primaryRecheckInterval;
        }

        /** @see #withPrimaryRecheckBackoff(int, Duration) */
        public int getPrimaryRecheckBackoffMultiplier() {
            return primaryRecheckBackoffMultiplier;
        }

        /**
         * Returns the configured cap, or null until a cap is supplied. Building resolves a missing cap from the initial interval.
         *
         * @see #withPrimaryRecheckBackoff(int, Duration)
         */
        @Nullable
        public Duration getPrimaryMaximumRecheckInterval() {
            return primaryMaximumRecheckInterval;
        }

        /** @see #withFailoverOnAuthenticationFailure(boolean) */
        public boolean isFailoverOnAuthenticationFailure() {
            return failoverOnAuthenticationFailure;
        }

        /** @see #withFailoverOnCertificateVerificationFailure(boolean) */
        public boolean isFailoverOnCertificateVerificationFailure() {
            return failoverOnCertificateVerificationFailure;
        }

        @NotNull
        public FailoverPolicy build() {
            return new FailoverPolicy(primaryConnectionAttempts, primaryConnectionRetryDelay, primaryRecheckInterval,
                    primaryRecheckBackoffMultiplier, resolvePrimaryMaximumRecheckInterval(), failoverOnAuthenticationFailure,
                    failoverOnCertificateVerificationFailure);
        }

        @NotNull
        private Duration resolvePrimaryMaximumRecheckInterval() {
            return primaryMaximumRecheckInterval == null ? primaryRecheckInterval : primaryMaximumRecheckInterval;
        }
    }
}
