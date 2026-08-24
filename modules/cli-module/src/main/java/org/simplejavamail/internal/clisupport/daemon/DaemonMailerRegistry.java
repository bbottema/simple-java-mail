package org.simplejavamail.internal.clisupport.daemon;

import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.internal.clisupport.CliMailerProfile;
import org.simplejavamail.internal.clisupport.MailerProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Reuses daemon-owned Mailers without allowing incompatible configurations to share one.
 * A {@link CliMailerProfile} selects a bounded cache entry, a construction future prevents duplicate Mailer creation,
 * and leases keep an entry alive while a command is using it. Idle, capacity, and shutdown retirement all converge on
 * one close-once path so batch executors and SMTP connection pools are released with their Mailer.
 */
final class DaemonMailerRegistry implements MailerProvider {
	private static final Logger LOGGER = LoggerFactory.getLogger(DaemonMailerRegistry.class);
	private static final int DEFAULT_MAX_ENTRIES = 16;
	private static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(15);

	private final ConcurrentHashMap<CliMailerProfile, Entry> entries = new ConcurrentHashMap<>();
	private final Object entryCreationLock = new Object();
	private final Semaphore capacity;
	private final long idleNanos;
	private final ScheduledExecutorService reaper;
	private final AtomicBoolean closed = new AtomicBoolean();
	private final AtomicInteger closeFailures = new AtomicInteger();

	DaemonMailerRegistry() {
		this(Integer.getInteger("simplejavamail.cli.daemon.max-mailers", DEFAULT_MAX_ENTRIES),
				Duration.ofMillis(Long.getLong("simplejavamail.cli.daemon.mailer-idle-millis",
						DEFAULT_IDLE_TIMEOUT.toMillis())));
	}

	DaemonMailerRegistry(final int maximumEntries, final Duration idleTimeout) {
		if (maximumEntries < 1 || maximumEntries > 256 || idleTimeout.isNegative()) {
			throw new IllegalArgumentException("Invalid daemon Mailer registry limits");
		}
		capacity = new Semaphore(maximumEntries);
		idleNanos = idleTimeout.toNanos();
		reaper = Executors.newSingleThreadScheduledExecutor(runnable -> {
			final Thread thread = new Thread(runnable, "sjm-daemon-mailer-reaper");
			thread.setDaemon(true);
			return thread;
		});
		reaper.scheduleWithFixedDelay(this::retireExpiredEntries, 30, 30, TimeUnit.SECONDS);
	}

	@Override
	public Lease acquire(@NotNull final CliMailerProfile profile, @NotNull final Supplier<Mailer> factory) {
		while (!closed.get()) {
			final EntryRegistration registration = registerEntry(profile);
			constructMailerIfNeeded(profile, registration, factory);
			final Mailer mailer = awaitMailerConstruction(registration.entry());
			if (registration.entry().acquire()) {
				return lease(registration.entry(), mailer);
			}
		}
		throw shuttingDown();
	}

	private EntryRegistration registerEntry(final CliMailerProfile profile) {
		assertOpen();
		final Entry existing = entries.get(profile);
		if (existing != null) {
			return new EntryRegistration(existing, false);
		}
		synchronized (entryCreationLock) {
			assertOpen();
			final Entry competingEntry = entries.get(profile);
			if (competingEntry != null) {
				return new EntryRegistration(competingEntry, false);
			}
			if (!reserveCapacityOrRetireOldest()) {
				throw new DaemonOverloadedException("Daemon Mailer profile limit reached");
			}
			final Entry registered = new Entry();
			entries.put(profile, registered);
			return new EntryRegistration(registered, true);
		}
	}

	private void constructMailerIfNeeded(final CliMailerProfile profile, final EntryRegistration registration,
			final Supplier<Mailer> factory) {
		if (!registration.newlyRegistered()) {
			return;
		}
		final Entry entry = registration.entry();
		try {
			entry.mailer.complete(Objects.requireNonNull(factory.get(), "Mailer factory returned null"));
		} catch (Throwable failure) {
			entry.mailer.completeExceptionally(failure);
			if (entries.remove(profile, entry)) {
				capacity.release();
			}
		}
		if (closed.get()) {
			retire(profile, entry);
		}
	}

