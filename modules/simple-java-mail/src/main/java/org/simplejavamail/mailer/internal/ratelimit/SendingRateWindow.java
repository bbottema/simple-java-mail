package org.simplejavamail.mailer.internal.ratelimit;

import org.simplejavamail.api.mailer.config.SendingRateLimit;

import java.util.ArrayDeque;
import java.util.Deque;

/** One weighted rolling window. Its owning limiter serializes access and supplies monotonic timestamps. */
final class SendingRateWindow {

	private final int limit;
	private final long periodNanos;
	private final Deque<Charge> charges = new ArrayDeque<>();
	private long used;
	private long lastChargedAt;
	private long spacingNanos;

	SendingRateWindow(final SendingRateLimit rule) {
		limit = rule.getCount();
		periodNanos = rule.getPeriod().toNanos();
	}

	long remainingWaitNanos(final int cost, final long now, final boolean allowBursts) {
		expireCharges(now);
		final long capacityWait = waitForCapacity(cost, now);
		final long spacingWait = allowBursts || spacingNanos == 0 ? 0 : Math.max(0, spacingNanos - (now - lastChargedAt));
		return Math.max(capacityWait, spacingWait);
	}

	void charge(final int cost, final long now) {
		expireCharges(now);
		if (cost > 0) {
			charges.addLast(new Charge(cost, now));
			used += cost;
		}
		lastChargedAt = now;
		spacingNanos = spacingFor(cost);
	}

	private void expireCharges(final long now) {
		while (!charges.isEmpty() && now - charges.peekFirst().startedAt >= periodNanos) {
			used -= charges.removeFirst().cost;
		}
	}

	private long waitForCapacity(final int cost, final long now) {
		long excess = used + cost - limit;
		if (excess <= 0) {
			return 0;
		}
		for (final Charge charge : charges) {
			excess -= charge.cost;
			if (excess <= 0) {
				return periodNanos - (now - charge.startedAt);
			}
		}
		throw new IllegalStateException("Recipient cost must fit the whole sending allowance before entering its rolling window.");
	}

	private long spacingFor(final int cost) {
		// Divide before multiplying: cost never exceeds limit, so even a near-Long.MAX_VALUE period stays representable.
		// Round fractional nanoseconds upwards rather than starting an attempt before its configured spacing elapsed.
		final long fractionalNumerator = (periodNanos % limit) * cost;
		return (periodNanos / limit) * cost + fractionalNumerator / limit + (fractionalNumerator % limit == 0 ? 0 : 1);
	}

	private static final class Charge {
		private final int cost;
		private final long startedAt;

		private Charge(final int cost, final long startedAt) {
			this.cost = cost;
			this.startedAt = startedAt;
		}
	}
}
