package org.simplejavamail.internal.smimesupport;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.SignatureStatus;
import org.simplejavamail.api.email.EncryptionStatus;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.config.ConfigKeys;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.converter.internal.ConfiguredEmailConverterImpl;
import org.simplejavamail.internal.smime.FactorySmimeRuntime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;
import static testutil.configcontracts.ConfigurationContractCoverage.record;

class FactorySmimeRuntimeTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void absentAndExplicitUnlimitedUseThePublishedFallbackWithoutFabricatingSnapshotInput(final boolean explicit) throws Exception {
        final ConfigLoader loader = ConfigLoader.builder();
        if (explicit) loader.withMap(Map.of(ConfigKeys.SMIME_KEY_CACHE_CAPACITY.key(), -1));
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(loader.load());
        if (explicit) assertThat(mail.getConfig().get(ConfigKeys.SMIME_KEY_CACHE_CAPACITY)).isEqualTo(-1);
        else assertThat(mail.getConfig().get(ConfigKeys.SMIME_KEY_CACHE_CAPACITY)).isNull();
        try (Mailer mailer = mail.mailerBuilder().withProperty("mail.host", "localhost").withTransportModeLoggingOnly(true).buildMailer()) {
            mailer.rehearse(message(mail, loadPkcs12KeyStore()));
            assertThat(field(field(moduleIfInitialized(mail), "keyCache"), "capacity"))
                    .isEqualTo(ConfigLoader.DEFAULT_SMIME_KEY_CACHE_CAPACITY);
        }
    }

    @Test
    void mailerAndRepeatedConvertersShareRealSigningAndDecryptionWithinOneFactory() throws Exception {
        final SimpleJavaMail mail = factory(2);
        final Pkcs12Config keys = loadPkcs12KeyStore();
        final Pkcs12Config second = independentStore(keys);
        final Pkcs12Config third = independentStore(keys);
        assertThat(moduleIfInitialized(mail)).isNull();
        try (Mailer mailer = mail.mailerBuilder().withProperty("mail.host", "localhost").withTransportModeLoggingOnly(true).buildMailer()) {
            final byte[] signed = mailer.rehearse(message(mail, keys)).getEmlBytes();
            assertThat(mail.converter().fromEml(signed).toEmail().getInspection().getPlainText().getSignatureStatus())
                    .isEqualTo(SignatureStatus.VERIFIED);
            final Object runtime = moduleIfInitialized(mail);
            final Object signedKey = resident(entries(runtime).get(keys));
            mailer.rehearse(message(mail, second));

            final Email received = mail.converter().fromEml(encrypted(keys)).withSmimeDecryption(keys).toEmail();

            assertThat(received.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
            assertThat(received.getPlainText()).contains("Cache owner body");
            assertThat(moduleIfInitialized(mail)).isSameAs(runtime);
            assertThat(entries(runtime)).hasSize(2);
            assertThat(resident(entries(runtime).get(keys))).isSameAs(signedKey);
            mailer.rehearse(message(mail, third));
            assertThat(entries(runtime)).containsOnlyKeys(keys, third).doesNotContainKey(second);
            record("adapter", "factory.smime-runtime");
        }
    }

    @Test
    void zeroCapacityRetainsNoCompletedKeysAfterRealSigningAndDecryption() throws Exception {
        final SimpleJavaMail mail = factory(0);
        final Pkcs12Config keys = loadPkcs12KeyStore();
        try (Mailer mailer = mail.mailerBuilder().withProperty("mail.host", "localhost").withTransportModeLoggingOnly(true).buildMailer()) {
            mailer.rehearse(message(mail, keys));
            assertThat(entries(moduleIfInitialized(mail))).isEmpty();
            final Email opened = mail.converter().fromEml(encrypted(keys)).withSmimeDecryption(keys).toEmail();
            assertThat(opened.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
            assertThat(entries(moduleIfInitialized(mail))).isEmpty();
            record("field", "factory.smime-key-cache");
            record("rule", "smime.cache-capacity");
        }
    }

    @Test
    void identicalSnapshotsCreateIndependentOwnersAndTheActualReceiverChoosesTheCache() throws Exception {
        final SimpleJavaMailConfig snapshot = ConfigLoader.builder().withMap(Map.of(ConfigKeys.SMIME_KEY_CACHE_CAPACITY.key(), 1)).load();
        final SimpleJavaMail first = SimpleJavaMail.withConfig(snapshot);
        final SimpleJavaMail second = SimpleJavaMail.withConfig(snapshot);
        final Pkcs12Config keys = loadPkcs12KeyStore();
        final Email builtUnderFirst = message(first, keys);
        try (Mailer firstMailer = first.mailerBuilder().withProperty("mail.host", "localhost").withTransportModeLoggingOnly(true).buildMailer();
                Mailer secondMailer = second.mailerBuilder().withProperty("mail.host", "localhost").withTransportModeLoggingOnly(true).buildMailer()) {
            secondMailer.rehearse(builtUnderFirst);
            assertThat(moduleIfInitialized(first)).isNull();
            assertThat(entries(moduleIfInitialized(second))).hasSize(1);

            firstMailer.rehearse(builtUnderFirst);

            assertThat(moduleIfInitialized(first)).isNotSameAs(moduleIfInitialized(second));
            assertThat(entries(moduleIfInitialized(first))).hasSize(1);
            assertThat(resident(entries(moduleIfInitialized(first)).get(keys)))
                    .isNotSameAs(resident(entries(moduleIfInitialized(second)).get(keys)));
            record("case", "smime.factory-cache");
        }
    }

    @Test
    void invalidCapacityIsInactiveForPlainMailAndRejectedBeforePrivateKeyUse() throws Exception {
        final SimpleJavaMail mail = factory(-2);
        assertThat(mail.getConfig().get(ConfigKeys.SMIME_KEY_CACHE_CAPACITY)).isEqualTo(-2);
        try (Mailer mailer = mail.mailerBuilder().withProperty("mail.host", "localhost").withTransportModeLoggingOnly(true).buildMailer()) {
            mailer.rehearse(mail.emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org")
                    .withPlainText("Plain content").buildEmail());
            assertThat(moduleIfInitialized(mail)).isNull();
            assertThatThrownBy(() -> mailer.rehearse(message(mail, loadPkcs12KeyStore())))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("simplejavamail.smime.cache.capacity");
            final Pkcs12Config keys = loadPkcs12KeyStore();
            assertThatThrownBy(() -> mail.converter().fromEml(encrypted(keys)).withSmimeDecryption(keys).toEmail())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("simplejavamail.smime.cache.capacity");
        }
    }

    private static SimpleJavaMail factory(final int capacity) {
        return SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(ConfigKeys.SMIME_KEY_CACHE_CAPACITY.key(), capacity)).load());
    }

    private static Email message(final SimpleJavaMail mail, final Pkcs12Config keys) {
        return mail.emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org")
                .withPlainText("Cache owner body").signWithSmime(SmimeSigningConfig.builder().pkcs12Config(keys).build()).buildEmail();
    }

    private static Object moduleIfInitialized(final SimpleJavaMail factory) throws Exception {
        final FactorySmimeRuntime context = ((ConfiguredEmailConverterImpl) factory.converter()).smimeRuntime();
        return field(context, "module");
    }

    @SuppressWarnings("unchecked")
    private static Map<Pkcs12Config, ?> entries(final Object runtime) throws Exception {
        assertThat(runtime).isNotNull();
        return (Map<Pkcs12Config, ?>) field(field(runtime, "keyCache"), "entries");
    }

    private static Object resident(final Object entry) throws Exception {
        assertThat(entry).isNotNull();
        return entry instanceof org.simplejavamail.utils.mail.smime.SmimeKey ? entry : field(entry, "key");
    }

    private static Object field(final Object owner, final String name) throws Exception {
        final Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static byte[] encrypted(final Pkcs12Config keys) throws Exception {
        final KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(keys.getPkcs12StoreData()), keys.getStorePassword());
        final MimeBodyPart body = new MimeBodyPart();
        body.setText("Cache owner body", "UTF-8");
        final MimeMultipart clear = new MimeMultipart("mixed");
        clear.addBodyPart(body);
        final MimeBodyPart content = new MimeBodyPart();
        content.setContent(clear);
        final MimeBodyPart encrypted = SmimeCryptoSupport.encrypt(content,
                (X509Certificate) store.getCertificate(keys.getKeyAlias()),
                SmimeCryptoSupport.DEFAULT_KEY_ENCAPSULATION_ALGORITHM, SmimeCryptoSupport.DEFAULT_CIPHER);
        final Properties properties = new Properties();
        properties.setProperty("mail.host", "localhost");
        final MimeMessage message = new MimeMessage(Session.getInstance(properties));
        message.setDataHandler(encrypted.getDataHandler());
        message.saveChanges();
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        message.writeTo(output);
        return output.toByteArray();
    }

    private static Pkcs12Config independentStore(final Pkcs12Config keys) throws Exception {
        final KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(keys.getPkcs12StoreData()), keys.getStorePassword());
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        store.store(data, keys.getStorePassword());
        return Pkcs12Config.builder().pkcs12Store(data.toByteArray()).storePassword(keys.getStorePassword())
                .keyAlias(keys.getKeyAlias()).keyPassword(keys.getKeyPassword()).build();
    }
}
