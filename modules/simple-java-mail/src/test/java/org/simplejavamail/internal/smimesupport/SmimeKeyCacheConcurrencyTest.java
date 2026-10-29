package org.simplejavamail.internal.smimesupport;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.utils.mail.smime.SmimeKey;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class SmimeKeyCacheConcurrencyTest {

    @Test
    void concurrentFirstUseOfEqualConfigurationsReusesOneParsedKey() throws Exception {
        final Pkcs12Config keys = independentStore();
        final int workers = 16;
        final ExecutorService executor = Executors.newFixedThreadPool(workers);
        final CountDownLatch ready = new CountDownLatch(workers);
        final CountDownLatch start = new CountDownLatch(1);
        final List<Future<SmimeKey>> results = new ArrayList<>();
        try {
            for (int worker = 0; worker < workers; worker++) {
                results.add(executor.submit(() -> {
                    final Pkcs12Config equalKeys = copy(keys);
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return load(equalKeys);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            final Set<SmimeKey> identities = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Future<SmimeKey> result : results) {
                identities.add(result.get(30, TimeUnit.SECONDS));
            }
            assertThat(identities).hasSize(1);
            assertThat(load(copy(keys))).isSameAs(identities.iterator().next());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void distinctConfigurationsStaySeparateAndFailedLoadsDoNotPoisonValidReuse() throws Exception {
        final Pkcs12Config first = independentStore();
        final Pkcs12Config second = independentStore();
        final SmimeKey firstKey = load(first);
        final SmimeKey secondKey = load(second);
        assertThat(first).isNotEqualTo(second);
        assertThat(secondKey).isNotSameAs(firstKey);
        assertThat(load(copy(first))).isSameAs(firstKey);
        assertThat(load(copy(second))).isSameAs(secondKey);

        final Pkcs12Config missingAlias = Pkcs12Config.builder().pkcs12Store(first.getPkcs12StoreData())
                .storePassword(first.getStorePassword()).keyAlias("absent-alias").keyPassword(first.getKeyPassword()).build();
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> load(missingAlias)).isInstanceOf(SmimeException.class);
        }
        assertThat(load(copy(first))).isSameAs(firstKey);
    }

    @Test
    void concurrentSigningAndDecryptionKeepTheirPublicOutcomes() throws Exception {
        final Pkcs12Config keys = independentStore();
        final KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(keys.getPkcs12StoreData()), keys.getStorePassword());
        final MimeBodyPart content = new MimeBodyPart();
        content.setText("Concurrent protected body", "UTF-8");
        final MimeBodyPart encrypted = SmimeCryptoSupport.encrypt(content,
                (X509Certificate) store.getCertificate(keys.getKeyAlias()),
                SmimeCryptoSupport.DEFAULT_KEY_ENCAPSULATION_ALGORITHM, SmimeCryptoSupport.DEFAULT_CIPHER);
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom("sender@example.org");
        message.setDataHandler(encrypted.getDataHandler());
        message.saveChanges();
        final ByteArrayOutputStream encryptedBytes = new ByteArrayOutputStream();
        message.writeTo(encryptedBytes);
        final Email outgoing = SimpleJavaMail.fromDefaults().emailBuilder().startingBlank()
                .from("sender@example.org").to("recipient@example.org").withPlainText("Concurrent signed body")
                .signWithSmime(SmimeSigningConfig.builder().pkcs12Config(keys).build()).buildEmail();

        final ExecutorService executor = Executors.newFixedThreadPool(8);
        final CountDownLatch start = new CountDownLatch(1);
        final List<Future<?>> results = new ArrayList<>();
        try {
            for (int worker = 0; worker < 8; worker++) {
                final boolean signing = worker % 2 == 0;
                results.add(executor.submit(() -> {
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    if (signing) {
                        final Email verified = EmailConverter.mimeMessageToEmail(EmailConverter.emailToMimeMessage(outgoing));
                        assertThat(verified.getOriginalSmimeDetails().getVerificationStatus())
                                .isEqualTo(OriginalSmimeDetails.VerificationStatus.VALID);
                        assertThat(verified.getPlainText()).contains("Concurrent signed body");
                    } else {
                        final Email decrypted = EmailConverter.emlToEmail(new ByteArrayInputStream(encryptedBytes.toByteArray()), keys);
                        assertThat(decrypted.getOriginalSmimeDetails().getDecryptionStatus())
                                .isEqualTo(OriginalSmimeDetails.DecryptionStatus.DECRYPTED);
                        assertThat(decrypted.getPlainText()).contains("Concurrent protected body");
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> result : results) {
                result.get(30, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static Pkcs12Config independentStore() throws Exception {
        final Pkcs12Config keys = loadPkcs12KeyStore();
        final KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(keys.getPkcs12StoreData()), keys.getStorePassword());
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        store.store(bytes, keys.getStorePassword());
        return Pkcs12Config.builder().pkcs12Store(bytes.toByteArray()).storePassword(keys.getStorePassword())
                .keyAlias(keys.getKeyAlias()).keyPassword(keys.getKeyPassword()).build();
    }

    private static Pkcs12Config copy(final Pkcs12Config keys) {
        return Pkcs12Config.builder().pkcs12Store(keys.getPkcs12StoreData()).storePassword(keys.getStorePassword())
                .keyAlias(keys.getKeyAlias()).keyPassword(keys.getKeyPassword()).build();
    }

    private static SmimeKey load(final Pkcs12Config keys) throws Exception {
        final Method method = SMIMESupport.class.getDeclaredMethod("retrieveSmimeKeyFromPkcs12Keystore", Pkcs12Config.class);
        method.setAccessible(true);
        try {
            return (SmimeKey) method.invoke(new SMIMESupport(), keys);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception) {
                throw (Exception) failure.getCause();
            }
            throw failure;
        }
    }
}
