package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.simplejavamail.api.mailer.MailSendProgress;
import org.simplejavamail.api.mailer.MailSendProgressStage;

import java.time.Duration;
import java.time.Instant;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

@Timeout(15)
class MailSendProgressDispatcherTest {
    @Test
    void coalescesPerIdAndNeverHandsOffASecondCallbackWhileOneIsOutstanding() throws Exception {
        final BlockingQueue<Runnable> executor = new LinkedBlockingQueue<>();
        final List<MailSendProgress> delivered = new ArrayList<>();
        final UUID id = UUID.randomUUID();
        try (MailSendProgressDispatcher dispatcher = new MailSendProgressDispatcher(delivered::add, executor::add)) {
            dispatcher.offer(snapshot(id, 1));
            final Runnable first = take(executor);
            dispatcher.offer(snapshot(id, 2));
            dispatcher.offer(snapshot(id, 3));
            dispatcher.offer(snapshot(id, 2));
            assertThat(executor).isEmpty();
            first.run();
            take(executor).run();
            first.run(); // A buggy executor must not deliver an old callback twice.
            assertThat(delivered).extracting(MailSendProgress::getSequence).containsExactly(1L, 3L);
        }
    }

    @Test
    void boundsPendingIdsAndEvictsTheOldestWithoutLimitingSenders() throws Exception {
        final BlockingQueue<Runnable> executor = new LinkedBlockingQueue<>();
        final List<MailSendProgress> delivered = new ArrayList<>();
        try (MailSendProgressDispatcher dispatcher = new MailSendProgressDispatcher(delivered::add, executor::add)) {
            dispatcher.offer(snapshot(UUID.randomUUID(), 1));
            final Runnable first = take(executor);
            final UUID evicted = UUID.randomUUID();
            dispatcher.offer(snapshot(evicted, 1));
            for (int i = 0; i < MailSendProgressDispatcher.MAX_PENDING_IDS; i++) {
                dispatcher.offer(snapshot(UUID.randomUUID(), 1));
            }
            first.run();
            for (int i = 0; i < MailSendProgressDispatcher.MAX_PENDING_IDS; i++) {
                take(executor).run();
            }
            assertThat(delivered).hasSize(MailSendProgressDispatcher.MAX_PENDING_IDS + 1);
            assertThat(delivered).extracting(MailSendProgress::getObservationId).doesNotContain(evicted);
        }
    }

    @Test
    void refreshingAnOlderIdKeepsItsNewSnapshotInsteadOfEvictingIt() throws Exception {
        final BlockingQueue<Runnable> executor = new LinkedBlockingQueue<>();
        final List<MailSendProgress> delivered = new ArrayList<>();
        try (MailSendProgressDispatcher dispatcher = new MailSendProgressDispatcher(delivered::add, executor::add)) {
            dispatcher.offer(snapshot(UUID.randomUUID(), 1));
            final Runnable blocked = take(executor);
            final UUID refreshed = UUID.randomUUID();
            final UUID oldestUnchanged = UUID.randomUUID();
            dispatcher.offer(snapshot(refreshed, 1));
            dispatcher.offer(snapshot(oldestUnchanged, 1));
            for (int i = 2; i < MailSendProgressDispatcher.MAX_PENDING_IDS; i++) {
                dispatcher.offer(snapshot(UUID.randomUUID(), 1));
            }
            dispatcher.offer(snapshot(refreshed, 2));
            dispatcher.offer(snapshot(UUID.randomUUID(), 1));
            blocked.run();
            for (int i = 0; i < MailSendProgressDispatcher.MAX_PENDING_IDS; i++) {
                take(executor).run();
            }
            assertThat(delivered).extracting(MailSendProgress::getObservationId).contains(refreshed).doesNotContain(oldestUnchanged);
            assertThat(delivered).filteredOn(value -> value.getObservationId().equals(refreshed))
                    .extracting(MailSendProgress::getSequence).containsExactly(2L);
        }
    }

