package org.simplejavamail.mailer.internal.ratelimit;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.config.SendingRateLimit;

import java.math.BigInteger;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SendingRateWindowTest {

	@Test
	void countsWeightedOccurrencesOverARollingWindowIncludingItsExactBoundary() {
		final SendingRateWindow window = window(10, 100);
		window.charge(4, 20);
		window.charge(6, 30);
		assertThat(window.remainingWaitNanos(4, 119, true)).isEqualTo(1);
		assertThat(window.remainingWaitNanos(4, 120, true)).isZero();
		assertThat(window.remainingWaitNanos(5, 120, true)).isEqualTo(10);
		assertThat(window.remainingWaitNanos(10, 130, true)).isZero();
	}

	@Test
	void spreadsByPreviousRecipientCostAndDoesNotAccumulateIdleCredit() {
		final SendingRateWindow window = window(100, 60_000_000_000L);
		assertThat(window.remainingWaitNanos(10, 0, false)).isZero();
		window.charge(10, 0);
		assertThat(window.remainingWaitNanos(1, 0, false)).isEqualTo(6_000_000_000L);
		assertThat(window.remainingWaitNanos(1, 6_000_000_000L, false)).isZero();
		window.charge(1, 600_000_000_000L);
		assertThat(window.remainingWaitNanos(1, 600_000_000_000L, false)).isEqualTo(600_000_000L);
	}

	@Test
	void spreadingDoesNotReplaceTheRollingCeiling() {
		final SendingRateWindow window = window(10, 100);
		window.charge(6, 0);
		assertThat(window.remainingWaitNanos(6, 60, false)).isEqualTo(40);
	}

	@Test
	void fractionalNanosecondsRoundUpWithoutOverflowingLargePeriodsOrCosts() {
		final SendingRateWindow thirds = window(3, 10);
		thirds.charge(1, 0);
		assertThat(thirds.remainingWaitNanos(1, 0, false)).isEqualTo(4);
		final int count = Integer.MAX_VALUE;
		final int cost = count - 1;
		final SendingRateWindow large = window(count, Long.MAX_VALUE);
		large.charge(cost, 0);
		final BigInteger numerator = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(cost));
		final long expected = numerator.add(BigInteger.valueOf(count - 1)).divide(BigInteger.valueOf(count)).longValueExact();
		assertThat(large.remainingWaitNanos(1, 0, false)).isEqualTo(expected);
	}

	@Test
	void elapsedSubtractionSurvivesMonotonicCounterWrap() {
		final SendingRateWindow window = window(1, 100);
		window.charge(1, Long.MAX_VALUE - 49);
		assertThat(window.remainingWaitNanos(1, Long.MIN_VALUE + 49, false)).isEqualTo(1);
		assertThat(window.remainingWaitNanos(1, Long.MIN_VALUE + 50, false)).isZero();
	}

	@Test
	void unusedCapacityCanBurstButEveryAttemptStillCounts() {
		final SendingRateWindow window = window(3, 100);
		for (int submission = 0; submission < 3; submission++) {
			assertThat(window.remainingWaitNanos(1, 0, true)).isZero();
			window.charge(1, 0);
		}
		assertThat(window.remainingWaitNanos(1, 0, true)).isEqualTo(100);
	}

	private static SendingRateWindow window(final int count, final long period) {
		return new SendingRateWindow(new SendingRateLimit(count, Duration.ofNanos(period)));
	}
}
