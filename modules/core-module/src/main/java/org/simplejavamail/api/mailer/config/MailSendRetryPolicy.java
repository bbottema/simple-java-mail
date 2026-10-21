package org.simplejavamail.api.mailer.config;

import lombok.Value;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailerGenericBuilder;

import java.io.Serializable;
import java.time.Duration;

import static java.util.Objects.requireNonNull;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_MAIL_SEND_RETRY_BACKOFF_MULTIPLIER;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_MAIL_SEND_RETRY_JITTER;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_MAIL_SEND_RETRY_WAITING_CAPACITY;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_MAIL_SEND_RETRY_MAXIMUM_MESSAGE_SIZE;
import static org.simplejavamail.api.mailer.MailerGenericBuilder.DEFAULT_MAIL_SEND_RETRY_MEMORY_LIMIT;

/**
 * Settings for trying the same email again after a safely replayable temporary SMTP refusal, including the resources retained between attempts.
 * The selected Mailer supplies the retry schedule; the initiating Mailer supplies the waiting and replay limits from its own policy.
 * Unset choices inherit the owning factory's captured configuration, then the library fallbacks. Resource choices alone do not enable
 * retries when no schedule is configured, but can still bound work delegated to a retry-enabled destination.
 * It never authorizes replay after partial or uncertain acceptance, and contains no counters, message content or executor.
 * Configure it with {@link MailerGenericBuilder#withMailSendRetryPolicy(MailSendRetryPolicy)}.
 */
@Value
public class MailSendRetryPolicy implements Serializable {
    private static final long serialVersionUID = 1L;
    /** Match the replay buffer's conservative Java-array ceiling; this is a representation limit, not a configurable default. */
    private static final long MAX_REPLAY_ARRAY_SIZE = Integer.MAX_VALUE - 8L;

    @Nullable Integer maximumAttempts;
    boolean maximumAttemptsCleared;
    @Nullable Duration retryDelay;
    boolean retryDelayCleared;
    @Nullable Integer retryBackoffMultiplier;
    @Nullable Duration maximumRetryDelay;
    boolean maximumRetryDelayCleared;
    @Nullable Double retryJitter;
    @Nullable Integer waitingCapacity;
    @Nullable Long maximumMessageSize;
    @Nullable Long memoryLimit;

    /**
     * Validates a complete policy without resolving defaults. Prefer {@link #builder()} for choices that inherit captured configuration.
     *
     * A null attempt count and delay leave retries disabled. Supply both to enable a schedule.
     * @throws IllegalArgumentException If the schedule is incomplete or invalid, or the resource limits cannot retain one replay message.
     */
    public MailSendRetryPolicy(@Nullable final Integer maximumAttempts, @Nullable final Duration retryDelay, final int retryBackoffMultiplier,
            @Nullable final Duration maximumRetryDelay, final double retryJitter, final int waitingCapacity,
            final long maximumMessageSize, final long memoryLimit) {
        validateSchedule(maximumAttempts, retryDelay, retryBackoffMultiplier, maximumRetryDelay, retryJitter);
        validateResourceLimits(waitingCapacity, maximumMessageSize, memoryLimit);
        this.maximumAttempts = maximumAttempts;
        this.maximumAttemptsCleared = maximumAttempts == null;
        this.retryDelay = retryDelay;
        this.retryDelayCleared = retryDelay == null;
        this.retryBackoffMultiplier = retryBackoffMultiplier;
        this.maximumRetryDelay = maximumRetryDelay;
        this.maximumRetryDelayCleared = maximumRetryDelay == null;
        this.retryJitter = retryJitter;
        this.waitingCapacity = waitingCapacity;
        this.maximumMessageSize = maximumMessageSize;
        this.memoryLimit = memoryLimit;
    }

    private MailSendRetryPolicy(final Builder builder) {
        validateSuppliedSchedule(builder.maximumAttempts, builder.retryDelay, builder.retryBackoffMultiplier,
                builder.maximumRetryDelay, builder.retryJitter);
        validateResourceLimits(builder.waitingCapacity, builder.maximumMessageSize, builder.memoryLimit);
        maximumAttempts = builder.maximumAttempts;
        maximumAttemptsCleared = builder.maximumAttemptsCleared;
        retryDelay = builder.retryDelay;
        retryDelayCleared = builder.retryDelayCleared;
        retryBackoffMultiplier = builder.retryBackoffMultiplier;
        maximumRetryDelay = builder.maximumRetryDelay;
        maximumRetryDelayCleared = builder.maximumRetryDelayCleared;
        retryJitter = builder.retryJitter;
        waitingCapacity = builder.waitingCapacity;
        maximumMessageSize = builder.maximumMessageSize;
        memoryLimit = builder.memoryLimit;
    }

