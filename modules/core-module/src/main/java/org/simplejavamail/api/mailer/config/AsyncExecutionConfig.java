package org.simplejavamail.api.mailer.config;

import lombok.Value;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.internal.util.DurationMillis;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import static java.util.Objects.requireNonNull;

/**
 * Immutable supplied AsyncExecutionConfig choices. Unset worker and queue choices inherit the owning Mailer factory snapshot.
 * An executor registration is Java-only; without one, the Mailer owns its executor.
 * Construction and inspection never start workers, connect, resolve configuration sources or own borrowed resources.
 * Replacing this group on a Mailer discards its previous supplied choices.
 */
@Value
public class AsyncExecutionConfig {
    @Nullable Integer threadPoolSize;
    @Nullable Duration keepAliveTime;
    @Nullable Integer queueCapacity;
    @Nullable @ToString.Exclude ExecutorService executorService;
    boolean executorServiceCleared;
    @Nullable Boolean rejectWhenFull;
    @Nullable Duration capacityWait;

    private AsyncExecutionConfig(final Builder builder) {
        threadPoolSize = builder.threadPoolSize;
        keepAliveTime = builder.keepAliveTime;
        DurationMillis.toNullableInt(keepAliveTime, "keepAliveTime");
        queueCapacity = builder.queueCapacity;
        executorService = builder.executorService;
        executorServiceCleared = builder.executorServiceCleared;
        rejectWhenFull = builder.rejectWhenFull;
        capacityWait = builder.capacityWait;
    }

    /** @return A builder containing no supplied choices. */
    @NotNull public static Builder builder() { return new Builder(); }
    /** @return An independent builder retaining supplied choices and borrowed-resource identities. */
    @NotNull public Builder toBuilder() { return new Builder(this); }
    /** @see Builder#withThreadPoolSize(Integer) */
    @Nullable public Integer getThreadPoolSize() { return threadPoolSize; }
    /** @see Builder#withKeepAliveTime(Duration) */
    @Nullable public Duration getKeepAliveTime() { return keepAliveTime; }
    /** @see Builder#withQueueCapacity(int) */
    @Nullable public Integer getQueueCapacity() { return queueCapacity; }
    /**
     * @return The supplied, borrowed executor itself, or {@code null} when no executor is supplied; inspection never creates an executor.
     * @see Builder#withExecutorService(ExecutorService)
     */
    @Nullable public ExecutorService getExecutorService() { return executorService; }
    /**
     * Whether this group explicitly cleared its Java-only executor registration.
     * @see Builder#clearExecutorService()
     */
    public boolean isExecutorServiceCleared() { return executorServiceCleared; }
    /**
     * True for immediate rejection, false for a bounded wait, null for captured configuration.
     * @see Builder#rejectWhenFull()
     */
    @Nullable public Boolean getRejectWhenFull() { return rejectWhenFull; }
    /**
     * Supplied admission wait, ignored for immediate rejection; null inherits the captured wait.
     * @see Builder#waitForCapacity(Duration)
     */
    @Nullable public Duration getCapacityWait() { return capacityWait; }

