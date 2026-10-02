package org.simplejavamail.api.mailer.config;

import lombok.Value;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.time.Duration;

import static java.util.Objects.requireNonNull;

/**
 * A supplied maximum count over a rolling period, used for either messages or recipients. It contains no counters or resolved defaults.
 * Local submission attempts are counted, not final delivery or the SMTP service's account-wide quota.
 */
@Value
public class SendingRateLimit implements Serializable {

	private static final long serialVersionUID = 1L;

	/** Maximum message or recipient count in the period. */
	int count;
	/** Rolling period, independent of wall-clock minute boundaries. */
	@NotNull Duration period;

	/**
	 * @param count Positive maximum count; there is no implicit limit.
	 * @param period Positive period representable in nanoseconds.
	 * @throws IllegalArgumentException If the count or period is not positive, or the period is too large.
	 */
	public SendingRateLimit(final int count, @NotNull final Duration period) {
		if (count <= 0) {
			throw new IllegalArgumentException("A sending limit needs a positive message or recipient count.");
		}
		requireNonNull(period, "period");
		if (period.isNegative() || period.isZero()) {
			throw new IllegalArgumentException("A sending limit needs a positive period, for example Duration.ofMinutes(1).");
		}
		try {
			period.toNanos();
		} catch (ArithmeticException overflow) {
			throw new IllegalArgumentException("The sending-limit period is too large to measure in nanoseconds; choose a shorter period.", overflow);
		}
		this.count = count;
		this.period = period;
	}

	private Object readResolve() {
		return new SendingRateLimit(count, period);
	}
}
