package org.simplejavamail.api.mailer.config;

import lombok.Value;
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
    int waitTimeoutMillis;

    /**
     * @param capacity Maximum waiting tasks, excluding active workers; -1 keeps an unbounded queue and zero permits only direct handoff.
     * @param overflowPolicy Whether a saturated executor rejects immediately or waits for a slot on the submitting thread.
     * @param waitTimeoutMillis Positive admission-wait limit, not a total mail-send timeout; ignored by the rejection policy.
     * @throws IllegalArgumentException If the capacity is below -1 or the wait timeout is not positive.
     */
    public AsyncQueueConfig(final int capacity, @NotNull final AsyncQueueOverflowPolicy overflowPolicy, final int waitTimeoutMillis) {
        if (capacity < -1) {
            throw new IllegalArgumentException("Async queue capacity must be -1 (unbounded), zero, or positive");
        }
        if (waitTimeoutMillis <= 0) {
            throw new IllegalArgumentException("Async queue wait timeout must be positive");
        }
        this.capacity = capacity;
        this.overflowPolicy = requireNonNull(overflowPolicy, "overflowPolicy");
        this.waitTimeoutMillis = waitTimeoutMillis;
    }

    /**
     * <strong>Builder Default:</strong> {@value MailerGenericBuilder#DEFAULT_ASYNC_QUEUE_CAPACITY} (unbounded).
     *
     * @see MailerGenericBuilder#withAsyncQueueCapacity(int)
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * <strong>Builder Default:</strong> {@link MailerGenericBuilder#DEFAULT_ASYNC_QUEUE_OVERFLOW_POLICY} (immediate rejection).
     *
     * @see MailerGenericBuilder#withAsyncQueueOverflowPolicy(AsyncQueueOverflowPolicy)
     */
    @NotNull
    public AsyncQueueOverflowPolicy getOverflowPolicy() {
        return overflowPolicy;
    }

    /**
     * <strong>Builder Default:</strong> {@value MailerGenericBuilder#DEFAULT_ASYNC_QUEUE_WAIT_TIMEOUT_MILLIS} milliseconds (one second).
     *
     * @see MailerGenericBuilder#withAsyncQueueWaitTimeoutMillis(int)
     */
    public int getWaitTimeoutMillis() {
        return waitTimeoutMillis;
    }
}
