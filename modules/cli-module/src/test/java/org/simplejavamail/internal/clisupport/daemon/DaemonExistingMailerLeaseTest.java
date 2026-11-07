package org.simplejavamail.internal.clisupport.daemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.clisupport.CliMailerProfile;
import org.simplejavamail.internal.clisupport.MailerProvider;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@Timeout(20)
class DaemonExistingMailerLeaseTest {
    @Test
    void missingProfilesDoNotCreateOrEvictSendingEntries() throws Exception {
        final Mailer cached = mock(Mailer.class);
        final CliMailerProfile profile = profile("cached");
        try (DaemonMailerRegistry registry = new DaemonMailerRegistry(1, Duration.ofMinutes(1))) {
            assertThat(registry.tryAcquireExisting(profile)).isEmpty();
            assertThat(registry.entryCount()).isZero();
            try (MailerProvider.Lease ignored = registry.acquire(profile, () -> cached)) { }
            assertThat(registry.tryAcquireExisting(profile("other"))).isEmpty();
            assertThat(registry.entryCount()).isEqualTo(1);
            assertThat(registry.activeLeaseCount()).isZero();
            try (MailerProvider.Lease lease = registry.tryAcquireExisting(profile).orElseThrow()) {
                assertThat(lease.mailer()).isSameAs(cached);
                assertThat(registry.activeLeaseCount()).isEqualTo(1);
            }
        }
        verify(cached, times(1)).close();
    }

    @Test
    void pendingConstructionIsAMissWithoutWaitingForItsFactory() throws Exception {
        final Mailer cached = mock(Mailer.class);
        final CliMailerProfile profile = profile("pending");
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch finish = new CountDownLatch(1);
        final ExecutorService threads = Executors.newFixedThreadPool(2);
        try (DaemonMailerRegistry registry = new DaemonMailerRegistry(1, Duration.ofMinutes(1))) {
            final Future<?> constructing = threads.submit(() -> {
                try (MailerProvider.Lease ignored = registry.acquire(profile, () -> {
                    entered.countDown();
                    await(finish);
                    return cached;
                })) { }
            });
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                final Future<Optional<MailerProvider.Lease>> lookup = threads.submit(() -> registry.tryAcquireExisting(profile));
                assertThat(lookup.get(2, TimeUnit.SECONDS)).isEmpty();
                assertThat(constructing.isDone()).isFalse();
            } finally {
                finish.countDown();
                constructing.get(5, TimeUnit.SECONDS);
            }
            try (MailerProvider.Lease lease = registry.tryAcquireExisting(profile).orElseThrow()) {
                assertThat(lease.mailer()).isSameAs(cached);
            }
        } finally {
            finish.countDown();
            threads.shutdownNow();
        }
        verify(cached, times(1)).close();
    }

    @Test
    void anExistingLeaseProtectsItsMailerFromCapacityRetirement() throws Exception {
        final Mailer first = mock(Mailer.class);
        final Mailer replacement = mock(Mailer.class);
        final CliMailerProfile profile = profile("first");
        final AtomicInteger replacementFactories = new AtomicInteger();
        try (DaemonMailerRegistry registry = new DaemonMailerRegistry(1, Duration.ofMinutes(1))) {
            try (MailerProvider.Lease ignored = registry.acquire(profile, () -> first)) { }
            try (MailerProvider.Lease existing = registry.tryAcquireExisting(profile).orElseThrow()) {
                assertThat(existing.mailer()).isSameAs(first);
                assertThatThrownBy(() -> registry.acquire(profile("replacement"), () -> {
                    replacementFactories.incrementAndGet();
                    return replacement;
                })).isInstanceOf(DaemonOverloadedException.class);
                assertThat(replacementFactories).hasValue(0);
            }
            try (MailerProvider.Lease lease = registry.acquire(profile("replacement"), () -> replacement)) {
                assertThat(lease.mailer()).isSameAs(replacement);
            }
            assertThat(registry.tryAcquireExisting(profile)).isEmpty();
        }
        verify(first, times(1)).close();
        verify(replacement, times(1)).close();
    }

    @Test
    void failedAndClosedEntriesCannotSupplyAnExistingLease() {
        final DaemonMailerRegistry registry = new DaemonMailerRegistry(1, Duration.ofMinutes(1));
        final CliMailerProfile profile = profile("failed");
        final IllegalStateException failure = new IllegalStateException("factory unavailable");
        try {
            assertThatThrownBy(() -> registry.acquire(profile, () -> { throw failure; })).isSameAs(failure);
            assertThat(registry.tryAcquireExisting(profile)).isEmpty();
            assertThat(registry.entryCount()).isZero();
        } finally {
            registry.close();
        }
        assertThatThrownBy(() -> registry.tryAcquireExisting(profile)).isInstanceOf(DaemonOverloadedException.class)
                .hasMessageContaining("shutting down");
    }

    private static CliMailerProfile profile(final String host) {
        return CliMailerProfile.create(ConfigLoader.builder().withMap(java.util.Map.of("simplejavamail.smtp.host", host)).load(),
                List.of(), new byte[32]);
    }

    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) { throw new AssertionError("Construction was not released"); }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