    private static void validateSchedule(@Nullable final Integer attempts, @Nullable final Duration delay, final int multiplier,
            @Nullable final Duration maximumDelay, final double jitter) {
        if (isScheduleUnset(attempts, delay, multiplier, maximumDelay, jitter)) {
            return;
        }

        requireCompleteSchedule(attempts, delay);
        validateSuppliedSchedule(attempts, delay, multiplier, maximumDelay, jitter);
        if (maximumDelay == null && delay.toNanos() * (1 + jitter) > Long.MAX_VALUE) {
            throw new IllegalArgumentException("The retry delay with jitter is too large to measure. Choose a shorter delay or an explicit maximum.");
        }
    }

    private static void validateSuppliedSchedule(@Nullable final Integer attempts, @Nullable final Duration delay,
            @Nullable final Integer multiplier, @Nullable final Duration maximumDelay, @Nullable final Double jitter) {
        if (attempts != null) {
            validateAttemptCount(attempts);
        }
        final Long initialNanos = delay == null ? null : positiveNanos(delay, "Initial retry delay");
        validateBackoff(multiplier, maximumDelay, initialNanos);
        validateJitter(jitter);
    }

    private static boolean isScheduleUnset(@Nullable final Integer attempts, @Nullable final Duration delay, final int multiplier,
            @Nullable final Duration maximumDelay, final double jitter) {
        return attempts == null && delay == null && multiplier == 1 && maximumDelay == null && jitter == 0;
    }

    private static void requireCompleteSchedule(@Nullable final Integer attempts, @Nullable final Duration delay) {
        if (attempts == null || delay == null) {
            throw new IllegalArgumentException("Automatic mail-send retries need both total attempts and an initial delay. "
                    + "Supply them through properties or withMaximumAttempts(...) and withRetryDelay(...). "
                    + "Use clearRetrySchedule() on the retry-policy builder to remove the schedule, backoff and jitter while retaining resource limits.");
        }
    }

    private static void validateAttemptCount(final int attempts) {
        if (attempts < 2) {
            throw new IllegalArgumentException("Allow at least two submission attempts, including the first. "
                    + "Use clearRetrySchedule() on the retry-policy builder to disable automatic retries while retaining its resource limits.");
        }
    }

    private static void validateBackoff(@Nullable final Integer multiplier, @Nullable final Duration maximumDelay,
            @Nullable final Long initialNanos) {
        if (multiplier != null && (multiplier < 1 || (multiplier > 1 && maximumDelay == null))) {
            throw new IllegalArgumentException("Use a multiplier of one for fixed retry delays, or supply a maximum delay for increasing backoff.");
        }
        if (maximumDelay != null) {
            final long maximumNanos = positiveNanos(maximumDelay, "Maximum retry delay");
            if (initialNanos != null && maximumNanos < initialNanos) {
                throw new IllegalArgumentException("The maximum retry delay must be at least as long as the initial delay.");
            }
        }
    }

    private static void validateJitter(@Nullable final Double jitter) {
        if (jitter == null) {
            return;
        }
        if (!Double.isFinite(jitter) || jitter < 0 || jitter >= 1) {
            throw new IllegalArgumentException("Retry jitter must be a finite fraction from zero to less than one; "
                    + "for example, 0.2 means up to 20% variation.");
        }
    }

    private static void validateResourceLimits(@Nullable final Integer waitingCapacity, @Nullable final Long maximumMessageSize,
            @Nullable final Long memoryLimit) {
        if (waitingCapacity != null && waitingCapacity <= 0) {
            throw new IllegalArgumentException("Retry waiting capacity must be positive. Use withWaitingCapacity(...) on the retry policy builder.");
        }
        if (maximumMessageSize != null && (maximumMessageSize <= 0 || maximumMessageSize > MAX_REPLAY_ARRAY_SIZE)) {
            throw new IllegalArgumentException("The maximum retry message size must be positive and fit a Java byte array. "
                    + "Choose a smaller value with withMaximumMessageSize(...) on the retry policy builder.");
        }
        if (memoryLimit != null && (memoryLimit <= 0 || (maximumMessageSize != null && memoryLimit < maximumMessageSize))) {
            throw new IllegalArgumentException("The retry memory limit must hold at least one maximum-size replay message. "
                    + "Increase withMemoryLimit(...), or lower withMaximumMessageSize(...) on the retry policy builder.");
        }
    }

