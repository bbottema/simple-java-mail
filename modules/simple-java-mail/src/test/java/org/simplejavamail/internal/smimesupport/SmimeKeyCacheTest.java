package org.simplejavamail.internal.smimesupport;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.internal.smimesupport.model.SmimePreprocessingResult;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.utils.mail.smime.SmimeKey;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class SmimeKeyCacheTest {

    @Test
    void capacityEvictsTheLeastRecentlyUsedConfigurationWithoutRejectingWork() {
        final Fixture fixture = new Fixture();
        final SmimeKey first = fixture.load(1);
        final SmimeKey second = fixture.load(2);
        assertThat(fixture.load(1)).isSameAs(first);

        final SmimeKey third = fixture.load(3);

        assertThat(fixture.load(1)).isSameAs(first);
        assertThat(fixture.load(3)).isSameAs(third);
        assertThat(fixture.load(2)).isNotSameAs(second);
        assertThat(fixture.loads.get(keys(1))).isEqualTo(1);
        assertThat(fixture.loads.get(keys(2))).isEqualTo(2);
    }

    @Test
    void idleMaintenanceReleasesEntriesWithoutAnotherForegroundLookupAndRestartsOnUse() {
        final Fixture fixture = new Fixture();
        final SmimeKey first = fixture.load(1);
        assertThat(fixture.schedulers).hasSize(1);
        final ManualMaintenance originalWorker = fixture.schedulers.get(0);

        fixture.advanceSeconds(10);
        originalWorker.runNext();

        assertThat(originalWorker.isShutdown()).isTrue();
        assertThat(fixture.load(1)).isNotSameAs(first);
        assertThat(fixture.schedulers).hasSize(2);
        assertThat(fixture.schedulers.get(1).isShutdown()).isFalse();
    }

    @Test
    void aResidentLookupRefreshesIdleExpiryWithoutCreatingMoreMaintenanceTasks() {
        final Fixture fixture = new Fixture();
        final SmimeKey first = fixture.load(1);
        fixture.advanceSeconds(9);
        assertThat(fixture.load(1)).isSameAs(first);
        assertThat(fixture.schedulers).hasSize(1);
        final ManualMaintenance worker = fixture.schedulers.get(0);
        assertThat(worker.tasks).hasSize(1);

        fixture.advanceSeconds(1);
        worker.runNext();

        assertThat(worker.isShutdown()).isFalse();
        assertThat(worker.tasks).hasSize(1);
        assertThat(worker.lastDelay).isEqualTo(TimeUnit.SECONDS.toNanos(9));
        fixture.advanceSeconds(9);
        worker.runNext();
        assertThat(worker.isShutdown()).isTrue();
        assertThat(fixture.load(1)).isNotSameAs(first);
    }

    @Test
    void overdueMaintenanceDoesNotAllowAnExpiredEntryToBeReused() {
        final Fixture fixture = new Fixture();
        final SmimeKey first = fixture.load(1);
        fixture.advanceSeconds(11);

        assertThat(fixture.load(1)).isNotSameAs(first);
        assertThat(fixture.loads.get(keys(1))).isEqualTo(2);
    }

    @Test
    void aRetiredWorkerCannotEvictOrCloseTheReplacementGeneration() {
        final Fixture fixture = new Fixture();
        fixture.load(1);
        assertThat(fixture.schedulers).hasSize(1);
        final ManualMaintenance originalWorker = fixture.schedulers.get(0);
        final Runnable oldTask = originalWorker.tasks.removeFirst();
        fixture.advanceSeconds(10);
        oldTask.run();
        final SmimeKey replacement = fixture.load(1);
        assertThat(fixture.schedulers).hasSize(2);
        final ManualMaintenance replacementWorker = fixture.schedulers.get(1);

        oldTask.run();

        assertThat(replacementWorker.isShutdown()).isFalse();
        assertThat(replacementWorker.tasks).hasSize(1);
        assertThat(fixture.load(1)).isSameAs(replacement);
    }

    @Test
    void aSlowStoreLoadDoesNotBlockAnUnrelatedConfiguration() throws Exception {
        final Fixture fixture = new Fixture();
        final CountDownLatch loading = new CountDownLatch(1);
        final CountDownLatch finish = new CountDownLatch(1);
        final SmimeKey slowKey = new SmimeKey(null);
        final ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            final Future<SmimeKey> slow = callers.submit(() -> fixture.cache.get(keys(1), ignored -> {
                loading.countDown();
                await(finish);
                return slowKey;
            }));
            assertThat(loading.await(10, TimeUnit.SECONDS)).isTrue();
            final Future<SmimeKey> unrelated = callers.submit(() -> fixture.load(2));

            assertThat(unrelated.get(10, TimeUnit.SECONDS)).isNotNull();
            finish.countDown();
            assertThat(slow.get(10, TimeUnit.SECONDS)).isSameAs(slowKey);
        } finally {
            finish.countDown();
            callers.shutdownNow();
            assertThat(callers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void failedLoadsPreserveTheirFailureAndDoNotPoisonLaterUse() {
        final Fixture fixture = new Fixture();
        final SmimeException failure = new SmimeException("Unable to load this store");

        assertThatThrownBy(() -> fixture.cache.get(keys(1), ignored -> { throw failure; })).isSameAs(failure);
        assertThat(fixture.schedulers).isEmpty();
        assertThat(fixture.load(1)).isNotNull();
        assertThat(fixture.loads.get(keys(1))).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentWaitersKeepTheOriginalFailureAndALaterLoadCanSucceed(final boolean fatal) throws Exception {
        final Fixture fixture = new Fixture();
        final Throwable failure = fatal ? new AssertionError("Store loader failed") : new SmimeException("Store loader failed");
        final CountDownLatch loading = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<Throwable> ownerFailure = new AtomicReference<>();
        final AtomicReference<Throwable> waiterFailure = new AtomicReference<>();
        final AtomicInteger extraLoads = new AtomicInteger();
        final Thread owner = new Thread(() -> {
            try {
                fixture.cache.get(keys(1), ignored -> {
                    loading.countDown();
                    await(release);
                    if (failure instanceof Error) throw (Error) failure;
                    throw (RuntimeException) failure;
                });
            } catch (Throwable observed) {
                ownerFailure.set(observed);
            }
        }, "smime-cache-loading-owner");
        final Thread waiter = new Thread(() -> {
            try {
                fixture.cache.get(keys(1), ignored -> {
                    extraLoads.incrementAndGet();
                    return new SmimeKey(null);
                });
            } catch (Throwable observed) {
                waiterFailure.set(observed);
            }
        }, "smime-cache-loading-waiter");
        try {
            owner.start();
            assertThat(loading.await(10, TimeUnit.SECONDS)).isTrue();
            waiter.start();
            awaitWaiting(waiter);
            release.countDown();
            owner.join(TimeUnit.SECONDS.toMillis(10));
            waiter.join(TimeUnit.SECONDS.toMillis(10));

            assertThat(owner.isAlive()).isFalse();
            assertThat(waiter.isAlive()).isFalse();
            assertThat(ownerFailure.get()).isSameAs(failure);
            assertThat(waiterFailure.get()).isSameAs(failure);
            assertThat(extraLoads).hasValue(0);
            assertThat(fixture.load(1)).isNotNull();
        } finally {
            release.countDown();
            owner.join(TimeUnit.SECONDS.toMillis(10));
            waiter.join(TimeUnit.SECONDS.toMillis(10));
        }
    }

    @Test
    void inabilityToStartMaintenanceDoesNotRetainKeysOrRejectValidLoading() {
        final SmimeKeyCache cache = new SmimeKeyCache(2, Duration.ofSeconds(10), System::nanoTime,
                () -> { throw new SecurityException("Cache worker unavailable"); });
        final SmimeKey first = new SmimeKey(null);
        final SmimeKey second = new SmimeKey(null);

        assertThat(cache.get(keys(1), ignored -> first)).isSameAs(first);
        assertThat(cache.get(keys(1), ignored -> second)).isSameAs(second);
    }

    @Test
    void alreadyAcquiredKeysStayUsableAndSigningAndDecryptionReloadEvictedEntries() throws Exception {
        final Fixture fixture = new Fixture();
        final SMIMESupport support = new SMIMESupport(fixture.cache);
        final Pkcs12Config keys = loadPkcs12KeyStore();
        final SmimeKey alreadyAcquired = loadedKey(support, keys);
        final MimeBodyPart content = new MimeBodyPart();
        content.setText("Body protected before cache eviction", "UTF-8");
        final MimeBodyPart encrypted = SmimeCryptoSupport.encrypt(content, alreadyAcquired.getCertificate(),
                SmimeCryptoSupport.DEFAULT_KEY_ENCAPSULATION_ALGORITHM, SmimeCryptoSupport.DEFAULT_CIPHER);
        final Session session = Session.getInstance(new Properties());
        final MimeMessage incoming = new MimeMessage(session);
        incoming.setDataHandler(encrypted.getDataHandler());
        incoming.saveChanges();

        fixture.load(1);
        fixture.load(2);

        assertThat(SmimeCryptoSupport.decrypt(encrypted, alreadyAcquired).getContent().toString())
                .contains("Body protected before cache eviction");
        final SmimeSigningConfig signing = SmimeSigningConfig.builder().pkcs12Config(keys).build();
        final Email email = SimpleJavaMail.fromDefaults().emailBuilder().startingBlank()
                .from("sender@example.org").to("recipient@example.org").withPlainText("Reloaded signing key")
                .signWithSmime(signing).buildEmail();
        final MimeMessage plain = new MimeMessage(session);
        plain.setFrom("sender@example.org");
        plain.setRecipients(jakarta.mail.Message.RecipientType.TO, "recipient@example.org");
        plain.setText("Reloaded signing key", "UTF-8");
        plain.saveChanges();
        final MimeMessage signed = support.signMessageWithSmime(session, email, plain, signing);
        assertThat(EmailConverter.mimeMessageToEmail(signed).getOriginalSmimeDetails().getVerificationStatus())
                .isEqualTo(OriginalSmimeDetails.VerificationStatus.VALID);
        final SmimeKey afterCapacityEviction = loadedKey(support, keys);
        assertThat(afterCapacityEviction).isNotSameAs(alreadyAcquired);

        fixture.advanceSeconds(10);
        fixture.schedulers.get(0).runNext();
        final SmimePreprocessingResult opened = support.processIncoming(session, incoming, keys);
        assertThat(opened.getDetails().getDecryptionStatus()).isEqualTo(OriginalSmimeDetails.DecryptionStatus.DECRYPTED);
        assertThat(EmailConverter.mimeMessageToEmail(opened.getEffectiveMimeMessage()).getPlainText())
                .contains("Body protected before cache eviction");
        assertThat(loadedKey(support, keys)).isNotSameAs(afterCapacityEviction);
    }

    @Test
    void theDefaultMaintenanceThreadIsDaemonAndDoesNotInheritApplicationContext() throws Exception {
        final ClassLoader previous = Thread.currentThread().getContextClassLoader();
        final InheritableThreadLocal<String> applicationContext = new InheritableThreadLocal<>();
        final Method factory = SmimeKeyCache.class.getDeclaredMethod("newMaintenanceWorker");
        factory.setAccessible(true);
        final ScheduledThreadPoolExecutor worker = (ScheduledThreadPoolExecutor) factory.invoke(null);
        applicationContext.set("application request");
        Thread.currentThread().setContextClassLoader(new ClassLoader(previous) { });
        try {
            worker.submit(() -> {
                assertThat(Thread.currentThread().isDaemon()).isTrue();
                assertThat(Thread.currentThread().getContextClassLoader()).isNull();
                assertThat(applicationContext.get()).isNull();
            }).get(10, TimeUnit.SECONDS);
        } finally {
            applicationContext.remove();
            Thread.currentThread().setContextClassLoader(previous);
            worker.shutdownNow();
            assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void deniedShutdownStillAllowsTheEmptyMaintenanceWorkerToRetire() throws Exception {
        final Method factory = SmimeKeyCache.class.getDeclaredMethod("newMaintenanceWorker");
        factory.setAccessible(true);
        final ScheduledThreadPoolExecutor worker = (ScheduledThreadPoolExecutor) factory.invoke(null);
        worker.setKeepAliveTime(20, TimeUnit.MILLISECONDS);
        final AtomicReference<Thread> ownedThread = new AtomicReference<>();
        final java.util.concurrent.ThreadFactory threads = worker.getThreadFactory();
        worker.setThreadFactory(task -> {
            final Thread thread = threads.newThread(task);
            ownedThread.set(thread);
            return thread;
        });
        final AtomicInteger deniedShutdowns = new AtomicInteger();
        final java.util.concurrent.ScheduledExecutorService shutdownDenied =
                (java.util.concurrent.ScheduledExecutorService) Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[] {java.util.concurrent.ScheduledExecutorService.class}, (proxy, method, arguments) -> {
                            if (method.getName().equals("shutdown")) {
                                deniedShutdowns.incrementAndGet();
                                throw new SecurityException("Owned worker shutdown denied");
                            }
                            try {
                                return method.invoke(worker, arguments);
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        });
        try {
            final SmimeKeyCache cache = new SmimeKeyCache(1, Duration.ofMillis(20), System::nanoTime, () -> shutdownDenied);
            cache.get(keys(1), ignored -> new SmimeKey(null));
            assertThat(ownedThread.get()).isNotNull();

            ownedThread.get().join(TimeUnit.SECONDS.toMillis(5));

            assertThat(deniedShutdowns).hasValue(1);
            assertThat(ownedThread.get().isAlive()).isFalse();
            assertThat(worker.isShutdown()).isFalse();
        } finally {
            worker.shutdownNow();
            assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static SmimeKey loadedKey(final SMIMESupport support, final Pkcs12Config configuration) throws Exception {
        final Method loader = SMIMESupport.class.getDeclaredMethod("retrieveSmimeKeyFromPkcs12Keystore", Pkcs12Config.class);
        loader.setAccessible(true);
        try {
            return (SmimeKey) loader.invoke(support, configuration);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
            throw failure;
        }
    }

    private static Pkcs12Config keys(final int id) {
        return Pkcs12Config.builder().pkcs12Store(new byte[] {(byte) id}).storePassword("store")
                .keyAlias("key").keyPassword("key").build();
    }

    private static void await(final CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static void awaitWaiting(final Thread caller) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (caller.isAlive() && caller.getState() != Thread.State.WAITING && System.nanoTime() - deadline < 0) {
            caller.join(1);
        }
        assertThat(caller.getState()).isEqualTo(Thread.State.WAITING);
    }

    private static final class Fixture {
        private final AtomicLong clock = new AtomicLong();
        private final List<ManualMaintenance> schedulers = new ArrayList<>();
        private final Map<Pkcs12Config, Integer> loads = new ConcurrentHashMap<>();
        private final Function<Pkcs12Config, SmimeKey> loader = configuration -> {
            loads.merge(configuration, 1, Integer::sum);
            return new SmimeKey(null);
        };
        private final SmimeKeyCache cache = new SmimeKeyCache(2, Duration.ofSeconds(10), clock::get, () -> {
            final ManualMaintenance worker = new ManualMaintenance();
            schedulers.add(worker);
            return worker;
        });

        private SmimeKey load(final int id) { return cache.get(keys(id), loader); }
        private void advanceSeconds(final long seconds) { clock.addAndGet(TimeUnit.SECONDS.toNanos(seconds)); }
    }

    /** Records scheduled work without running threads, so idle retirement can be driven independently of cache lookups. */
    private static final class ManualMaintenance extends ScheduledThreadPoolExecutor {
        private final Deque<Runnable> tasks = new ArrayDeque<>();
        private long lastDelay;

        private ManualMaintenance() { super(1); }

        @Override
        public ScheduledFuture<?> schedule(final Runnable command, final long delay, final TimeUnit unit) {
            assertThat(isShutdown()).isFalse();
            lastDelay = unit.toNanos(delay);
            tasks.addLast(command);
            return new ScheduledTask(command, lastDelay);
        }

        private void runNext() { tasks.removeFirst().run(); }
    }

    private static final class ScheduledTask extends FutureTask<Void> implements ScheduledFuture<Void> {
        private final long delay;
        private ScheduledTask(final Runnable command, final long delay) {
            super(command, null);
            this.delay = delay;
        }
        @Override public long getDelay(final TimeUnit unit) { return unit.convert(delay, TimeUnit.NANOSECONDS); }
        @Override public int compareTo(final Delayed other) {
            return Long.compare(delay, other.getDelay(TimeUnit.NANOSECONDS));
        }
    }
}