    @Test
    void blockingCallerRunsExecutorNeverRunsOnThePublishingThreadAndShutdownDoesNotInterruptIt() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch returned = new CountDownLatch(1);
        final AtomicReference<Thread> callbackThread = new AtomicReference<>();
        final AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        final MailSendProgressDispatcher dispatcher = new MailSendProgressDispatcher(progress -> {
            callbackThread.set(Thread.currentThread());
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                callbackFailure.set(interrupted);
            } finally {
                returned.countDown();
            }
        }, Runnable::run);
        try {
            dispatcher.offer(snapshot(UUID.randomUUID(), 1));
            assertThat(entered.await(5, SECONDS)).isTrue();
            dispatcher.offer(snapshot(UUID.randomUUID(), 1));
            dispatcher.close();
            assertThat(callbackThread.get()).isNotSameAs(Thread.currentThread());
            assertThat(callbackThread.get().isDaemon()).isTrue();
            assertThat(returned.getCount()).isEqualTo(1);
        } finally {
            release.countDown();
            dispatcher.close();
        }
        assertThat(returned.await(5, SECONDS)).isTrue();
        assertThat(callbackFailure).hasNullValue();
    }

    @Test
    void closingDiscardsPendingAndAlreadyHandedOffButUnstartedCallbacks() throws Exception {
        final BlockingQueue<Runnable> executor = new LinkedBlockingQueue<>();
        final AtomicInteger callbacks = new AtomicInteger();
        final MailSendProgressDispatcher dispatcher = new MailSendProgressDispatcher(ignored -> callbacks.incrementAndGet(), executor::add);
        dispatcher.offer(snapshot(UUID.randomUUID(), 1));
        final Runnable outstanding = take(executor);
        dispatcher.offer(snapshot(UUID.randomUUID(), 1));
        dispatcher.close();
        assertDetached(outstanding);
        dispatcher.offer(snapshot(UUID.randomUUID(), 1));
        outstanding.run();
        assertThat(callbacks).hasValue(0);
        assertThat(executor).isEmpty();
    }

    @Test
    void completedAndRejectedTasksReleaseTheirOwnerAndSnapshotEvenIfTheExecutorRetainsThem() throws Exception {
        final BlockingQueue<Runnable> handedOff = new LinkedBlockingQueue<>();
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger handoffs = new AtomicInteger();
        try (MailSendProgressDispatcher dispatcher = new MailSendProgressDispatcher(ignored -> calls.incrementAndGet(), task -> {
            handedOff.add(task);
            if (handoffs.incrementAndGet() == 1) {
                throw new RejectedExecutionException("retained but rejected");
            }
        })) {
            dispatcher.offer(snapshot(UUID.randomUUID(), 1));
            final Runnable rejected = take(handedOff);
            dispatcher.offer(snapshot(UUID.randomUUID(), 1));
            final Runnable completed = take(handedOff); // Dispatch of the second task proves rejection cleanup has completed.
            assertDetached(rejected);
            rejected.run();
            completed.run();
            assertDetached(completed);
            completed.run();
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void executorStartingThenThrowingDoesNotReleaseTheRunningCallbackSlot() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger handoffs = new AtomicInteger();
        final AtomicReference<Thread> worker = new AtomicReference<>();
        final AtomicReference<Runnable> retained = new AtomicReference<>();
        final BlockingQueue<Runnable> later = new LinkedBlockingQueue<>();
        try (MailSendProgressDispatcher dispatcher = new MailSendProgressDispatcher(ignored -> {
            entered.countDown();
            await(release);
        }, task -> {
            if (handoffs.incrementAndGet() == 1) {
                retained.set(task);
                final Thread thread = new Thread(task);
                worker.set(thread);
                thread.start();
                await(entered);
                throw new RejectedExecutionException("already started");
            }
            later.add(task);
        })) {
            dispatcher.offer(snapshot(UUID.randomUUID(), 1));
            assertThat(entered.await(5, SECONDS)).isTrue();
            assertDetached(retained.get());
            dispatcher.offer(snapshot(UUID.randomUUID(), 1));
            assertThat(later).isEmpty();
            release.countDown();
            take(later).run();
        } finally {
            release.countDown();
            if (worker.get() != null) {
                worker.get().join(5000);
                assertThat(worker.get().isAlive()).isFalse();
            }
        }
    }

    private static void assertDetached(final Runnable task) throws Exception {
        // Check reachability deterministically, rather than relying on a collector choosing to run during a unit test.
        for (final String name : new String[] {"owner", "progress"}) {
            final Field field = task.getClass().getDeclaredField(name);
            field.setAccessible(true);
            assertThat(field.get(task)).as("retained executor task's %s", name).isNull();
        }
        assertThat(task.getClass().getDeclaredFields()).noneMatch(Field::isSynthetic);
    }

    private static void await(final CountDownLatch latch) {
        try {
            assertThat(latch.await(5, SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    @Test
    void rejectionAndThrowingCallbacksDoNotStopLaterReporting() throws Exception {
        final BlockingQueue<Runnable> executor = new LinkedBlockingQueue<>();
        final CountDownLatch rejected = new CountDownLatch(1);
        final AtomicInteger handoffs = new AtomicInteger();
        final AtomicInteger callbacks = new AtomicInteger();
        try (MailSendProgressDispatcher dispatcher = new MailSendProgressDispatcher(ignored -> {
            callbacks.incrementAndGet();
            throw new IllegalStateException("broken dashboard");
        }, task -> {
            if (handoffs.incrementAndGet() == 1) {
                rejected.countDown();
                throw new RejectedExecutionException("full application executor");
            }
            executor.add(task);
        })) {
            dispatcher.offer(snapshot(UUID.randomUUID(), 1));
            assertThat(rejected.await(5, SECONDS)).isTrue();
            dispatcher.offer(snapshot(UUID.randomUUID(), 1));
            take(executor).run();
            dispatcher.offer(snapshot(UUID.randomUUID(), 1));
            take(executor).run();
            assertThat(callbacks).hasValue(2);
        }
    }

    @Test
    void callbacksCanPublishMoreProgressWithoutReentrantDispatch() throws Exception {
        final BlockingQueue<Runnable> executor = new LinkedBlockingQueue<>();
        final AtomicReference<MailSendProgressDispatcher> reference = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();
        try (MailSendProgressDispatcher dispatcher = new MailSendProgressDispatcher(progress -> {
            if (calls.incrementAndGet() == 1) {
                reference.get().offer(snapshot(progress.getObservationId(), 2));
                assertThat(executor).isEmpty();
            }
        }, executor::add)) {
            reference.set(dispatcher);
            dispatcher.offer(snapshot(UUID.randomUUID(), 1));
            take(executor).run();
            take(executor).run();
            assertThat(calls).hasValue(2);
        }
    }

    static <T> T take(final BlockingQueue<T> queue) throws InterruptedException {
        final T next = queue.poll(5, SECONDS);
        assertThat(next).as("next progress handoff or snapshot").isNotNull();
        return next;
    }

    private static MailSendProgress snapshot(final UUID id, final long sequence) {
        return MailSendProgressImpl.builder().observationId(id).sequence(sequence).observedAt(Instant.EPOCH)
                .elapsed(Duration.ZERO).stage(MailSendProgressStage.PREPARING).build();
    }
}