    private static long positiveNanos(final Duration delay, final String description) {
        final long nanos;
        try {
            nanos = requireNonNull(delay, description).toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(description + " is too large to measure; choose a shorter duration.", overflow);
        }
        if (nanos <= 0) {
            throw new IllegalArgumentException(description + " must be positive.");
        }
        return nanos;
    }

    @NotNull
    public static Builder builder() { return new Builder(); }

    /**
     * True when this value contains both an attempt count and initial delay. Inspect the Mailer's operational configuration for the
     * effective state: an omitted schedule can still be inherited from captured properties.
     */
    public boolean isRetryEnabled() { return maximumAttempts != null && retryDelay != null; }

    /**
     * Returns the supplied attempt count, or null when omitted or explicitly cleared.
     * @see Builder#withMaximumAttempts(int)
     */
    @Nullable public Integer getMaximumAttempts() { return maximumAttempts; }

    /** @see Builder#clearMaximumAttempts() */
    public boolean isMaximumAttemptsCleared() { return maximumAttemptsCleared; }

    /**
     * Returns the supplied initial delay, or null when omitted or explicitly cleared.
     * @see Builder#withRetryDelay(Duration)
     */
    @Nullable public Duration getRetryDelay() { return retryDelay; }

    /** @see Builder#clearRetryDelay() */
    public boolean isRetryDelayCleared() { return retryDelayCleared; }

    /** @see Builder#withRetryBackoff(int, Duration) */
    @Nullable public Integer getRetryBackoffMultiplier() { return retryBackoffMultiplier; }

    /**
     * Returns the supplied cap, or null when backoff has no configured cap.
     * @see Builder#withRetryBackoff(int, Duration)
     */
    @Nullable public Duration getMaximumRetryDelay() { return maximumRetryDelay; }

    /** @see Builder#resetRetryBackoff() */
    public boolean isMaximumRetryDelayCleared() { return maximumRetryDelayCleared; }

    /** @see Builder#withRetryJitter(double) */
    @Nullable public Double getRetryJitter() { return retryJitter; }

    /** @see Builder#withWaitingCapacity(int) */
    @Nullable public Integer getWaitingCapacity() { return waitingCapacity; }

    /** @see Builder#withMaximumMessageSize(long) */
    @Nullable public Long getMaximumMessageSize() { return maximumMessageSize; }

    /** @see Builder#withMemoryLimit(long) */
    @Nullable public Long getMemoryLimit() { return memoryLimit; }

    private Object readResolve() {
        return new MailSendRetryPolicy(toBuilder());
    }

    /** Returns an independent builder retaining every stored choice, including resolved limits and caps. */
    @NotNull
    public Builder toBuilder() {
        return new Builder(this);
    }

    /** Supplied choices only; the Mailer resolves omitted choices from captured properties and the defaults on {@link MailerGenericBuilder}. */
    public static final class Builder {
        @Nullable private Integer maximumAttempts;
        private boolean maximumAttemptsCleared;
        @Nullable private Duration retryDelay;
        private boolean retryDelayCleared;
        @Nullable private Integer retryBackoffMultiplier;
        @Nullable private Duration maximumRetryDelay;
        private boolean maximumRetryDelayCleared;
        @Nullable private Double retryJitter;
        @Nullable private Integer waitingCapacity;
        @Nullable private Long maximumMessageSize;
        @Nullable private Long memoryLimit;

        private Builder() { }

        private Builder(final MailSendRetryPolicy policy) {
            maximumAttempts = policy.maximumAttempts;
            maximumAttemptsCleared = policy.maximumAttemptsCleared;
            retryDelay = policy.retryDelay;
            retryDelayCleared = policy.retryDelayCleared;
            retryBackoffMultiplier = policy.retryBackoffMultiplier;
            maximumRetryDelay = policy.maximumRetryDelay;
            maximumRetryDelayCleared = policy.maximumRetryDelayCleared;
            retryJitter = policy.retryJitter;
            waitingCapacity = policy.waitingCapacity;
            maximumMessageSize = policy.maximumMessageSize;
            memoryLimit = policy.memoryLimit;
        }

