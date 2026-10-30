package org.simplejavamail.internal.clisupport.daemon;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** One monotonic stop budget; only the explicit standalone entry point grants process-termination authority. */
final class DaemonShutdown {
    static final Duration APPLICATION_GRACE = Duration.ofSeconds(30);
    static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(60);
    private enum ExitOwner { AUTHENTICATED_STOP, JVM }

    private final long applicationGraceNanos;
    private final long totalTimeoutNanos;
    private final LongSupplier nanoTime;
    private final Consumer<Runnable> startWatchdog;
    private final Runnable terminateProcess;
    private final AtomicReference<Long> stopStartedAtNanos = new AtomicReference<>();
    private final AtomicReference<ExitOwner> exitOwner = new AtomicReference<>();

    private DaemonShutdown(final Duration applicationGrace, final Duration totalTimeout,
            final LongSupplier nanoTime, final Consumer<Runnable> startWatchdog, final Runnable terminateProcess) {
        applicationGraceNanos = applicationGrace.toNanos();
        totalTimeoutNanos = totalTimeout.toNanos();
        if (applicationGraceNanos <= 0 || totalTimeoutNanos < applicationGraceNanos) {
            throw new IllegalArgumentException("Invalid daemon shutdown budgets");
        }
        this.nanoTime = nanoTime;
        this.startWatchdog = startWatchdog;
        this.terminateProcess = terminateProcess;
    }

    static DaemonShutdown embedded() {
        return new DaemonShutdown(APPLICATION_GRACE, TOTAL_TIMEOUT, System::nanoTime, null, null);
    }

    static DaemonShutdown standalone() {
        return standalone(APPLICATION_GRACE, TOTAL_TIMEOUT);
    }

    /** Package-private timing seam used only by child-process fixtures. */
    static DaemonShutdown standalone(final Duration applicationGrace, final Duration totalTimeout) {
        return new DaemonShutdown(applicationGrace, totalTimeout, System::nanoTime, DaemonShutdown::startDedicatedWatchdog,
                () -> Runtime.getRuntime().halt(0));
    }

    /** Tests capture the watchdog rather than allowing their hosting JVM to terminate. */
    static DaemonShutdown observing(final Duration applicationGrace, final Duration totalTimeout,
            final LongSupplier nanoTime, final Consumer<Runnable> startWatchdog, final Runnable terminateProcess) {
        return new DaemonShutdown(applicationGrace, totalTimeout, nanoTime, startWatchdog, terminateProcess);
    }

    void begin() {
        stopStartedAtNanos.compareAndSet(null, nanoTime.getAsLong());
    }

    void beginAuthenticatedStop() {
        final boolean ownsExit = startWatchdog != null && exitOwner.compareAndSet(null, ExitOwner.AUTHENTICATED_STOP);
        begin();
        if (ownsExit) {
            startWatchdog.accept(this::awaitDeadlineAndTerminate);
        }
    }

    void enterJvmShutdown() {
        // First origin wins: a signal keeps its status, but cannot revoke a previously accepted stop's watchdog.
        exitOwner.compareAndSet(null, ExitOwner.JVM);
        begin();
    }

    long applicationRemainingNanos() {
        return remainingNanos(applicationGraceNanos);
    }

    long remainingNanos() {
        return remainingNanos(totalTimeoutNanos);
    }

    private long remainingNanos(final long budget) {
        final Long start = stopStartedAtNanos.get();
        return start == null ? budget : Math.max(0, budget - (nanoTime.getAsLong() - start));
    }

    void awaitStopped(final CountDownLatch stopped) throws InterruptedException {
        stopped.await(remainingNanos(), TimeUnit.NANOSECONDS);
    }

    void awaitJvmShutdown(final CountDownLatch stopped, final Runnable requestStop) throws InterruptedException {
        enterJvmShutdown();
        // Listener/socket closure must not strand the JVM hook before it reaches its timed wait.
        final Thread initiator = new Thread(requestStop, "sjm-daemon-stop-initiator");
        initiator.setDaemon(true);
        initiator.start();
        awaitStopped(stopped);
    }

    private void awaitDeadlineAndTerminate() {
        long remaining;
        while ((remaining = remainingNanos()) > 0) {
            LockSupport.parkNanos(remaining);
            Thread.interrupted();
        }
        // Do not log, close resources, acquire application locks, or run shutdown hooks on this final deadline path.
        if (exitOwner.get() == ExitOwner.AUTHENTICATED_STOP) { terminateProcess.run(); }
    }

    private static void startDedicatedWatchdog(final Runnable watchdog) {
        final Thread thread = new Thread(watchdog, "sjm-daemon-stop-watchdog");
        thread.setDaemon(true);
        thread.start();
    }
}
