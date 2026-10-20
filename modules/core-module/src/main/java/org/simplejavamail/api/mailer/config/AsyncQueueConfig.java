package org.simplejavamail.api.mailer.config;

import java.time.Duration;

import lombok.Value;
import org.simplejavamail.internal.util.DurationMillis;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.mailer.MailerGenericBuilder;

import static java.util.Objects.requireNonNull;

/**
 * Immutable queue settings for a Mailer's built-in executor; these do not configure a connection pool or caller-owned executor.
 * Values are supplied at construction; {@link MailerGenericBuilder} resolves defaults and overrides.
 */
@Value
public class AsyncQueueConfig {
    int capacity;
    @NotNull AsyncQueueOverflowPolicy overflowPolicy;
    @NotNull Duration waitTimeout;

    /**
     * @param capacity Maximum waiting tasks, excluding active workers; -1 keeps an unbounded queue and zero permits only direct handoff.
     * @param overflowPolicy Whether a saturated executor rejects immediately or waits for a slot on the submitting thread.
     * @param waitTimeout Positive admission-wait limit, not a total mail-send timeout; ignored by the rejection policy.
     * @throws IllegalArgumentException If the capacity is below -1 or the wait timeout is not positive, has fractional milliseconds or exceeds the integer-millisecond range.
     */
    public AsyncQueueConfig(final int capacity, @NotNull final AsyncQueueOverflowPolicy overflowPolicy, @NotNull final Duration waitTimeout) {
        if (capacity < -1) {
            throw new IllegalArgumentException("Async queue capacity must be -1 (unbounded), zero, or positive");
        }
        if (DurationMillis.toInt(waitTimeout, "asyncQueueWaitTimeout") <= 0) {
            throw new IllegalArgumentException("Async queue wait timeout must be positive");
        }
        this.capacity = capacity;
        this.overflowPolicy = requireNonNull(overflowPolicy, "overflowPolicy");
        this.waitTimeout = waitTimeout;
    }

    /**
     * <strong>Builder Default:</strong> {@value MailerGenericBuilder#DEFAULT_ASYNC_QUEUE_CAPACITY} (unbounded).
     *
     * @see org.simplejavamail.api.mailer.config.AsyncExecutionConfig.Builder#withQueueCapacity(int)
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * <strong>Builder Default:</strong> {@link MailerGenericBuilder#DEFAULT_ASYNC_QUEUE_OVERFLOW_POLICY} (immediate rejection).
     *
     * @see org.simplejavamail.api.mailer.config.AsyncExecutionConfig.Builder#rejectWhenFull()
     */
    @NotNull
    public AsyncQueueOverflowPolicy getOverflowPolicy() {
        return overflowPolicy;
    }

    /**
     * <strong>Builder Default:</strong> {@link MailerGenericBuilder#DEFAULT_ASYNC_QUEUE_WAIT_TIMEOUT} (one second).
     *
     * @see org.simplejavamail.api.mailer.config.AsyncExecutionConfig.Builder#waitForCapacity(java.time.Duration)
     */
    @NotNull
    public Duration getWaitTimeout() {
        return waitTimeout;
    }
}