        /**
         * Limits provider invocations for one email, including the first. For example, three allows at most two retries.
         * A retry delay must also be supplied in Java or captured properties to enable retries; at least two.
         * Connection setup attempts are governed separately by failover configuration.
         * {@link #clearMaximumAttempts()} removes only the count; use {@link #clearRetrySchedule()} to disable the complete schedule.
         * @param attempts Maximum total provider invocations for one logical email send.
         */
        @NotNull
        public Builder withMaximumAttempts(final int attempts) { maximumAttempts = attempts; maximumAttemptsCleared = false; return this; }

        /**
         * Explicitly removes only the attempt count, including a captured value. A remaining delay or schedule modifier still requires
         * a complete schedule when building the Mailer.
         * @see #withMaximumAttempts(int)
         */
        @NotNull
        public Builder clearMaximumAttempts() {
            maximumAttempts = null;
            maximumAttemptsCleared = true;
            return this;
        }

        /**
         * Waits this long after a refused attempt has finished cleanup before trying again. An attempt count must also be supplied in
         * Java or captured properties; positive.
         * Pick a delay appropriate for the SMTP service rather than assuming all services recover on the same schedule.
         * {@link #clearRetryDelay()} removes only the delay; use {@link #clearRetrySchedule()} to disable the complete schedule.
         * @param delay Initial retry delay; for example, thirty seconds.
         */
        @NotNull
        public Builder withRetryDelay(@NotNull final Duration delay) {
            retryDelay = requireNonNull(delay, "retryDelay"); retryDelayCleared = false; return this;
        }

        /**
         * Explicitly removes only the initial delay, including a captured value. A remaining attempt count or schedule modifier still
         * requires a complete schedule when building the Mailer.
         * @see #withRetryDelay(Duration)
         */
        @NotNull
        public Builder clearRetryDelay() {
            retryDelay = null;
            retryDelayCleared = true;
            return this;
        }

        /**
         * Disables retries by removing the attempt count, delay, backoff and jitter together, including captured schedule values.
         * Waiting and replay-resource choices remain unchanged, including inheritance for omitted limits.
         * Unlike clearing one required field, this leaves a complete, disabled policy ready to build. Previously built policies are not changed.
         *
         * @see #withMaximumAttempts(int)
         * @see #withRetryDelay(Duration)
         */
        @NotNull
        public Builder clearRetrySchedule() {
            clearMaximumAttempts();
            clearRetryDelay();
            resetRetryBackoff();
            resetRetryJitter();
            return this;
        }

        /**
         * Increases the base delay after each refused attempt, capped at the supplied maximum. Disabled by default.
         * A multiplier of two with a thirty-second initial delay gives thirty, sixty, then 120 seconds before jitter and the cap.
         * @param multiplier Integer greater than one.
         * @param maximumDelay Positive cap at least as long as the initial delay; jitter cannot exceed it.
         */
        @NotNull
        public Builder withRetryBackoff(final int multiplier, @NotNull final Duration maximumDelay) {
            if (multiplier <= 1) {
                throw new IllegalArgumentException("Choose a retry backoff multiplier greater than one, or use resetRetryBackoff() for fixed delays.");
            }
            retryBackoffMultiplier = multiplier;
            maximumRetryDelay = requireNonNull(maximumDelay, "maximumDelay");
            maximumRetryDelayCleared = false;
            return this;
        }

        /** @see #withRetryBackoff(int, Duration) */
        @NotNull
        public Builder resetRetryBackoff() {
            retryBackoffMultiplier = DEFAULT_MAIL_SEND_RETRY_BACKOFF_MULTIPLIER;
            maximumRetryDelay = null;
            maximumRetryDelayCleared = true;
            return this;
        }

        /**
         * Spreads retries by varying each base delay uniformly by up to this fraction in either direction, within any cap.
         * For example, 0.2 varies thirty seconds between 24 and 36 seconds.
         * Defaults to {@link MailerGenericBuilder#DEFAULT_MAIL_SEND_RETRY_JITTER no variation}; finite and less than one.
         * @param fraction Non-negative fractional variation; zero disables it.
         */
        @NotNull
        public Builder withRetryJitter(final double fraction) { retryJitter = fraction; return this; }

        /** @see #withRetryJitter(double) */
        @NotNull
        public Builder resetRetryJitter() { return withRetryJitter(DEFAULT_MAIL_SEND_RETRY_JITTER); }

