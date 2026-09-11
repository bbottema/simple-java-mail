package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.mailer.AsyncQueueRejectionReason;
import org.simplejavamail.api.mailer.AsyncQueueSnapshot;
import org.simplejavamail.api.mailer.MailSendRejectedException;
import org.simplejavamail.api.mailer.config.AsyncQueueConfig;
import org.simplejavamail.api.mailer.config.AsyncQueueOverflowPolicy;

import java.util.EnumMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

/** Owns the Mailer's queue boundary; waiting for admission never executes SMTP work on the submitting thread. */
final class MailSendExecutor extends ThreadPoolExecutor {
    private static final AtomicInteger EXECUTOR_SEQUENCE = new AtomicInteger();
    private static final ThreadLocal<MailSendExecutor> CURRENT_WORKER = new ThreadLocal<>();
    private final AsyncQueueConfig configuration;
    private final AtomicLongArray rejections = new AtomicLongArray(AsyncQueueRejectionReason.values().length);

    MailSendExecutor(final int workerLimit, final int keepAliveMillis, final AsyncQueueConfig configuration) {
        super(workerLimit, workerLimit, keepAliveMillis, TimeUnit.MILLISECONDS,
                createQueue(configuration.getCapacity()), namedThreads());
        this.configuration = configuration;
        setRejectedExecutionHandler((operation, executor) -> waitForCapacityOrReject(operation));
        if (keepAliveMillis > 0) {
            allowCoreThreadTimeOut(true);
        }
    }

    AsyncQueueSnapshot snapshot() {
        final EnumMap<AsyncQueueRejectionReason, Long> counts = new EnumMap<>(AsyncQueueRejectionReason.class);
        for (AsyncQueueRejectionReason reason : AsyncQueueRejectionReason.values()) {
            counts.put(reason, rejections.get(reason.ordinal()));
        }
        return new AsyncQueueSnapshot(configuration, getMaximumPoolSize(), getQueue().size(), getActiveCount(), isShutdown(), counts);
    }

    static boolean isCurrentWorker(final ExecutorService executor) {
        return CURRENT_WORKER.get() == executor;
    }

    @Override
    protected void beforeExecute(final Thread thread, final Runnable operation) {
        CURRENT_WORKER.set(this);
    }

    @Override
    protected void afterExecute(final Runnable operation, final Throwable failure) {
        CURRENT_WORKER.remove();
    }

    private void waitForCapacityOrReject(final Runnable operation) {
        if (isShutdown()) {
            throw reject(AsyncQueueRejectionReason.EXECUTOR_SHUT_DOWN);
        }
        if (configuration.getOverflowPolicy() == AsyncQueueOverflowPolicy.REJECT) {
            throw reject(AsyncQueueRejectionReason.QUEUE_FULL);
        }
        try {
            enqueueBeforeTimeout(operation);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw reject(AsyncQueueRejectionReason.CAPACITY_WAIT_INTERRUPTED);
        }
    }

    private void enqueueBeforeTimeout(final Runnable operation) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(configuration.getWaitTimeoutMillis());
        long remaining = deadline - System.nanoTime();
        while (remaining > 0 && !isShutdown()) {
            if (operation instanceof MailSendOperation) {
                ((MailSendOperation<?>) operation).control().checkStopped();
                remaining = Math.min(remaining, ((MailSendOperation<?>) operation).control().remainingNanos());
            }
            // Direct handoff needs a consumer; workers can expire after rejection or between admission polls.
            prestartCoreThread();
            // Periodically observe shutdown as well as capacity: queue producers otherwise outlive an executor that is closing.
            if (getQueue().offer(operation, Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS)) {
                if (isShutdown() && remove(operation)) {
                    throw reject(AsyncQueueRejectionReason.EXECUTOR_SHUT_DOWN);
                }
                // A buffered offer can still race with worker expiry after the prestart above.
                prestartCoreThread();
                return;
            }
            remaining = deadline - System.nanoTime();
        }
        if (operation instanceof MailSendOperation) {
            ((MailSendOperation<?>) operation).control().checkStopped();
        }
        throw reject(isShutdown() ? AsyncQueueRejectionReason.EXECUTOR_SHUT_DOWN : AsyncQueueRejectionReason.CAPACITY_WAIT_TIMED_OUT);
    }

    private MailSendRejectedException reject(final AsyncQueueRejectionReason reason) {
        rejections.incrementAndGet(reason.ordinal());
        return new MailSendRejectedException(reason);
    }

    MailSendRejectedException rejectAfterShutdown() {
        return reject(AsyncQueueRejectionReason.EXECUTOR_SHUT_DOWN);
    }

    private static BlockingQueue<Runnable> createQueue(final int capacity) {
        if (capacity == -1) {
            return new LinkedBlockingQueue<>();
        }
        return capacity == 0 ? new SynchronousQueue<>(true) : new ArrayBlockingQueue<>(capacity, true);
    }

    private static ThreadFactory namedThreads() {
        final int executorNumber = EXECUTOR_SEQUENCE.incrementAndGet();
        final AtomicInteger threadSequence = new AtomicInteger();
        final ThreadFactory threads = Executors.defaultThreadFactory();
        return operation -> {
            final Thread thread = threads.newThread(operation);
            thread.setName("Simple Java Mail async mail sender, executor " + executorNumber + " / thread " + threadSequence.incrementAndGet());
            return thread;
        };
    }
}
