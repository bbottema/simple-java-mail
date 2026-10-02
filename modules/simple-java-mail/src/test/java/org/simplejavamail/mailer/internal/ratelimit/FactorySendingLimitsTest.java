package org.simplejavamail.mailer.internal.ratelimit;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.config.SendingRateLimit;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FactorySendingLimitsTest {

	private final SendingRateLimit messages = new SendingRateLimit(30, Duration.ofMinutes(1));

	@Test
	void failedInitializationReleasesRulesButCannotRemoveAnEstablishedGroup() {
		final FactorySendingLimits registry = new FactorySendingLimits();
		final RuntimeException failure = new IllegalStateException("pool initialization failed");
		assertThatThrownBy(() -> registry.register("account", messages, null, false, limiter -> { throw failure; })).isSameAs(failure);
		final MailSendRateLimiter established = registry.register("account", null, messages, true, unused -> { });
		assertThatThrownBy(() -> registry.register("account", null, messages, true, limiter -> { throw failure; })).isSameAs(failure);
		assertThat(registry.register("account", null, messages, true, unused -> { })).isSameAs(established);
		assertThatThrownBy(() -> registry.register("account", messages, null, false, unused -> { })).hasMessageContaining("same message limit");
	}

	@Test
	void concurrentSuccessfulParticipantSurvivesAnotherBuildersRollback() throws Exception {
		verifyConcurrentInitialization(true);
	}

	@Test
	void concurrentFailedParticipantsLeaveNoRegisteredRules() throws Exception {
		verifyConcurrentInitialization(false);
	}

	private void verifyConcurrentInitialization(final boolean anotherBuildSucceeds) throws Exception {
		final FactorySendingLimits registry = new FactorySendingLimits();
		final CountDownLatch initializing = new CountDownLatch(1);
		final CountDownLatch finish = new CountDownLatch(1);
		final RuntimeException failure = new IllegalStateException("pool initialization failed");
		final ExecutorService worker = Executors.newSingleThreadExecutor();
		try {
			final Future<?> pending = worker.submit(() -> registry.register("account", messages, null, false, limiter -> {
				initializing.countDown();
				try {
					assertThat(finish.await(5, SECONDS)).isTrue();
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw new AssertionError(interrupted);
				}
				throw failure;
			}));
			assertThat(initializing.await(5, SECONDS)).isTrue();
			// This would deadlock if initialization ran while holding the registry's monitor.
			final MailSendRateLimiter established;
			if (anotherBuildSucceeds) {
				established = registry.register("account", messages, null, false, unused -> { });
			} else {
				established = null;
				assertThatThrownBy(() -> registry.register("account", messages, null, false, limiter -> { throw failure; })).isSameAs(failure);
			}
			finish.countDown();
			assertThatThrownBy(() -> pending.get(5, SECONDS)).hasCause(failure);
			if (anotherBuildSucceeds) {
				assertThat(registry.register("account", messages, null, false, unused -> { })).isSameAs(established);
				assertThatThrownBy(() -> registry.register("account", null, messages, true, unused -> { })).hasMessageContaining("same message limit");
			} else {
				assertThat(registry.register("account", null, messages, true, unused -> { })).isNotNull();
			}
		} finally {
			finish.countDown();
			worker.shutdownNow();
			assertThat(worker.awaitTermination(5, SECONDS)).isTrue();
		}
	}

	@Test
	void namedGroupsShareByFactoryIdentityRatherThanMatchingConfiguration() {
		final FactorySendingLimits first = new FactorySendingLimits();
		final FactorySendingLimits second = new FactorySendingLimits();
		final MailSendRateLimiter limiter = first.register("company-account", messages, null, false, unused -> { });
		assertThat(first.register("company-account", new SendingRateLimit(30, Duration.ofSeconds(60)), null, false, unused -> { })).isSameAs(limiter);
		assertThat(first.register("other-account", messages, null, false, unused -> { })).isNotSameAs(limiter);
		assertThat(second.register("company-account", messages, null, false, unused -> { })).isNotSameAs(limiter);
	}

	@Test
	void privateRegistrationsNeverShareAndBlankNamesAreNotPrivate() {
		final FactorySendingLimits registry = new FactorySendingLimits();
		assertThat(registry.register(null, messages, null, true, unused -> { })).isNotSameAs(registry.register(null, messages, null, true, unused -> { }));
		assertThatThrownBy(() -> registry.register("  ", messages, null, true, unused -> { })).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void groupMembersMustAgreeIncludingExplicitlyDisabledRules() {
		final FactorySendingLimits registry = new FactorySendingLimits();
		registry.register("company-account", messages, null, false, unused -> { });
		assertThatThrownBy(() -> registry.register("company-account", messages, null, true, unused -> { })).hasMessageContaining("same message limit");
		assertThatThrownBy(() -> registry.register("company-account", null, null, false, unused -> { })).hasMessageContaining("same message limit");
		assertThatThrownBy(() -> registry.register("company-account", messages, messages, false, unused -> { })).hasMessageContaining("same message limit");
		assertThatThrownBy(() -> registry.register("company-account", new SendingRateLimit(31, Duration.ofMinutes(1)), null, false, unused -> { }))
				.hasMessageContaining("same message limit");
	}
}
