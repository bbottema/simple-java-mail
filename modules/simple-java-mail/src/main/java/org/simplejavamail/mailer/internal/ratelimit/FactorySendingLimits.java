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

	private final Map<String, Registration> groups = new HashMap<>();
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
		if (group != null && group.isBlank()) {
			throw new IllegalArgumentException("A rate-limit group needs a nonblank name; reset the group to use a private allowance.");
		}
		final Rules rules = new Rules(messages, recipients, allowBursts);
		if (group == null) {
			final MailSendRateLimiter limiter = rules.newLimiter(clock);
			initialize.accept(limiter);
			return limiter;
		}
		final Registration registration = retainProvisionalGroup(group, rules);
		boolean initialized = false;
		try {
			initialize.accept(registration.limiter);
			initialized = true;
			return registration.limiter;
		} finally {
			finishRegistration(group, registration, initialized);
		}
	}

	private synchronized Registration retainProvisionalGroup(final String group, final Rules rules) {
		final Registration existing = groups.get(group);
		if (existing != null) {
			if (!existing.rules.equals(rules)) {
				throw new IllegalArgumentException("Mailers sharing a rate-limit group must use the same message limit, recipient limit and burst setting."
						+ " Match those settings or use different group names for independent allowances.");
			}
			existing.pendingBuilders++;
			return existing;
		}
		final Registration registration = new Registration(rules, rules.newLimiter(clock));
		groups.put(group, registration);
		return registration;
	}

	private synchronized void finishRegistration(final String group, final Registration registration, final boolean initialized) {
		registration.established |= initialized;
		registration.pendingBuilders--;
		if (!registration.established && registration.pendingBuilders == 0) {
			groups.remove(group);
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
