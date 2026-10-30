package org.simplejavamail.internal.clisupport.daemon;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class DaemonShutdownTest {
    @Test
    void productionBudgetsKeepThirtySecondsGraceWithinSixtySecondsTotal() {
        assertThat(DaemonShutdown.APPLICATION_GRACE).isEqualTo(Duration.ofSeconds(30));
        assertThat(DaemonShutdown.TOTAL_TIMEOUT).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void firstStopOwnsBothBudgetsAndDuplicateStopsCannotResetTheWatchdog() {
        final AtomicLong clock = new AtomicLong(100);
        final List<Runnable> watchdogs = new ArrayList<>();
        final AtomicInteger terminations = new AtomicInteger();
        final DaemonShutdown shutdown = DaemonShutdown.observing(Duration.ofSeconds(30), Duration.ofSeconds(60),
                clock::get, watchdogs::add, terminations::incrementAndGet);

        shutdown.beginAuthenticatedStop();
        clock.addAndGet(Duration.ofSeconds(31).toNanos());
        shutdown.beginAuthenticatedStop();

        assertThat(shutdown.applicationRemainingNanos()).isZero();
        assertThat(shutdown.remainingNanos()).isEqualTo(Duration.ofSeconds(29).toNanos());
        assertThat(watchdogs).hasSize(1);
        assertThat(terminations).hasValue(0);
        clock.addAndGet(Duration.ofSeconds(29).toNanos());
        watchdogs.get(0).run();
        assertThat(terminations).hasValue(1);
        assertThat(shutdown.remainingNanos()).isZero();
    }

    @Test
    void authenticatedStopKeepsTheWatchdogThroughLaterJvmShutdown() {
        final AtomicLong clock = new AtomicLong();
        final List<Runnable> watchdogs = new ArrayList<>();
        final AtomicInteger terminations = new AtomicInteger();
        final DaemonShutdown shutdown = DaemonShutdown.observing(Duration.ofSeconds(1), Duration.ofSeconds(2),
                clock::get, watchdogs::add, terminations::incrementAndGet);
        shutdown.beginAuthenticatedStop();
        shutdown.enterJvmShutdown();
        clock.set(Duration.ofSeconds(3).toNanos());
        watchdogs.get(0).run();
        assertThat(terminations).hasValue(1);
    }

    @Test
    void authenticatedStopAfterAccountingOnlyCleanupReusesItsRemainingBudget() {
        final AtomicLong clock = new AtomicLong();
        final List<Runnable> watchdogs = new ArrayList<>();
        final AtomicInteger terminations = new AtomicInteger();
        final DaemonShutdown shutdown = DaemonShutdown.observing(Duration.ofSeconds(30), Duration.ofSeconds(60),
                clock::get, watchdogs::add, terminations::incrementAndGet);
        shutdown.begin();
        clock.set(Duration.ofSeconds(31).toNanos());
        shutdown.beginAuthenticatedStop();
        assertThat(shutdown.applicationRemainingNanos()).isZero();
        assertThat(shutdown.remainingNanos()).isEqualTo(Duration.ofSeconds(29).toNanos());
        assertThat(watchdogs).hasSize(1);
        clock.set(Duration.ofSeconds(60).toNanos());
        watchdogs.get(0).run();
        assertThat(terminations).hasValue(1);
    }

    @Test
    void embeddedShutdownHasNoProcessTerminationAuthority() {
        final AtomicLong clock = new AtomicLong();
        final AtomicInteger terminations = new AtomicInteger();
        final DaemonShutdown shutdown = DaemonShutdown.observing(Duration.ofSeconds(1), Duration.ofSeconds(2),
                clock::get, null, terminations::incrementAndGet);
        shutdown.begin();
        clock.set(Duration.ofSeconds(3).toNanos());
        shutdown.begin();
        assertThat(shutdown.remainingNanos()).isZero();
        assertThat(terminations).hasValue(0);
    }

    @Test
    void jvmShutdownKeepsTheSignalOwnedExitInsteadOfHaltingWithSuccess() throws Exception {
        final AtomicLong clock = new AtomicLong();
        final List<Runnable> watchdogs = new ArrayList<>();
        final AtomicInteger terminations = new AtomicInteger();
        final DaemonShutdown shutdown = DaemonShutdown.observing(Duration.ofSeconds(1), Duration.ofSeconds(2),
                clock::get, watchdogs::add, terminations::incrementAndGet);
        shutdown.enterJvmShutdown();
        shutdown.beginAuthenticatedStop();
        clock.set(Duration.ofSeconds(3).toNanos());
        shutdown.awaitStopped(new CountDownLatch(1));
        assertThat(watchdogs).isEmpty();
        assertThat(terminations).hasValue(0);
    }

    @Test
    void dedicatedWatchdogDoesNotNeedTheCommandOrCleanupWorkerToReturn() throws Exception {
        final CountDownLatch terminated = new CountDownLatch(1);
        final DaemonShutdown shutdown = DaemonShutdown.observing(Duration.ofMillis(25), Duration.ofMillis(50),
                System::nanoTime, action -> {
                    final Thread watchdog = new Thread(action, "test-shutdown-watchdog");
                    watchdog.setDaemon(true);
                    watchdog.start();
                }, terminated::countDown);
        shutdown.beginAuthenticatedStop();
        assertThat(terminated.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void invalidTimingSeamsCannotExtendGracePastTheWholeBudget() {
        assertThatThrownBy(() -> DaemonShutdown.observing(Duration.ofSeconds(3), Duration.ofSeconds(2),
                System::nanoTime, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startupFailureCleanupDoesNotAcquireAuthenticatedStopAuthority() {
        final AtomicLong clock = new AtomicLong();
        final List<Runnable> watchdogs = new ArrayList<>();
        final AtomicInteger terminations = new AtomicInteger();
        final DaemonShutdown shutdown = DaemonShutdown.observing(Duration.ofSeconds(1), Duration.ofSeconds(2),
                clock::get, watchdogs::add, terminations::incrementAndGet);
        shutdown.begin();
        clock.set(Duration.ofSeconds(3).toNanos());
        shutdown.begin();
        assertThat(watchdogs).isEmpty();
        assertThat(terminations).hasValue(0);
    }

    @Test
    void aBlockedStopInitiatorCannotExtendTheJvmHooksTimedWait() throws Exception {
        final CountDownLatch initiatorEntered = new CountDownLatch(1);
        final CountDownLatch releaseInitiator = new CountDownLatch(1);
        final CountDownLatch initiatorFinished = new CountDownLatch(1);
        final DaemonShutdown shutdown = DaemonShutdown.observing(Duration.ofMillis(25), Duration.ofMillis(100),
                System::nanoTime, null, null);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> shutdown.awaitJvmShutdown(new CountDownLatch(1), () -> {
                initiatorEntered.countDown();
                try { releaseInitiator.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { initiatorFinished.countDown(); }
            }));
            assertThat(initiatorEntered.getCount()).isZero();
            assertThat(initiatorFinished.getCount()).isEqualTo(1);
        } finally {
            releaseInitiator.countDown();
            assertThat(initiatorFinished.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
