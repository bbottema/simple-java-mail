package org.simplejavamail.api.mailer.config;

import lombok.Value;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.time.Duration;

import static java.util.Objects.requireNonNull;

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

    /** Resolves defaults and validates the combined policy when {@link #build()} is called. Repeated setters replace the previous choice. */
    public static final class Builder {
        private int primaryConnectionAttempts = 1;
        private Duration primaryConnectionRetryDelay = Duration.ZERO;
        private Duration primaryRecheckInterval = Duration.ofSeconds(30);
        private int primaryRecheckBackoffMultiplier = 1;
        private Duration primaryMaximumRecheckInterval;
        private boolean failoverOnAuthenticationFailure;
        private boolean failoverOnCertificateVerificationFailure;

        private Builder() { }

        /** Total setup attempts per candidate in one visit, including the first; not attempts to submit message content. Default: one. */
        @NotNull
        public Builder withPrimaryConnectionAttempts(final int primaryConnectionAttempts) {
            this.primaryConnectionAttempts = primaryConnectionAttempts;
            return this;
        }

        /** Restores one setup attempt per candidate. */
        @NotNull
        public Builder resetPrimaryConnectionAttempts() {
            return withPrimaryConnectionAttempts(1);
        }

        /** Non-negative delay between additional setup attempts explicitly enabled with {@link #withPrimaryConnectionAttempts(int)}. Default: zero. */
        @NotNull
        public Builder withPrimaryConnectionRetryDelay(@NotNull final Duration primaryConnectionRetryDelay) {
            this.primaryConnectionRetryDelay = requireNonNull(primaryConnectionRetryDelay, "primaryConnectionRetryDelay");
            return this;
        }

        /** Restores zero delay between additional setup attempts. */
        @NotNull
        public Builder resetPrimaryConnectionRetryDelay() {
            return withPrimaryConnectionRetryDelay(Duration.ZERO);
        }

        /**
         * Positive initial interval before reconsidering a failed destination. For example, 30 seconds lets other sends
         * continue through a working failover Mailer while only one operation checks the primary when it is due again.
         * Default: 30 seconds. This is not a delay before trying the failover Mailer.
         */
        @NotNull
        public Builder withPrimaryRecheckInterval(@NotNull final Duration primaryRecheckInterval) {
            this.primaryRecheckInterval = requireNonNull(primaryRecheckInterval, "primaryRecheckInterval");
            return this;
        }

        /** Restores the initial reconsideration interval to 30 seconds. */
        @NotNull
        public Builder resetPrimaryRecheckInterval() {
            return withPrimaryRecheckInterval(Duration.ofSeconds(30));
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

        /** Restores fixed reconsideration intervals, using {@link #withPrimaryRecheckInterval(Duration)}. */
        @NotNull
        public Builder resetPrimaryRecheckBackoff() {
            primaryRecheckBackoffMultiplier = 1;
            primaryMaximumRecheckInterval = null;
            return this;
        }

        /** Allows a separately configured destination to take over when the primary rejects its credentials. Default: false. */
        @NotNull
        public Builder withFailoverOnAuthenticationFailure(final boolean enabled) {
            failoverOnAuthenticationFailure = enabled;
            return this;
        }

        /** Restores stopping on authentication rejection. */
        @NotNull
        public Builder resetFailoverOnAuthenticationFailure() {
            return withFailoverOnAuthenticationFailure(false);
        }

        /**
         * Allows another configured destination when this one fails certificate or hostname verification, for example
         * after an expired certificate. Both destinations retain their own TLS requirements; verification is never disabled.
         * Default: false, independently of the authentication choice.
         */
        @NotNull
        public Builder withFailoverOnCertificateVerificationFailure(final boolean enabled) {
            failoverOnCertificateVerificationFailure = enabled;
            return this;
        }

        /** Restores stopping on certificate or server-identity verification failure. */
        @NotNull
        public Builder resetFailoverOnCertificateVerificationFailure() {
            return withFailoverOnCertificateVerificationFailure(false);
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
