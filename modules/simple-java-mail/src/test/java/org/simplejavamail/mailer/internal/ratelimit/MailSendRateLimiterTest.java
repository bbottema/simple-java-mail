package org.simplejavamail.mailer.internal.ratelimit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.simplejavamail.api.mailer.MailSendCancelledException;
import org.simplejavamail.api.mailer.config.SendingRateLimit;
import org.simplejavamail.internal.util.concurrent.MailSendControl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@Timeout(15)
class MailSendRateLimiterTest {

	private static final long PERIOD = SECONDS.toNanos(10);
	private final ExecutorService workers = Executors.newFixedThreadPool(8);
	private final ScheduledExecutorService watcher = mock(ScheduledExecutorService.class);

	@AfterEach
	void stopWorkers() throws Exception {
		workers.shutdownNow();
		assertThat(workers.awaitTermination(5, SECONDS)).isTrue();
	}

	@Test
	void disabledLimiterDoesNotReadClockRegisterCallbacksOrAllocateReservations() {
		final LongSupplier clock = mock(LongSupplier.class);
		final MailSendControl control = mock(MailSendControl.class);
		final MailSendRateLimiter limiter = new MailSendRateLimiter(null, null, false, clock);
		final MailSendRateLimiter.Reservation first = limiter.reserve(1, control);
		assertThat(limiter.reserve(100, control)).isSameAs(first);
		first.commit(control);
		first.close();
		verifyNoInteractions(clock, control);
	}

	@Test
	void abandonedPreparationReleasesAllowanceButClosingASubmissionDoesNotRefundIt() throws Exception {
		final TestClock clock = new TestClock();
		final MailSendRateLimiter limiter = limiter(1, null, true, clock);
		final MailSendControl control = control();
		final MailSendRateLimiter.Reservation unused = limiter.reserve(1, control);
		unused.close();
		unused.close();
		try (MailSendRateLimiter.Reservation submitted = limiter.reserve(1, control)) {
			submitted.commit(control);
			assertThatThrownBy(() -> submitted.commit(control)).isInstanceOf(IllegalStateException.class);
		}
		final Future<MailSendRateLimiter.Reservation> following = queued(limiter, 1, control, clock);
		clock.now.set(PERIOD);
		limiter.wakeWaiters();
		try (MailSendRateLimiter.Reservation reserved = following.get(5, SECONDS)) {
			reserved.commit(control);
		}
	}

	@Test
	void slowPreparationCannotBankLaterReservationsAndSendsMayOverlapAfterCommit() throws Exception {
		final TestClock clock = new TestClock();
		final MailSendRateLimiter limiter = limiter(2, null, false, clock);
		final MailSendControl control = control();
		final MailSendRateLimiter.Reservation slow = limiter.reserve(1, control);
		final Future<MailSendRateLimiter.Reservation> next = queued(limiter, 1, control, clock);
		clock.now.set(100 * PERIOD);
		slow.commit(control);
		clock.resetReadBarrier();
		limiter.wakeWaiters();
		clock.awaitRead();
		assertThat(next.isDone()).isFalse();
		clock.now.addAndGet(PERIOD / 2);
		limiter.wakeWaiters();
		try (MailSendRateLimiter.Reservation reserved = next.get(5, SECONDS)) {
			reserved.commit(control);
		}
		// The original send has not finished/closed yet; only its provider invocation was needed to admit a successor.
		slow.close();
	}