	private static Mailer awaitMailerConstruction(final Entry entry) {
		try {
			return entry.mailer.join();
		} catch (CompletionException e) {
			final Throwable cause = e.getCause();
			if (cause instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			throw new IllegalStateException("Unable to construct daemon Mailer", cause);
		}
	}

	private static Lease lease(final Entry entry, final Mailer mailer) {
		return new Lease() {
			private final AtomicBoolean released = new AtomicBoolean();

			@Override
			public Mailer mailer() {
				return mailer;
			}

			@Override
			public void close() {
				if (released.compareAndSet(false, true)) {
					entry.release();
				}
			}
		};
	}

	private void assertOpen() {
		if (closed.get()) {
			throw shuttingDown();
		}
	}

	private static DaemonOverloadedException shuttingDown() {
		return new DaemonOverloadedException("Daemon Mailer registry is shutting down");
	}

	int entryCount() {
		return entries.size();
	}

	int activeLeaseCount() {
		return entries.values().stream().mapToInt(Entry::activeLeaseCount).sum();
	}

	int closeFailureCount() {
		return closeFailures.get();
	}

	private boolean reserveCapacityOrRetireOldest() {
		if (capacity.tryAcquire()) {
			return true;
		}
		retireOldestIdle();
		return capacity.tryAcquire();
	}

	private void retireExpiredEntries() {
		if (idleNanos == 0) {
			return;
		}
		final long cutoff = System.nanoTime() - idleNanos;
		for (final Map.Entry<CliMailerProfile, Entry> candidate : entries.entrySet()) {
			if (candidate.getValue().lastUsedNanos < cutoff) {
				retire(candidate.getKey(), candidate.getValue());
			}
		}
	}

	private void retireOldestIdle() {
		entries.entrySet().stream()
				.filter(entry -> entry.getValue().activeLeaseCount() == 0)
				.min(Comparator.comparingLong(entry -> entry.getValue().lastUsedNanos))
				.ifPresent(entry -> retire(entry.getKey(), entry.getValue()));
	}

	private void retire(final CliMailerProfile profile, final Entry entry) {
		if (entry.retire() && entries.remove(profile, entry)) {
			capacity.release();
			closeWhenConstructed(entry);
		}
	}

	private void forceRetire(final CliMailerProfile profile, final Entry entry) {
		if (entry.forceRetire() && entries.remove(profile, entry)) {
			capacity.release();
			closeWhenConstructed(entry);
		}
	}

	private void closeWhenConstructed(final Entry entry) {
		entry.mailer.whenComplete((mailer, failure) -> {
			if (mailer == null || !entry.startClose()) {
				return;
			}
			try {
				mailer.close();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				closeFailures.incrementAndGet();
				LOGGER.warn("A cached daemon Mailer close was interrupted; remaining Mailers will still be retired");
			} catch (Exception ignored) {
				closeFailures.incrementAndGet();
				LOGGER.warn("A cached daemon Mailer could not be closed cleanly; remaining Mailers will still be retired");
			}
		});
	}

	@Override
	public void close() {
		closeWithin(Duration.ofSeconds(30));
	}

	void closeAfterExecutorDrain() {
		closeWithin(Duration.ZERO);
	}

	private void closeWithin(final Duration drainTimeout) {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		reaper.shutdownNow();
		final long deadline = System.nanoTime() + drainTimeout.toNanos();
		awaitActiveLeasesUntil(deadline);
		forceRetireAllEntries();
	}

	private void awaitActiveLeasesUntil(final long deadline) {
		while (hasActiveLeasesBefore(deadline)) {
			try {
				Thread.sleep(10);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
	}

	private boolean hasActiveLeasesBefore(final long deadline) {
		return activeLeaseCount() > 0 && System.nanoTime() < deadline;
	}

	private void forceRetireAllEntries() {
		for (final Map.Entry<CliMailerProfile, Entry> entry : new ArrayList<>(entries.entrySet())) {
			forceRetire(entry.getKey(), entry.getValue());
		}
	}

	private record EntryRegistration(Entry entry, boolean newlyRegistered) {
	}

	/** Coordinates construction, leasing, retirement, and close-once state for one cached Mailer. */
	private static final class Entry {
		private final CompletableFuture<Mailer> mailer = new CompletableFuture<>();
		private int active;
		private boolean retired;
		private boolean closeStarted;
		private volatile long lastUsedNanos = System.nanoTime();

		private synchronized boolean acquire() {
			if (retired) {
				return false;
			}
			active++;
			lastUsedNanos = System.nanoTime();
			return true;
		}

		private synchronized void release() {
			if (active <= 0) {
				throw new IllegalStateException("Daemon Mailer lease released twice");
			}
			active--;
			lastUsedNanos = System.nanoTime();
		}

		private synchronized boolean retire() {
			if (retired || active != 0 || !mailer.isDone() || mailer.isCompletedExceptionally()) {
				return false;
			}
			retired = true;
			return true;
		}

		private synchronized boolean forceRetire() {
			if (retired) {
				return false;
			}
			retired = true;
			return true;
		}

		private synchronized boolean startClose() {
			if (closeStarted) {
				return false;
			}
			closeStarted = true;
			return true;
		}

		private synchronized int activeLeaseCount() {
			return active;
		}
	}
}
