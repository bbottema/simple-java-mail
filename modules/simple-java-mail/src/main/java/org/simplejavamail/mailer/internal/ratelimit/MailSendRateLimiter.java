package org.simplejavamail.mailer.internal.ratelimit;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.internal.batchsupport.SendingAllowance;
import org.simplejavamail.api.mailer.config.SendingRateLimit;
import org.simplejavamail.internal.util.concurrent.MailSendControl;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import static java.util.Objects.requireNonNull;

/**
 * Reserves local sending allowance before resource acquisition, then charges it immediately before provider invocation.
 * Only one reservation may prepare at a time: slow connection setup cannot accumulate permissions that later submit in a burst.
 * Once charged, a submission no longer blocks another preparation except through the configured rate. Network work never holds this monitor.
 */
public final class MailSendRateLimiter implements SendingAllowance {

	private static final Reservation UNLIMITED = new Reservation(null, 0);

	private final Object monitor = new Object();
	private final Deque<Reservation> waiters = new ArrayDeque<>();
	@Nullable private final SendingRateWindow messages;
	@Nullable private final SendingRateWindow recipients;
	private final int recipientLimit;
	private final boolean allowBursts;
	private final LongSupplier clock;
	@Nullable private Reservation preparing;

	public MailSendRateLimiter(@Nullable final SendingRateLimit messages, @Nullable final SendingRateLimit recipients, final boolean allowBursts) {
		this(messages, recipients, allowBursts, System::nanoTime);
	}

	MailSendRateLimiter(@Nullable final SendingRateLimit messages, @Nullable final SendingRateLimit recipients, final boolean allowBursts,
			final LongSupplier clock) {
		this.messages = messages == null ? null : new SendingRateWindow(messages);
		this.recipients = recipients == null ? null : new SendingRateWindow(recipients);
		this.recipientLimit = recipients == null ? Integer.MAX_VALUE : recipients.getCount();
		this.allowBursts = allowBursts;
		this.clock = requireNonNull(clock, "clock");
	}

	/**
	 * Waits on the existing send thread; no transport, proxy or application callback belongs inside this method.
	 * The caller closes an unused reservation on preparation/acquisition failure and commits it immediately before invoking its provider.
	 */
	@NotNull
	@Override
	public Reservation reserve(final int recipientCount, @NotNull final MailSendControl control) {
		if (messages == null && recipients == null) {
			return UNLIMITED;
		}
		validateRecipientCost(recipientCount);
		final Reservation reservation = new Reservation(this, recipientCount);
		try (MailSendControl.Registration ignored = control.onStop(this::wakeWaiters)) {
			synchronized (monitor) {
				waiters.addLast(reservation);
			}
			awaitReservation(reservation, control);
			control.checkStopped();
			return reservation;
		} catch (RuntimeException | Error failure) {
			reservation.close();
			throw failure;
		}
	}

	/** @see SendingAllowance#isEnabled() */
	@Override
	public boolean isEnabled() {
		return messages != null || recipients != null;
	}

	private void validateRecipientCost(final int count) {
		if (count < 0) {
			throw new IllegalArgumentException("Recipient count cannot be negative.");
		}
		if (count > recipientLimit) {
			throw new IllegalArgumentException("This email has " + count + " envelope recipients, but the sending limit allows only " + recipientLimit
					+ " in a whole period. Use fewer recipients or increase the configured recipient limit; the email has not been submitted.");
		}
	}

	private void awaitReservation(final Reservation reservation, final MailSendControl control) {
		boolean reserved = false;
		while (!reserved) {
			control.checkStopped();
			synchronized (monitor) {
				final long rateWait = remainingWaitNanos(reservation, clock.getAsLong());
				if (!control.isStopRequested() && rateWait == 0) {
					waiters.removeFirst();
					preparing = reservation;
					reserved = true;
				} else if (!control.isStopRequested()) {
					waitForChange(Math.min(rateWait, control.remainingNanos()));
				}
			}
		}
	}

	private long remainingWaitNanos(final Reservation reservation, final long now) {
		if (preparing != null || waiters.peekFirst() != reservation) {
			return Long.MAX_VALUE;
		}
		final long messageWait = messages == null ? 0 : messages.remainingWaitNanos(1, now, allowBursts);
		final long recipientWait = recipients == null ? 0 : recipients.remainingWaitNanos(reservation.recipientCount, now, allowBursts);
		return Math.max(messageWait, recipientWait);
	}

	private void waitForChange(final long nanos) {
		if (nanos <= 0) {
			return;
		}
		try {
			// Object.wait releases the bookkeeping monitor. Stop callbacks only wake waiters; they never run a send or observer.
			TimeUnit.NANOSECONDS.timedWait(monitor, nanos);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for SMTP sending allowance; the email has not been submitted.", interrupted);
		}
	}

	void wakeWaiters() {
		synchronized (monitor) {
			monitor.notifyAll();
		}
	}

	private void commit(final Reservation reservation, final MailSendControl control) {
		control.checkStopped();
		synchronized (monitor) {
			if (control.isStopRequested()) {
				throw control.stoppedFailure(null);
			}
			if (reservation.finished || preparing != reservation) {
				throw new IllegalStateException("This sending allowance is no longer reserved; it cannot be submitted again.");
			}
			final long now = clock.getAsLong();
			if (messages != null) {
				messages.charge(1, now);
			}
			if (recipients != null) {
				recipients.charge(reservation.recipientCount, now);
			}
			reservation.finished = true;
			preparing = null;
			monitor.notifyAll();
		}
	}

	private void release(final Reservation reservation) {
		synchronized (monitor) {
			if (!reservation.finished) {
				waiters.remove(reservation);
				if (preparing == reservation) {
					preparing = null;
				}
				reservation.finished = true;
				monitor.notifyAll();
			}
		}
	}

	/** One caller-owned reservation. Closing after commit never refunds a submission, regardless of its result. */
	public static final class Reservation implements SendingAllowance.Reservation {
		@Nullable private final MailSendRateLimiter owner;
		private final int recipientCount;
		private boolean finished;

		private Reservation(@Nullable final MailSendRateLimiter owner, final int recipientCount) {
			this.owner = owner;
			this.recipientCount = recipientCount;
		}

		@Override
		public void commit(@NotNull final MailSendControl control) {
			if (owner != null) {
				owner.commit(this, control);
			}
		}

		@Override
		public void close() {
			if (owner != null) {
				owner.release(this);
			}
		}
	}
}