    /** Explicit choices for this group; inspection does not build or validate an incomplete configuration. */
    public static final class Builder {
        private Integer threadPoolSize;
        private Duration keepAliveTime;
        private Integer queueCapacity;
        private ExecutorService executorService;
        private boolean executorServiceCleared;
        private Boolean rejectWhenFull;
        private Duration capacityWait;
        private Builder() { }
        private Builder(final AsyncExecutionConfig config) {
            threadPoolSize = config.threadPoolSize;
            keepAliveTime = config.keepAliveTime;
            queueCapacity = config.queueCapacity;
            executorService = config.executorService;
            executorServiceCleared = config.executorServiceCleared;
            rejectWhenFull = config.rejectWhenFull;
            capacityWait = config.capacityWait;
        }
        /**
         * Sets the built-in executor's worker count, independently of the batch module. Explicit Java and property values are honored.
         * When unspecified, the count is one without the batch module or {@value org.simplejavamail.api.mailer.MailerGenericBuilder#DEFAULT_POOL_SIZE} with it.
         *
         * @param threadPoolSize See main description.
         *
         * @see #resetThreadPoolSize()
         * @see #withThreadPoolSize(Integer)
         */
        @NotNull public Builder withThreadPoolSize(final Integer threadPoolSize) { this.threadPoolSize = requireNonNull(threadPoolSize, "threadPoolSize"); return this; }
        /** @see #withThreadPoolSize(Integer) */
        @Nullable public Integer getThreadPoolSize() { return threadPoolSize; }
        /**
         * Selects the published worker-count fallback, including when the batch module is absent.
         * @see #withThreadPoolSize(Integer)
         */
        @NotNull public Builder resetThreadPoolSize() { return withThreadPoolSize(MailerGenericBuilder.DEFAULT_POOL_SIZE); }
        /**
         * Restores captured configuration for this member.
         * @see #withThreadPoolSize(Integer)
         */
        @NotNull public Builder useConfiguredThreadPoolSize() { threadPoolSize = null; return this; }
        /**
         * Sets how long idle built-in executor workers remain alive. A positive duration permits core workers to time out,
         * allowing the JVM to exit after their work finishes. Zero keeps core workers alive until the Mailer is closed.
         * Explicit Java and property values are honored independently of the batch module and of the worker-count choice.
         * When unspecified, keep-alive is zero without the batch module or {@link org.simplejavamail.api.mailer.MailerGenericBuilder#DEFAULT_POOL_KEEP_ALIVE_TIME} with it.
         *
         * @param keepAliveTime Nonnegative duration, exactly representable as integer milliseconds.
         * @throws IllegalArgumentException If the duration has fractional milliseconds or exceeds the integer-millisecond range.
         *
         * @see #resetKeepAliveTime()
         */
        @NotNull public Builder withKeepAliveTime(final Duration keepAliveTime) { DurationMillis.toNullableInt(keepAliveTime, "keepAliveTime"); this.keepAliveTime = requireNonNull(keepAliveTime, "keepAliveTime"); return this; }
        /** @see #withKeepAliveTime(Duration) */
        @Nullable public Duration getKeepAliveTime() { return keepAliveTime; }
        /**
         * Selects the published worker keep-alive fallback, including when the batch module is absent.
         * @see #withKeepAliveTime(Duration)
         */
        @NotNull public Builder resetKeepAliveTime() { return withKeepAliveTime(MailerGenericBuilder.DEFAULT_POOL_KEEP_ALIVE_TIME); }
        /**
         * Restores captured configuration for this member.
         * @see #withKeepAliveTime(Duration)
         */
        @NotNull public Builder useConfiguredKeepAliveTime() { keepAliveTime = null; return this; }
        /**
         * Bounds pending operations in this Mailer's built-in executor, separately from running workers and SMTP connections.
         * The default, {@code -1}, keeps the queue unbounded; {@code 0} allows work only when a worker can accept it immediately.
         * A positive value allows that many waiting operations. Queued operations are FIFO, but concurrent completion order is not guaranteed.
         * <p>
         * One ordinary send, receipt-returning send, simple batch, or asynchronous connection test occupies one place. A simple batch remains one lazy,
         * sequential operation, regardless of its email count. Blocking sends and the standalone BatchTransportExecutor do not use this queue.
         * Destination selection and ordinary email preparation run after admission, on executor execution. This bounds waiting sends, not message size
         * or application memory. A direct/caller-runs executor can still perform the work on the calling thread.
         * <p>
         * Saturation uses the immediate-rejection or bounded-wait policy; immediate rejection is the default.
         * Choose {@link #rejectWhenFull()} or {@link #waitForCapacity(Duration)}. These policies matter only with a finite queue.
         * Rejected operations acquire no SMTP or proxy resources.
         * Queue settings cannot be combined with {@link #withExecutorService(ExecutorService)}: configure that caller-owned executor itself instead.
         *
         * @param queueCapacity Maximum queued operations, or -1 to retain unbounded queuing; values below -1 are invalid.
         * @see #useConfiguredCapacityPolicy()
         * @see Mailer#getAsyncQueueSnapshot()
         */
        @NotNull public Builder withQueueCapacity(final int queueCapacity) {
            if (queueCapacity < -1) {
                throw new IllegalArgumentException("Async queue capacity must be -1 for an unbounded queue, zero for direct handoff, or a positive limit.");
            }
            this.queueCapacity = requireNonNull(queueCapacity, "queueCapacity");
            return this;
        }
        /** @see #withQueueCapacity(int) */
        @Nullable public Integer getQueueCapacity() { return queueCapacity; }
        /**
         * Selects the published library fallback.
         * @see #withQueueCapacity(int)
         */
        @NotNull public Builder resetQueueCapacity() { return withQueueCapacity(MailerGenericBuilder.DEFAULT_ASYNC_QUEUE_CAPACITY); }
        /**
         * Restores captured configuration for this member.
         * @see #withQueueCapacity(int)
         */
        @NotNull public Builder useConfiguredQueueCapacity() { queueCapacity = null; return this; }
        /**
         * <strong>For advanced use cases.</strong>
         * <p>
         * Sets the executor service used for asynchronous operations, including individual sends and simple batches. This lets the caller manage the thread
         * pool, threads and concurrency characteristics directly.
         * <p>
         * Without a caller-provided executor, the built-in executor honors explicit worker count and keep-alive choices, whether supplied through
         * Java or properties, with or without the batch module. Unspecified worker count defaults to one without the batch module or
         * {@value org.simplejavamail.api.mailer.MailerGenericBuilder#DEFAULT_POOL_SIZE} with it. Unspecified keep-alive defaults to zero without the batch module or
         * {@link org.simplejavamail.api.mailer.MailerGenericBuilder#DEFAULT_POOL_KEEP_ALIVE_TIME} with it. A positive keep-alive lets core workers stop once idle.
         * The executor uses named non-daemon workers and an unbounded queue unless configured through {@link #withQueueCapacity(int)}.
         * <p>
         * Simple Java Mail will <strong>not</strong> shut down the provided executor service when the Mailer or its connection pool is closed. The caller remains
         * responsible for the executor's lifecycle. Non-default built-in queue settings cannot be combined with a caller-owned executor and are rejected
         * when building the Mailer. Call {@link #resetQueue()} to discard property-backed queue settings when taking over executor configuration.
         *
         * @param executorService A caller-owned executor service replacing Simple Java Mail's default executor.
         * @see <a href="https://www.simplejavamail.org/configuration.html#section-mailer-lifecycle">Mailer lifecycle and resource ownership</a>
         */
        @NotNull public Builder withExecutorService(@NotNull final ExecutorService executorService) { this.executorService = requireNonNull(executorService, "executorService"); executorServiceCleared = false; return this; }
        /**
         * @return The supplied, borrowed executor itself, or {@code null} when none is supplied; no default executor is created by inspection.
         * @see #withExecutorService(ExecutorService)
         */
        @Nullable public ExecutorService getExecutorService() { return executorService; }
        /**
         * Removes the supplied executor, restoring Mailer-owned execution with the current worker and queue settings.
         * Explicit worker choices are honored with or without the batch module. Queue settings are not reset, and a borrowed executor is never shut down.
         * @see #withExecutorService(ExecutorService)
         */
        @NotNull public Builder clearExecutorService() { executorService = null; executorServiceCleared = true; return this; }
        /**
         * Removes the supplied Java executor registration so the Mailer owns its executor; current worker and queue choices remain.
         * There is no property-backed executor registration to restore, and a borrowed executor is never shut down.
         * @see #withExecutorService(ExecutorService)
         */
        @NotNull public Builder useConfiguredExecutorService() { executorService = null; executorServiceCleared = false; return this; }
        /**
         * Rejects a new asynchronous submission immediately when all workers and waiting slots are occupied.
         * This is the default policy and matters only with a finite queue. No SMTP work runs on the submitting thread.
         * The returned async send completes exceptionally with {@link org.simplejavamail.api.mailer.MailSendRejectedException};
         * its observer runs on the submitting thread before submission returns. A rejected simple batch never opens its iterable or notifies per email.
         * Removes any supplied admission wait; no SMTP or proxy resources are acquired for rejected work.
         * @see #waitForCapacity(Duration)
         */
        @NotNull public Builder rejectWhenFull() { rejectWhenFull = true; capacityWait = null; return this; }
        /**
         * Waits for queue capacity on the submitting thread, up to the supplied positive limit.
         * This policy matters only with a finite queue. The admission wait is not a socket timeout; it is included in any total mail-send deadline,
         * rather than extending that deadline. It does not run SMTP work on this thread.
         * Avoid waiting on event-loop threads. Waiting from a Mailer worker, for example inside an observer, also occupies that worker until admission
         * succeeds or times out. Interruption rejects admission and preserves the interrupt flag. Rejected sends and batches follow
         * {@link #rejectWhenFull()}'s completion and observation contract.
         * Configure a caller-owned executor directly instead of combining it with built-in queue choices.
         * The captured property-backed wait defaults to {@link MailerGenericBuilder#DEFAULT_ASYNC_QUEUE_WAIT_TIMEOUT} (one second);
         * this method always supplies an explicit limit.
         * @param wait Positive whole-millisecond duration within the integer-millisecond range.
         * @throws IllegalArgumentException If the duration is not positive or cannot be represented exactly.
         * @see #rejectWhenFull()
         */
        @NotNull public Builder waitForCapacity(@NotNull final Duration wait) {
            if (DurationMillis.toInt(wait, "capacityWait") <= 0) {
                throw new IllegalArgumentException("Async queue wait timeout must be positive. Supply a duration such as Duration.ofSeconds(1).");
            }
            rejectWhenFull = false; capacityWait = wait; return this;
        }
        /**
         * Restores the captured admission policy and wait.
         * @see #waitForCapacity(Duration)
         */
        @NotNull public Builder useConfiguredCapacityPolicy() { rejectWhenFull = null; capacityWait = null; return this; }
        /**
         * Selects the published queue defaults: unbounded queuing, immediate rejection and a one-second admission wait (ignored for rejection).
         * Replaces captured capacity, policy and admission-wait choices.
         * This permits handing execution to a caller-owned executor when the factory configures a built-in queue.
         * @see #withQueueCapacity(int)
         * @see #rejectWhenFull()
         */
        @NotNull public Builder resetQueue() {
            queueCapacity = MailerGenericBuilder.DEFAULT_ASYNC_QUEUE_CAPACITY;
            rejectWhenFull = MailerGenericBuilder.DEFAULT_ASYNC_QUEUE_OVERFLOW_POLICY == AsyncQueueOverflowPolicy.REJECT;
            capacityWait = MailerGenericBuilder.DEFAULT_ASYNC_QUEUE_WAIT_TIMEOUT;
            return this;
        }
        /**
         * True for rejection, false for waiting, null for inheritance.
         * @see #rejectWhenFull()
         */
        @Nullable public Boolean getRejectWhenFull() { return rejectWhenFull; }
        /**
         * Supplied admission wait, ignored when rejection is selected.
         * @see #waitForCapacity(Duration)
         */
        @Nullable public Duration getCapacityWait() { return capacityWait; }
        /** Describes choices while hiding application-owned executor details. */
        @Override public String toString() { return "AsyncExecutionConfig.Builder"; }
        /** @return An immutable snapshot; feature validation remains with its owning runtime. */
        @NotNull public AsyncExecutionConfig build() { return new AsyncExecutionConfig(this); }
    }
}