        /**
         * Bounds ordinary async sends suspended for retry backoff or awaiting resumed execution. The initiating Mailer owns this limit,
         * including when another Mailer's retry-enabled SMTP destination is selected. When full, the send ends with its genuine submission
         * failure and an explanation that another retry could not be scheduled. Does not enable retries or bound initial executor admission,
         * active invocations, or synchronous/retained-scope waits.
         * Defaults to {@link MailerGenericBuilder#DEFAULT_MAIL_SEND_RETRY_WAITING_CAPACITY 100 suspended retries}.
         * @param capacity Positive maximum number of suspended retries.
         */
        @NotNull
        public Builder withWaitingCapacity(final int capacity) { waitingCapacity = capacity; return this; }

        /** @see #withWaitingCapacity(int) */
        @NotNull
        public Builder resetWaitingCapacity() { return withWaitingCapacity(DEFAULT_MAIL_SEND_RETRY_WAITING_CAPACITY); }

        /**
         * Bounds one serialized message retained for safe retries, including MIME headers and encoded attachments. A 20 MiB attachment
         * grows to roughly 27 MiB in base64; use the serialized size rather than the file length. Capture fails before submission if this
         * limit is exceeded. Increase it for larger retry-enabled messages, or select a destination without an enabled retry schedule.
         * This is the initiating Mailer's resource limit, not the SMTP server's SIZE limit; it does not enable retries.
         * Defaults to {@link MailerGenericBuilder#DEFAULT_MAIL_SEND_RETRY_MAXIMUM_MESSAGE_SIZE 32 MiB}.
         * @param bytes Positive maximum replay size that fits a Java byte array.
         */
        @NotNull
        public Builder withMaximumMessageSize(final long bytes) { maximumMessageSize = bytes; return this; }

        /** @see #withMaximumMessageSize(long) */
        @NotNull
        public Builder resetMaximumMessageSize() { return withMaximumMessageSize(DEFAULT_MAIL_SEND_RETRY_MAXIMUM_MESSAGE_SIZE); }

        /**
         * Bounds aggregate replay-content bytes across active and waiting retry-enabled sends initiated by this Mailer. Exhaustion fails
         * capture before submission. Does not enable retries, write temporary files, or promise an exact heap bound: original Emails,
         * protection and temporary allocations can use additional memory.
         * Defaults to {@link MailerGenericBuilder#DEFAULT_MAIL_SEND_RETRY_MEMORY_LIMIT 128 MiB}.
         * @param bytes Positive aggregate budget at least as large as the maximum replay-message size.
         */
        @NotNull
        public Builder withMemoryLimit(final long bytes) { memoryLimit = bytes; return this; }

        /** @see #withMemoryLimit(long) */
        @NotNull
        public Builder resetMemoryLimit() { return withMemoryLimit(DEFAULT_MAIL_SEND_RETRY_MEMORY_LIMIT); }

        /** @see #withMaximumAttempts(int) */
        @Nullable
        public Integer getMaximumAttempts() {
            return maximumAttempts;
        }

        /** @see #withRetryDelay(Duration) */
        @Nullable
        public Duration getRetryDelay() {
            return retryDelay;
        }

        /** @see #withRetryBackoff(int, Duration) */
        @Nullable public Integer getRetryBackoffMultiplier() {
            return retryBackoffMultiplier;
        }

        /** @see #withRetryBackoff(int, Duration) */
        @Nullable
        public Duration getMaximumRetryDelay() {
            return maximumRetryDelay;
        }

        /** @see #withRetryJitter(double) */
        @Nullable public Double getRetryJitter() {
            return retryJitter;
        }

        /** @see #withWaitingCapacity(int) */
        @Nullable public Integer getWaitingCapacity() {
            return waitingCapacity;
        }

        /** @see #withMaximumMessageSize(long) */
        @Nullable public Long getMaximumMessageSize() {
            return maximumMessageSize;
        }

        /** @see #withMemoryLimit(long) */
        @Nullable public Long getMemoryLimit() {
            return memoryLimit;
        }

        /**
         * Captures supplied choices. The Mailer validates the complete schedule and resource relationships after inheritance.
         * @throws IllegalArgumentException If supplied values are invalid or conflict with each other.
         */
        @NotNull
        public MailSendRetryPolicy build() {
            return new MailSendRetryPolicy(this);
        }
    }
}
