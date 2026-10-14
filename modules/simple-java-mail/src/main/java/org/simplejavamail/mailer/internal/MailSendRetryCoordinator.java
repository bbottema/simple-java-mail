package org.simplejavamail.mailer.internal;

import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded retry admission. Timers only signal; one lazily started dispatcher hands existing operations back to their configured executor. */
final class MailSendRetryCoordinator implements AutoCloseable {

	private final int capacity;
	private final Executor executor;
	private final ScheduledThreadPoolExecutor timers;
	private final Set<Ticket> pending = new HashSet<>();
	private final ArrayDeque<Ticket> ready = new ArrayDeque<>();
	private Thread dispatcher;
	private boolean closed;

	MailSendRetryCoordinator(final int capacity, final Executor executor, final ScheduledThreadPoolExecutor timers) {
		this.capacity = capacity;
		this.executor = executor;
		this.timers = timers;
	}

	synchronized Ticket reserve(final MailSendOperation<?> operation) {
		if (closed || pending.size() >= capacity) {
			throw new MailerException("The Mailer's automatic-retry waiting capacity is full, so this email was not submitted again. "
					+ "Reduce concurrent sends or increase withWaitingCapacity(...) on the initiating Mailer's retry policy; "
					+ "the last SMTP refusal is retained.");
		}
		final Ticket ticket = new Ticket(operation);
		pending.add(ticket);
		if (dispatcher == null) {
			try {
				dispatcher = new Thread(this::dispatch, "sjm-retry-dispatcher");
				dispatcher.setDaemon(true);
				dispatcher.start();
			} catch (RuntimeException | Error unavailableThread) {
				pending.remove(ticket);
				dispatcher = null;
				throw unavailableThread;
			}
		}
		return ticket;
	}

	private void dispatch() {
		Ticket ticket;
		while ((ticket = awaitReadyTicket()) != null) {
			submitRetry(ticket);
		}
	}

	@Nullable
	private synchronized Ticket awaitReadyTicket() {
		while (ready.isEmpty() && !closed) {
			try {
				wait();
			} catch (InterruptedException interrupted) {
				if (closed) { return null; }
				// An unrelated interruption does not discard live retry tickets.
			}
		}
		return closed ? null : ready.removeFirst();
	}

	private void submitRetry(final Ticket ticket) {
		// Executor admission may block or run inline. Never do it under bookkeeping locks or on the deadline worker.
		if (ticket.operation.beginRetryResume()) {
			try {
				executor.execute(ticket.operation);
			} catch (RuntimeException | Error rejection) {
				final MailerException explanation = new MailerException("The configured executor could not resume this email's retry. "
						+ "Keep it open until the Mailer has drained, and check its admission capacity. No further submission was started.");
				explanation.addSuppressed(rejection);
				ticket.operation.retryResumeRejected(explanation);
			}
		}
	}

	@Override
	public synchronized void close() {
		closed = true;
		notifyAll();
		if (dispatcher != null && dispatcher != Thread.currentThread()) { dispatcher.interrupt(); }
	}

	/** Owns one pending slot through delay, dispatcher admission and executor queueing, until actual execution or retirement. */
	final class Ticket implements AutoCloseable {
		private final MailSendOperation<?> operation;
		private ScheduledFuture<?> alarm;
		private boolean released;

		private Ticket(final MailSendOperation<?> operation) { this.operation = operation; }

		void activate(final Duration delay) {
			synchronized (MailSendRetryCoordinator.this) {
				if (!released) {
					alarm = timers.schedule(this::signalReady, delay.toNanos(), TimeUnit.NANOSECONDS);
				}
			}
		}

		private void signalReady() {
			synchronized (MailSendRetryCoordinator.this) {
				if (!released) {
					ready.addLast(this);
					MailSendRetryCoordinator.this.notifyAll();
				}
			}
		}

		@Override
		public void close() {
			synchronized (MailSendRetryCoordinator.this) {
				if (!released) {
					released = true;
					pending.remove(this);
					ready.remove(this);
					if (alarm != null) { alarm.cancel(false); }
				}
			}
		}
	}
}
