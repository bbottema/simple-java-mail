package org.simplejavamail.internal.clisupport.daemon;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.clisupport.CliMailerProfile;
import org.simplejavamail.internal.clisupport.MailerProvider;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class DaemonMailerRegistryTest {
	@Test
	void compatibleRequestsReuseOneMailerAndShutdownClosesItOnce() throws Exception {
		final Mailer mailer = mock(Mailer.class);
		final AtomicInteger constructions = new AtomicInteger();
		final CliMailerProfile profile = CliMailerProfile.create(ConfigLoader.builder().load(), List.of(), new byte[32]);
		final DaemonMailerRegistry registry = new DaemonMailerRegistry(2, Duration.ofMinutes(1));
		try {
			try (MailerProvider.Lease first = registry.acquire(profile, () -> {
				constructions.incrementAndGet();
				return mailer;
			}); MailerProvider.Lease second = registry.acquire(profile, () -> {
				constructions.incrementAndGet();
				return mailer;
			})) {
				assertThat(first.mailer()).isSameAs(second.mailer());
				assertThat(registry.activeLeaseCount()).isEqualTo(2);
			}
			assertThat(constructions).hasValue(1);
			assertThat(registry.entryCount()).isEqualTo(1);
		} finally {
			registry.close();
		}
		verify(mailer, times(1)).close();
	}

	@Test
	void constructionThatFinishesAfterShutdownIsRetiredAndCannotPublish() throws Exception {
		final Mailer mailer = mock(Mailer.class);
		final CliMailerProfile profile = CliMailerProfile.create(ConfigLoader.builder().load(), List.of(), new byte[32]);
		final DaemonMailerRegistry registry = new DaemonMailerRegistry(1, Duration.ofMinutes(1));
		final CountDownLatch factoryEntered = new CountDownLatch(1);
		final CountDownLatch releaseFactory = new CountDownLatch(1);
		final ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			final Future<?> acquisition = executor.submit(() -> {
				try (MailerProvider.Lease ignored = registry.acquire(profile, () -> {
					factoryEntered.countDown();
					try {
						releaseFactory.await();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new IllegalStateException(e);
					}
					return mailer;
				})) {
					// Closing the registry while construction is blocked must prevent this lease from publishing.
				}
			});
			assertThat(factoryEntered.await(5, TimeUnit.SECONDS)).isTrue();
			registry.close();
			releaseFactory.countDown();
			assertThatThrownBy(() -> acquisition.get(5, TimeUnit.SECONDS))
					.isInstanceOf(ExecutionException.class)
					.hasCauseInstanceOf(DaemonOverloadedException.class);
		} finally {
			releaseFactory.countDown();
			registry.close();
			executor.shutdownNow();
		}
		verify(mailer, times(1)).close();
	}

	@Test
	void concurrentFirstUsePublishesExactlyOneMailer() throws Exception {
		final Mailer mailer = mock(Mailer.class);
		final AtomicInteger constructions = new AtomicInteger();
		final CliMailerProfile profile = profile("shared");
		final DaemonMailerRegistry registry = new DaemonMailerRegistry(1, Duration.ofMinutes(1));
		final ExecutorService executor = Executors.newFixedThreadPool(8);
		final CountDownLatch start = new CountDownLatch(1);
		try {
			final List<Future<Mailer>> acquisitions = IntStream.range(0, 24)
					.mapToObj(ignored -> executor.submit(() -> {
						start.await();
						try (MailerProvider.Lease lease = registry.acquire(profile, () -> {
							constructions.incrementAndGet();
							return mailer;
						})) {
							return lease.mailer();
						}
					}))
					.toList();
			start.countDown();
			for (Future<Mailer> acquisition : acquisitions) {
				assertThat(acquisition.get(5, TimeUnit.SECONDS)).isSameAs(mailer);
			}
			assertThat(constructions).hasValue(1);
		} finally {
			start.countDown();
			executor.shutdownNow();
			registry.close();
		}
		verify(mailer, times(1)).close();
	}

	@Test
	void capacityEvictsOnlyIdleProfilesAndRejectsWhenEveryEntryIsActive() throws Exception {
		final Mailer firstMailer = mock(Mailer.class);
		final Mailer secondMailer = mock(Mailer.class);
		final DaemonMailerRegistry registry = new DaemonMailerRegistry(1, Duration.ofMinutes(1));
		try {
			try (MailerProvider.Lease active = registry.acquire(profile("active"), () -> firstMailer)) {
				assertThatThrownBy(() -> registry.acquire(profile("blocked"), () -> secondMailer))
						.isInstanceOf(DaemonOverloadedException.class)
						.hasMessageContaining("limit");
				verify(firstMailer, never()).close();
			}
			try (MailerProvider.Lease replacement = registry.acquire(profile("replacement"), () -> secondMailer)) {
				assertThat(replacement.mailer()).isSameAs(secondMailer);
			}
			verify(firstMailer, times(1)).close();
		} finally {
			registry.close();
		}
		verify(secondMailer, times(1)).close();
	}

	@Test
	void failedConstructionDoesNotPoisonAProfile() throws Exception {
		final Mailer mailer = mock(Mailer.class);
		final CliMailerProfile profile = profile("retry");
		final DaemonMailerRegistry registry = new DaemonMailerRegistry(1, Duration.ofMinutes(1));
		try {
			assertThatThrownBy(() -> registry.acquire(profile, () -> {
				throw new IllegalStateException("synthetic construction failure");
			})).isInstanceOf(IllegalStateException.class);
			try (MailerProvider.Lease lease = registry.acquire(profile, () -> mailer)) {
				assertThat(lease.mailer()).isSameAs(mailer);
			}
		} finally {
			registry.close();
		}
		verify(mailer, times(1)).close();
	}

	@Test
	void postDrainShutdownForceRetiresAStillLeasedMailerAndRejectsNewWork() throws Exception {
		final Mailer mailer = mock(Mailer.class);
		final CliMailerProfile profile = profile("forced-shutdown");
		final DaemonMailerRegistry registry = new DaemonMailerRegistry(1, Duration.ofMinutes(1));
		final MailerProvider.Lease lease = registry.acquire(profile, () -> mailer);

		registry.closeAfterExecutorDrain();

		verify(mailer, times(1)).close();
		assertThatThrownBy(() -> registry.acquire(profile, () -> mailer))
				.isInstanceOf(DaemonOverloadedException.class)
				.hasMessageContaining("shutting down");
		lease.close();
	}

	private static CliMailerProfile profile(final String hostSuffix) {
		final Properties properties = new Properties();
		properties.setProperty(ConfigLoader.Property.SMTP_HOST.key(), "smtp-" + hostSuffix + ".example.test");
		return CliMailerProfile.create(ConfigLoader.builder().withProperties(properties).load(), List.of(), new byte[32]);
	}
}
