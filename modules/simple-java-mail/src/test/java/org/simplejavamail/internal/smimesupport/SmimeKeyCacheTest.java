package org.simplejavamail.internal.smimesupport;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
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
        assertThat(fixture.load(1)).isNotNull();
        assertThat(fixture.loads.get(keys(1))).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(FailureKind.class)
    void concurrentWaitersKeepTheOriginalFailureAndALaterLoadCanSucceed(final FailureKind kind) throws Exception {
        final Fixture fixture = new Fixture();
        final Throwable failure = kind == FailureKind.ERROR ? new AssertionError("Store loader failed")
                : kind == FailureKind.COMPLETION ? new CompletionException(new IllegalStateException("Store loader failed"))
                : new SmimeException("Store loader failed");
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

        fixture.load(1);
        fixture.load(2);
        final SmimePreprocessingResult opened = support.processIncoming(session, incoming, keys);
        assertThat(opened.getDetails().getDecryptionStatus()).isEqualTo(OriginalSmimeDetails.DecryptionStatus.DECRYPTED);
        assertThat(EmailConverter.mimeMessageToEmail(opened.getEffectiveMimeMessage()).getPlainText())
                .contains("Body protected before cache eviction");
        assertThat(loadedKey(support, keys)).isNotSameAs(afterCapacityEviction);
    }

    @Test
    void zeroRetainsNoCompletedEntryButDoesNotRejectSuccessfulLoads() {
        final Fixture fixture = new Fixture(0);
        final SmimeKey first = fixture.load(1);
        assertThat(fixture.load(1)).isNotSameAs(first);
        assertThat(fixture.loads.get(keys(1))).isEqualTo(2);
    }

    @Test
    void unlimitedRetainsAllConfigurationsBeyondTheFormerPrivateCeiling() {
        final Fixture fixture = new Fixture(-1);
        final List<SmimeKey> observed = new ArrayList<>();
        for (int id = 0; id < 300; id++) observed.add(fixture.load(id));
        for (int id = 0; id < 300; id++) assertThat(fixture.load(id)).isSameAs(observed.get(id));
        assertThat(fixture.loads).hasSize(300).allSatisfy((configuration, count) -> assertThat(count).isEqualTo(1));
    }

    @Test
    void theConfiguredPositiveCapacityHasNoHidden256EntryCeiling() {
        final Fixture fixture = new Fixture(300);
        final List<SmimeKey> observed = new ArrayList<>();
        for (int id = 0; id < 300; id++) observed.add(fixture.load(id));
        for (int id = 0; id < 300; id++) assertThat(fixture.load(id)).isSameAs(observed.get(id));
        fixture.load(300);
        assertThat(fixture.load(0)).isNotSameAs(observed.get(0));
    }

    @Test
    void unsupportedNegativeCapacityIsAFeatureConfigurationError() {
        assertThatThrownBy(() -> new SmimeKeyCache(-2)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("simplejavamail.smime.cache.capacity");
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 2})
    void overlappingEqualLoadsShareTheActiveResultEvenWhenCompletedRetentionIsDisabled(final int capacity) throws Exception {
        final Fixture fixture = new Fixture(capacity);
        final CountDownLatch loading = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final SmimeKey key = new SmimeKey(null);
        final FutureTask<SmimeKey> producer = new FutureTask<>(() -> fixture.cache.get(keys(1), ignored -> {
            loading.countDown();
            await(release);
            return key;
        }));
        final FutureTask<SmimeKey> consumer = new FutureTask<>(() -> fixture.load(1));
        final Thread owner = new Thread(producer, "smime-cache-owner");
        final Thread waiter = new Thread(consumer, "smime-cache-waiter");
        try {
            owner.start();
            assertThat(loading.await(10, TimeUnit.SECONDS)).isTrue();
            waiter.start();
            awaitWaiting(waiter);
            release.countDown();
            assertThat(producer.get(10, TimeUnit.SECONDS)).isSameAs(key);
            assertThat(consumer.get(10, TimeUnit.SECONDS)).isSameAs(key);
            owner.join(TimeUnit.SECONDS.toMillis(10));
            waiter.join(TimeUnit.SECONDS.toMillis(10));
            assertThat(fixture.loads).isEmpty();
            if (capacity == 0) assertThat(fixture.load(1)).isNotSameAs(key);
            else assertThat(fixture.load(1)).isSameAs(key);
        } finally {
            release.countDown();
            owner.join(TimeUnit.SECONDS.toMillis(10));
            waiter.join(TimeUnit.SECONDS.toMillis(10));
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
                .keyAlias("key-" + id).keyPassword("key").build();
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
        private final Map<Pkcs12Config, Integer> loads = new ConcurrentHashMap<>();
        private final Function<Pkcs12Config, SmimeKey> loader = configuration -> {
            loads.merge(configuration, 1, Integer::sum);
            return new SmimeKey(null);
        };
        private final SmimeKeyCache cache;
        private Fixture() { this(2); }
        private Fixture(final int capacity) { cache = new SmimeKeyCache(capacity); }
        private SmimeKey load(final int id) { return cache.get(keys(id), loader); }
    }

    enum FailureKind { SMIME, ERROR, COMPLETION }
}