	@Test
	void cancelledLargeHeadDoesNotBlockASmallerFollowerOrConsumeItsAllowance() throws Exception {
		final TestClock clock = new TestClock();
		final MailSendRateLimiter limiter = limiter(null, 10, true, clock);
		final MailSendControl successfulControl = control();
		try (MailSendRateLimiter.Reservation initial = limiter.reserve(9, successfulControl)) {
			initial.commit(successfulControl);
		}
		final MailSendControl headControl = control();
		final Future<MailSendRateLimiter.Reservation> head = queued(limiter, 2, headControl, clock);
		final Future<MailSendRateLimiter.Reservation> follower = queued(limiter, 1, successfulControl, clock);
		assertThat(follower.isDone()).isFalse();
		headControl.requestCancellation();
		assertThatThrownBy(() -> head.get(5, SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(MailSendCancelledException.class);
		try (MailSendRateLimiter.Reservation reserved = follower.get(5, SECONDS)) {
			reserved.commit(successfulControl);
		}
		assertThat(clock.now.get()).isZero();
	}

	@Test
	void bothRulesMustAllowTheSameSubmission() throws Exception {
		final TestClock clock = new TestClock();
		final SendingRateLimit messageRule = new SendingRateLimit(1, Duration.ofNanos(PERIOD));
		final SendingRateLimit recipientRule = new SendingRateLimit(10, Duration.ofNanos(PERIOD * 2));
		final MailSendRateLimiter limiter = new MailSendRateLimiter(messageRule, recipientRule, true, clock);
		final MailSendControl control = control();
		try (MailSendRateLimiter.Reservation first = limiter.reserve(6, control)) {
			first.commit(control);
		}
		clock.now.set(PERIOD);
		final Future<MailSendRateLimiter.Reservation> next = queued(limiter, 6, control, clock);
		assertThat(next.isDone()).isFalse();
		clock.now.set(2 * PERIOD);
		limiter.wakeWaiters();
		next.get(5, SECONDS).close();
	}

	@Test
	void cancellationBeforeCommitLeavesNothingCharged() {
		final TestClock clock = new TestClock();
		final MailSendRateLimiter limiter = limiter(1, null, false, clock);
		final MailSendControl cancelledControl = control();
		try (MailSendRateLimiter.Reservation cancelled = limiter.reserve(1, cancelledControl)) {
			cancelledControl.requestCancellation();
			assertThatThrownBy(() -> cancelled.commit(cancelledControl)).isInstanceOf(MailSendCancelledException.class);
		}
		final MailSendControl nextControl = control();
		try (MailSendRateLimiter.Reservation next = limiter.reserve(1, nextControl)) {
			next.commit(nextControl);
		}
		assertThat(clock.now.get()).isZero();
	}

	@Test
	void rejectsOversizedEmailsBeforeReadingTheClockOrRegisteringForCancellation() {
		final LongSupplier clock = mock(LongSupplier.class);
		final MailSendControl control = mock(MailSendControl.class);
		final MailSendRateLimiter limiter = limiter(null, 10, true, clock);
		assertThatThrownBy(() -> limiter.reserve(11, control)).hasMessageContaining("11 envelope recipients").hasMessageContaining("only 10");
		verifyNoInteractions(clock, control);
	}

	@Test
	void interruptionPreservesTheFlagAndRemovesTheWaitingReservation() throws Exception {
		final TestClock clock = new TestClock();
		final MailSendRateLimiter limiter = limiter(1, null, true, clock);
		final MailSendControl control = control();
		try (MailSendRateLimiter.Reservation preparing = limiter.reserve(1, control)) {
			clock.resetReadBarrier();
			final CompletableFuture<Boolean> interrupted = new CompletableFuture<>();
			final Thread waiting = new Thread(() -> {
				try (MailSendRateLimiter.Reservation unexpected = limiter.reserve(1, control)) {
					interrupted.completeExceptionally(new AssertionError("The existing preparation must block this reservation"));
				} catch (IllegalStateException failure) {
					interrupted.complete(Thread.currentThread().isInterrupted());
				} catch (Throwable failure) {
					interrupted.completeExceptionally(failure);
				}
			});
			waiting.start();
			try {
				clock.awaitRead();
				waiting.interrupt();
				assertThat(interrupted.get(5, SECONDS)).isTrue();
			} finally {
				waiting.interrupt();
				waiting.join(5000);
			}
		}
		limiter.reserve(1, control).close();
	}

	@Test
	void concurrentReservationsCannotDoubleChargeOrLoseAttempts() throws Exception {
		final TestClock clock = new TestClock();
		final MailSendRateLimiter limiter = limiter(100, 100, true, clock);
		final List<Future<?>> submitted = new ArrayList<>();
		for (int attempt = 0; attempt < 100; attempt++) {
			submitted.add(workers.submit(() -> {
				final MailSendControl control = control();
				try (MailSendRateLimiter.Reservation reservation = limiter.reserve(1, control)) {
					reservation.commit(control);
				}
			}));
		}
		for (final Future<?> attempt : submitted) {
			attempt.get(5, SECONDS);
		}
		final MailSendControl nextControl = control();
		final Future<MailSendRateLimiter.Reservation> next = queued(limiter, 1, nextControl, clock);
		try {
			assertThat(next.isDone()).isFalse();
		} finally {
			nextControl.requestCancellation();
		}
		assertThatThrownBy(() -> next.get(5, SECONDS)).hasCauseInstanceOf(MailSendCancelledException.class);
	}

	private MailSendControl control() {
		return new MailSendControl(null, watcher);
	}

	private static MailSendRateLimiter limiter(final Integer messages, final Integer recipients, final boolean allowBursts, final LongSupplier clock) {
		return new MailSendRateLimiter(messages == null ? null : new SendingRateLimit(messages, Duration.ofNanos(PERIOD)),
				recipients == null ? null : new SendingRateLimit(recipients, Duration.ofNanos(PERIOD)), allowBursts, clock);
	}

	private Future<MailSendRateLimiter.Reservation> queued(final MailSendRateLimiter limiter, final int cost,
			final MailSendControl control, final TestClock clock) throws Exception {
		clock.resetReadBarrier();
		final Future<MailSendRateLimiter.Reservation> waiting = workers.submit(() -> limiter.reserve(cost, control));
		clock.awaitRead();
		return waiting;
	}

	/** Handshake when a caller reaches bookkeeping; advancing time and waking it are explicit test actions. */
	private static final class TestClock implements LongSupplier {
		private final AtomicLong now = new AtomicLong();
		private final Semaphore reads = new Semaphore(0);

		@Override
		public long getAsLong() {
			final long sampled = now.get();
			reads.release();
			return sampled;
		}

		void resetReadBarrier() {
			reads.drainPermits();
		}

		void awaitRead() throws InterruptedException {
			assertThat(reads.tryAcquire(5, SECONDS)).isTrue();
		}
	}
}
