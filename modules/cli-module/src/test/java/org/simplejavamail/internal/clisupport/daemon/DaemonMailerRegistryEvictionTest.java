package org.simplejavamail.internal.clisupport.daemon;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.clisupport.CliMailerProfile;
import org.simplejavamail.internal.clisupport.MailerProvider;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class DaemonMailerRegistryEvictionTest {

    @Test
    void pendingOldestProfileDoesNotHideAnotherIdleMailer() throws Exception {
        final Mailer pendingMailer = mock(Mailer.class);
        final Mailer idleMailer = mock(Mailer.class);
        final Mailer replacementMailer = mock(Mailer.class);
        final CliMailerProfile pendingProfile = profile("pending");
        final CountDownLatch constructionEntered = new CountDownLatch(1);
        final CountDownLatch releaseConstruction = new CountDownLatch(1);
        final ExecutorService constructor = Executors.newSingleThreadExecutor();
        final DaemonMailerRegistry registry = new DaemonMailerRegistry(2, Duration.ZERO);
        try {
            final Future<Mailer> pending = constructor.submit(() -> {
                try (MailerProvider.Lease lease = registry.acquire(pendingProfile, () -> {
                    constructionEntered.countDown();
                    await(releaseConstruction);
                    return pendingMailer;
                })) {
                    return lease.mailer();
                }
            });
            assertThat(constructionEntered.await(5, TimeUnit.SECONDS)).isTrue();
            register(registry, profile("idle"), idleMailer);
            assertThat(registry.tryAcquireExisting(pendingProfile)).isEmpty();

            try (MailerProvider.Lease replacement = registry.acquire(profile("replacement"), () -> replacementMailer)) {
                assertThat(replacement.mailer()).isSameAs(replacementMailer);
                assertThat(registry.entryCount()).isEqualTo(2);
                verify(idleMailer, times(1)).close();
                verify(pendingMailer, never()).close();
            }

            releaseConstruction.countDown();
            assertThat(pending.get(5, TimeUnit.SECONDS)).isSameAs(pendingMailer);
            try (MailerProvider.Lease ready = registry.tryAcquireExisting(pendingProfile).orElseThrow()) {
                assertThat(ready.mailer()).isSameAs(pendingMailer);
            }
        } finally {
            releaseConstruction.countDown();
            constructor.shutdownNow();
            assertThat(constructor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            registry.close();
        }
        verify(pendingMailer, times(1)).close();
        verify(idleMailer, times(1)).close();
        verify(replacementMailer, times(1)).close();
    }

    @Test
    void newlyLeasedOldestCandidateDoesNotHideAnotherIdleMailer() throws Exception {
        final Mailer oldestMailer = mock(Mailer.class);
        final Mailer idleMailer = mock(Mailer.class);
        final Mailer replacementMailer = mock(Mailer.class);
        final CliMailerProfile oldestProfile = profile("oldest");
        final AtomicReference<MailerProvider.Lease> interveningLease = new AtomicReference<>();
        final DaemonMailerRegistry registry = new DaemonMailerRegistry(2, Duration.ZERO);
        try {
            register(registry, oldestProfile, oldestMailer);
            register(registry, profile("idle"), idleMailer);
            afterCandidateEnumeration(registry, () -> interveningLease.set(registry.tryAcquireExisting(oldestProfile).orElseThrow()));

            try (MailerProvider.Lease replacement = registry.acquire(profile("replacement"), () -> replacementMailer)) {
                assertThat(replacement.mailer()).isSameAs(replacementMailer);
                assertThat(interveningLease.get().mailer()).isSameAs(oldestMailer);
                assertThat(registry.entryCount()).isEqualTo(2);
                verify(oldestMailer, never()).close();
                verify(idleMailer, times(1)).close();
            }
        } finally {
            if (interveningLease.get() != null) interveningLease.get().close();
            registry.close();
        }
        verify(oldestMailer, times(1)).close();
        verify(idleMailer, times(1)).close();
        verify(replacementMailer, times(1)).close();
    }

    private static void register(final DaemonMailerRegistry registry, final CliMailerProfile profile, final Mailer mailer) {
        try (MailerProvider.Lease ignored = registry.acquire(profile, () -> mailer)) { }
    }

    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Construction was not released");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static CliMailerProfile profile(final String suffix) {
        final Properties properties = new Properties();
        properties.setProperty(ConfigLoader.Property.SMTP_HOST.key(), "smtp-" + suffix + ".example.test");
        return CliMailerProfile.create(ConfigLoader.builder().withProperties(properties).load(), List.of(), new byte[32]);
    }

    /** Models a compatible command taking a real lease after an eviction scan, before a candidate's retirement check. */
    @SuppressWarnings("unchecked")
    private static void afterCandidateEnumeration(final DaemonMailerRegistry registry, final Runnable interveningCommand) throws Exception {
        final Field entries = DaemonMailerRegistry.class.getDeclaredField("entries");
        entries.setAccessible(true);
        entries.set(registry, new InterleavingEntries<>((Map<CliMailerProfile, Object>) entries.get(registry), interveningCommand));
    }

    private static final class InterleavingEntries<K, V> extends ConcurrentHashMap<K, V> {
        private static final long serialVersionUID = 1L;
        private final Runnable interveningCommand;
        private final AtomicBoolean armed = new AtomicBoolean(true);

        private InterleavingEntries(final Map<K, V> entries, final Runnable interveningCommand) {
            super(entries);
            this.interveningCommand = interveningCommand;
        }

        @Override
        public Set<Map.Entry<K, V>> entrySet() {
            final Set<Map.Entry<K, V>> entries = super.entrySet();
            return new AbstractSet<>() {
                @Override public Iterator<Map.Entry<K, V>> iterator() { return entries.iterator(); }
                @Override public int size() { return entries.size(); }
                @Override public Spliterator<Map.Entry<K, V>> spliterator() {
                    final Spliterator<Map.Entry<K, V>> source = entries.spliterator();
                    return new Spliterators.AbstractSpliterator<>(source.estimateSize(), source.characteristics()) {
                        @Override public boolean tryAdvance(final Consumer<? super Map.Entry<K, V>> action) {
                            if (source.tryAdvance(action)) return true;
                            if (armed.compareAndSet(true, false)) interveningCommand.run();
                            return false;
                        }
                    };
                }
            };
        }
    }
}
