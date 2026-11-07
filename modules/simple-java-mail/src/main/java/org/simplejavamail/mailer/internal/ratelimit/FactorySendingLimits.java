package org.simplejavamail.mailer.internal.ratelimit;

import lombok.Value;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.config.SendingRateLimit;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import static java.util.Objects.requireNonNull;

/**
 * Factory-local allowance registry, separate from configuration snapshots and transport pools.
 * Named groups deliberately survive participant closure so rebuilding a Mailer cannot reset recent usage.
 */
public final class FactorySendingLimits {

	private final Map<Object, Registration> allowanceRegistrations = new HashMap<>();
	private final LongSupplier clock;

	public FactorySendingLimits() {
		this(System::nanoTime);
	}

	/** Internal deterministic-clock seam for exercising the registry through real send paths. */
	FactorySendingLimits(final LongSupplier clock) {
		this.clock = requireNonNull(clock, "clock");
	}

	/**
	 * Publishes the rules only after initialization succeeds. Concurrent builders can share a provisional group, but a failed
	 * build cannot pin its rules forever. Initialization runs outside the registry monitor, including optional pool setup.
	 */
	@NotNull
	public MailSendRateLimiter register(@Nullable final String group, @Nullable final SendingRateLimit messages,
			@Nullable final SendingRateLimit recipients, final boolean allowBursts, final Consumer<MailSendRateLimiter> initialize) {
		return register(null, group, messages, recipients, allowBursts, initialize);
	}

	/**
	 * A captured named SMTP definition retains its private allowance even when its privately owned Mailer is rebuilt.
	 *
	 * @param definitionIdentity Stable factory-local identity used only when no named allowance group is configured.
	 */
	@NotNull
	public MailSendRateLimiter register(@Nullable final Object definitionIdentity, @Nullable final String group, @Nullable final SendingRateLimit messages,
			@Nullable final SendingRateLimit recipients, final boolean allowBursts, final Consumer<MailSendRateLimiter> initialize) {
		try (Reservation reservation = reserve(definitionIdentity, group, messages, recipients, allowBursts)) {
			initialize.accept(reservation.limiter());
			reservation.commit();
			return reservation.limiter();
		}
	}

	/** Reserve compatible rules before construction; only a complete owned route commits the reservation. */
	@NotNull
	public Reservation reserve(@Nullable final Object definitionIdentity, @Nullable final String group, @Nullable final SendingRateLimit messages,
			@Nullable final SendingRateLimit recipients, final boolean allowBursts) {
		if (group != null && group.isBlank()) {
			throw new IllegalArgumentException("A rate-limit group needs a nonblank name; reset the group to use a private allowance.");
		}
		final Rules rules = new Rules(messages, recipients, allowBursts);
		final Object allowanceIdentity = group == null ? definitionIdentity : group;
		if (allowanceIdentity == null) {
			return new Reservation(null, null, rules.newLimiter(clock));
		}
		final Registration registration = retainProvisionalRegistration(allowanceIdentity, rules);
		return new Reservation(allowanceIdentity, registration, registration.limiter);
	}

	private synchronized Registration retainProvisionalRegistration(final Object allowanceIdentity, final Rules rules) {
		final Registration existing = allowanceRegistrations.get(allowanceIdentity);
		if (existing != null) {
			if (!existing.rules.equals(rules)) {
				throw new IllegalArgumentException("Mailers sharing a rate-limit group must use the same message limit, recipient limit and burst setting."
						+ " Match those settings or use different group names for independent allowances.");
			}
			existing.pendingBuilders++;
			return existing;
		}
		final Registration registration = new Registration(rules, rules.newLimiter(clock));
		allowanceRegistrations.put(allowanceIdentity, registration);
		return registration;
	}

	private synchronized void finishRegistration(final Object allowanceIdentity, final Registration registration, final boolean initialized) {
		registration.established |= initialized;
		registration.pendingBuilders--;
		if (!registration.established && registration.pendingBuilders == 0) {
			allowanceRegistrations.remove(allowanceIdentity, registration);
		}
	}

	/** One attempt's claim on rules; completion is idempotent and invokes no initialization callbacks under the registry monitor. */
	public final class Reservation implements AutoCloseable {
		@Nullable private final Object identity;
		@Nullable private final Registration registration;
		private final MailSendRateLimiter limiter;
		private boolean completed;

		private Reservation(@Nullable final Object identity, @Nullable final Registration registration, final MailSendRateLimiter limiter) {
			this.identity = identity;
			this.registration = registration;
			this.limiter = limiter;
		}

		@NotNull public MailSendRateLimiter limiter() { return limiter; }
		public void commit() { finish(true); }
		public void abort() { finish(false); }
		@Override public void close() { abort(); }

		private void finish(final boolean success) {
			synchronized (FactorySendingLimits.this) {
				if (completed) { return; }
				completed = true;
				if (registration != null) { finishRegistration(identity, registration, success); }
			}
		}
	}

	@Value
	private static class Rules {
		@Nullable SendingRateLimit messages;
		@Nullable SendingRateLimit recipients;
		boolean allowBursts;

		MailSendRateLimiter newLimiter(final LongSupplier clock) {
			return new MailSendRateLimiter(messages, recipients, allowBursts, clock);
		}
	}

	private static final class Registration {
		private final Rules rules;
		private final MailSendRateLimiter limiter;
		private int pendingBuilders = 1;
		private boolean established;

		private Registration(final Rules rules, final MailSendRateLimiter limiter) {
			this.rules = rules;
			this.limiter = limiter;
		}
	}
}
