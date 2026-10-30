package org.simplejavamail.internal.clisupport.daemon;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.clisupport.CliMailerProfile;
import org.simplejavamail.internal.clisupport.MailerProvider;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DaemonMailerRegistryShutdownTest {
    @Test
    void interruptedIdleCloseRemainsOwnedUntilTheActualCleanupCompletes() throws Exception {
        final CloseFixture interrupted = new CloseFixture();
        final CloseFixture replacementMailer = new CloseFixture();
        final CompletableFuture<Void> actualCleanup = new CompletableFuture<>();
        interrupted.closeAction = () -> { throw new InterruptedException("close wait interrupted"); };
        interrupted.cleanup = actualCleanup;
        final ExecutorService eviction = Executors.newSingleThreadExecutor();
        final DaemonMailerRegistry registry = new DaemonMailerRegistry(1, Duration.ZERO);
        try {
            register(registry, "interrupted", interrupted.mailer);
            eviction.submit(() -> register(registry, "replacement", replacementMailer.mailer)).get(5, TimeUnit.SECONDS);
            final CompletableFuture<Void> completion = registry.closeAfterExecutorDrainAsync();
            assertThat(completion).as("Interrupting a close wait does not complete the Mailer's cleanup").isNotDone();
            actualCleanup.complete(null);
            assertThatThrownBy(() -> completion.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(registry.closeFailureCount()).isEqualTo(1);
            assertThat(interrupted.closeCalls).hasValue(1);
            assertThat(interrupted.asyncCalls).hasValue(1);
            assertThat(replacementMailer.closeCalls).hasValue(1);
        } finally {
            actualCleanup.complete(null);
            eviction.shutdownNow();
            try { registry.closeAfterExecutorDrainAsync().get(5, TimeUnit.SECONDS); }
            catch (ExecutionException expectedInterruptedClose) { /* The failed wait remains observable after real cleanup finishes. */ }
        }
    }

    @Test
    void everyFrozenEntryCanStartClosingEvenWhenMoreThanFourClosesBlock() throws Exception {
        final CountDownLatch allEntered = new CountDownLatch(7);
        final CountDownLatch releaseCloses = new CountDownLatch(1);
        final DaemonMailerRegistry registry = new DaemonMailerRegistry(7, Duration.ZERO);
        try {
            for (int index = 0; index < 7; index++) {
                final CloseFixture mailer = new CloseFixture();
                final boolean blocked = index < 6;
                mailer.closeAction = () -> {
                    allEntered.countDown();
                    if (blocked) { releaseCloses.await(); }
                    return null;
                };
                register(registry, "fanout-" + index, mailer.mailer);
            }
            final CompletableFuture<Void> completion = registry.closeAfterExecutorDrainAsync();
            assertThat(allEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(completion).isNotDone();
            releaseCloses.countDown();
            completion.get(5, TimeUnit.SECONDS);
        } finally {
            releaseCloses.countDown();
            registry.closeAfterExecutorDrainAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void oneBlockedCloseCannotBlockOtherMailersOrTheShutdownController() throws Exception {
        final CloseFixture blocked = new CloseFixture();
        final CloseFixture healthy = new CloseFixture();
        final CountDownLatch closeEntered = new CountDownLatch(1);
        final CountDownLatch releaseClose = new CountDownLatch(1);
        final CountDownLatch healthyClosed = new CountDownLatch(1);
        blocked.closeAction = () -> { closeEntered.countDown(); releaseClose.await(); return null; };
        healthy.closeAction = () -> { healthyClosed.countDown(); return null; };
        final DaemonMailerRegistry registry = new DaemonMailerRegistry(2, Duration.ZERO);
        try {
            register(registry, "blocked", blocked.mailer);
            register(registry, "healthy", healthy.mailer);
            final CompletableFuture<Void> completion = registry.closeAfterExecutorDrainAsync();
            assertThat(closeEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(healthyClosed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(completion).isNotDone();
            assertThat(registry.closeAfterExecutorDrainAsync()).isSameAs(completion);
            assertThatThrownBy(() -> registry.acquire(profile("new"), () -> healthy.mailer)).isInstanceOf(DaemonOverloadedException.class);
            releaseClose.countDown();
            completion.get(5, TimeUnit.SECONDS);
            assertThat(blocked.closeCalls).hasValue(1);
            assertThat(healthy.closeCalls).hasValue(1);
        } finally {
            releaseClose.countDown();
            registry.closeAfterExecutorDrainAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void pendingConstructionAlreadyOwnsItsCloseBarrierWhenShutdownStarts() throws Exception {
        final CloseFixture mailer = new CloseFixture();
        final CountDownLatch factoryEntered = new CountDownLatch(1);
        final CountDownLatch releaseFactory = new CountDownLatch(1);
        final CountDownLatch closeEntered = new CountDownLatch(1);
        final CountDownLatch releaseClose = new CountDownLatch(1);
        mailer.closeAction = () -> { closeEntered.countDown(); releaseClose.await(); return null; };
        final ExecutorService factory = Executors.newSingleThreadExecutor();
        final DaemonMailerRegistry registry = new DaemonMailerRegistry(1, Duration.ZERO);
        try {
            final Future<?> acquisition = factory.submit(() -> registry.acquire(profile("late"), () -> {
                factoryEntered.countDown();
                await(releaseFactory);
                return mailer.mailer;
            }));
            assertThat(factoryEntered.await(5, TimeUnit.SECONDS)).isTrue();
            final CompletableFuture<Void> completion = registry.closeAfterExecutorDrainAsync();
            assertThat(completion).isNotDone();
            releaseFactory.countDown();
            assertThat(closeEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> acquisition.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(DaemonOverloadedException.class);
            assertThat(completion).isNotDone();
            releaseClose.countDown();
            completion.get(5, TimeUnit.SECONDS);
            assertThat(mailer.closeCalls).hasValue(1);
        } finally {
            releaseFactory.countDown();
            releaseClose.countDown();
            factory.shutdownNow();
            registry.closeAfterExecutorDrainAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void capacityRetirementRemovedFromLookupStillParticipatesInShutdown() throws Exception {
        final CloseFixture mailer = new CloseFixture();
        final CountDownLatch closeEntered = new CountDownLatch(1);
        final CountDownLatch releaseClose = new CountDownLatch(1);
        mailer.closeAction = () -> { closeEntered.countDown(); releaseClose.await(); return null; };
        final AtomicInteger replacementConstructions = new AtomicInteger();
        final ExecutorService eviction = Executors.newSingleThreadExecutor();
        final DaemonMailerRegistry registry = new DaemonMailerRegistry(1, Duration.ZERO);
        try {
            register(registry, "old", mailer.mailer);
            final Future<?> replacement = eviction.submit(() -> registry.acquire(profile("replacement"), () -> {
                replacementConstructions.incrementAndGet();
                return new CloseFixture().mailer;
            }));
            assertThat(closeEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(registry.entryCount()).isZero();
            final CompletableFuture<Void> completion = registry.closeAfterExecutorDrainAsync();
            assertThat(completion).isNotDone();
            releaseClose.countDown();
            completion.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> replacement.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(DaemonOverloadedException.class);
            assertThat(replacementConstructions).hasValue(0);
            assertThat(mailer.closeCalls).hasValue(1);
        } finally {
            releaseClose.countDown();
            eviction.shutdownNow();
            registry.closeAfterExecutorDrainAsync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void closeFailureRemainsObservableWhileOtherMailersStillClose() throws Exception {
        final CloseFixture failed = new CloseFixture();
        final CloseFixture healthy = new CloseFixture();
        failed.closeAction = () -> { throw new ExecutionException(new IOException("close failed")); };
        final DaemonMailerRegistry registry = new DaemonMailerRegistry(2, Duration.ZERO);
        register(registry, "failed", failed.mailer);
        register(registry, "healthy", healthy.mailer);
        assertThatThrownBy(() -> registry.closeAfterExecutorDrainAsync().get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(ExecutionException.class);
        assertThat(registry.closeFailureCount()).isEqualTo(1);
        assertThat(healthy.closeCalls).hasValue(1);
    }

    private static void register(final DaemonMailerRegistry registry, final String name, final Mailer mailer) {
        try (MailerProvider.Lease ignored = registry.acquire(profile(name), () -> mailer)) { }
    }

    private static CliMailerProfile profile(final String name) {
        final Properties properties = new Properties();
        properties.setProperty(ConfigLoader.Property.SMTP_HOST.key(), "smtp-" + name + ".example.test");
        return CliMailerProfile.create(ConfigLoader.builder().withProperties(properties).load(), List.of(), new byte[32]);
    }

    private static void await(final CountDownLatch latch) {
        try { latch.await(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }

    /** Models only Mailer ownership and its detached cleanup observation, without a JVM instrumentation agent. */
    private static final class CloseFixture {
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final AtomicInteger asyncCalls = new AtomicInteger();
        private Callable<Void> closeAction = () -> null;
        private CompletableFuture<Void> cleanup = CompletableFuture.completedFuture(null);
        private final Mailer mailer = (Mailer) Proxy.newProxyInstance(Mailer.class.getClassLoader(), new Class<?>[]{Mailer.class},
                (proxy, method, arguments) -> {
                    return switch (method.getName()) {
                        case "close" -> { closeCalls.incrementAndGet(); yield closeAction.call(); }
                        case "closeAsync" -> { asyncCalls.incrementAndGet(); yield cleanup.copy(); }
                        case "toString" -> "Daemon Mailer close fixture";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == arguments[0];
                        default -> throw new AssertionError("Unexpected Mailer call: " + method.getName());
                    };
                });
    }

}
