package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.mailer.config.MailSendRetryPolicy;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/** Calculates one bounded delay; it does not schedule work or coordinate different mailboxes. */
final class MailSendRetryDelay {

	private MailSendRetryDelay() { }

	static Duration afterAttempt(final MailSendRetryPolicy policy, final int attempt) {
		return afterAttempt(policy, attempt, () -> ThreadLocalRandom.current().nextDouble());
	}

	static Duration afterAttempt(final MailSendRetryPolicy policy, final int attempt, final DoubleSupplier random) {
		final long maximum = policy.getMaximumRetryDelay() == null ? Long.MAX_VALUE : policy.getMaximumRetryDelay().toNanos();
		final long backedOffNanos = applyBackoff(policy.getRetryDelay().toNanos(), policy.getRetryBackoffMultiplier(), attempt, maximum);
		return Duration.ofNanos(applyJitter(backedOffNanos, policy.getRetryJitter(), maximum, random));
	}

	private static long applyBackoff(long nanos, final int multiplier, final int attempt, final long maximum) {
		for (int index = 1; multiplier > 1 && index < attempt && nanos < maximum; index++) {
			// Check the cap before multiplying so overflow cannot turn a long backoff into a negative or immediate delay.
			nanos = nanos > maximum / multiplier ? maximum : nanos * multiplier;
		}
		return nanos;
	}

	private static long applyJitter(final long nanos, final double jitter, final long maximum, final DoubleSupplier random) {
		if (jitter == 0) { return nanos; }
		final double factor = 1 + jitter * (2 * random.getAsDouble() - 1);
		// Jitter must still respect the cap; rounding must not erase a positive configured wait.
		return Math.max(1, (long) Math.min(maximum, nanos * factor));
	}
}
