package org.simplejavamail.springsupport;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EncryptionStatus;
import org.simplejavamail.api.email.SignatureStatus;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.config.ConfigKeys;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.converter.internal.ConfiguredEmailConverterImpl;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static testutil.configcontracts.ConfigurationContractCoverage.record;

class SpringSmimeCacheCapacityTest {
    @Test
    void independentlyConfiguredContextsShareOnlyTheirOwnSigningAndDecryptionCache() throws Exception {
        final Pkcs12Config keys = testKeys();
        final Pkcs12Config secondKeys = equivalentMaterialInAnotherStore(keys);
        final Pkcs12Config thirdKeys = equivalentMaterialInAnotherStore(keys);
        final byte[] encrypted = encryptedInput(keys);
        try (AnnotationConfigApplicationContext disabled = context(0, true);
                AnnotationConfigApplicationContext retained = context(2, false)) {
            final SimpleJavaMail first = disabled.getBean(SimpleJavaMail.class);
            final SimpleJavaMail second = retained.getBean(SimpleJavaMail.class);
            assertThat(first.getConfig().get(ConfigKeys.SMIME_KEY_CACHE_CAPACITY)).isZero();
            assertThat(second.getConfig().get(ConfigKeys.SMIME_KEY_CACHE_CAPACITY)).isEqualTo(2);
            assertThat(ownerModule(first)).isNull();
            assertThat(ownerModule(second)).isNull();

            signAndVerify(first, disabled.getBean(Mailer.class), keys);
            signAndVerify(second, retained.getBean(Mailer.class), keys);
            signAndVerify(second, retained.getBean(Mailer.class), secondKeys);
            assertThat(entries(first)).isEmpty();
            assertThat(entries(second)).hasSize(2);
            final Object retainedEntry = entries(second).entrySet().stream()
                    .filter(entry -> entry.getKey().equals(keys)).findFirst().orElseThrow().getValue();

            decrypt(first, encrypted, keys);
            decrypt(second, encrypted, keys);
            signAndVerify(second, retained.getBean(Mailer.class), thirdKeys);

            assertThat(entries(first)).isEmpty();
            assertThat(entries(second)).hasSize(2);
            assertThat(entries(second).containsKey(keys)).isTrue();
            assertThat(entries(second).containsKey(thirdKeys)).isTrue();
            assertThat(entries(second).containsKey(secondKeys)).isFalse();
            assertThat(entries(second).entrySet().stream().filter(entry -> entry.getKey().equals(keys))
                    .findFirst().orElseThrow().getValue()).isSameAs(retainedEntry);
            assertThat(ownerModule(first)).isNotSameAs(ownerModule(second));
        }
        record("adapter", "spring.smime-cache");
        record("case", "spring.smime-cache-ownership");
    }

    private static AnnotationConfigApplicationContext context(final int capacity, final boolean locked) {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("cache policy", Map.of(
                (locked ? "simplejavamail.locked.smime.cache.capacity" : ConfigKeys.SMIME_KEY_CACHE_CAPACITY.key()), capacity,
                "simplejavamail.smtp.host", "relay.example.test")));
        // A supplied snapshot is a supported Spring replacement layer; omit unrelated classpath example defaults.
        context.registerBean(SimpleJavaMailConfig.class, () -> ConfigLoader.builder()
                .withSource(new SpringEnvironmentConfigSource(context.getEnvironment())).load());
        context.register(SimpleJavaMailAutoConfiguration.class);
        context.refresh();
        return context;
    }

    private static Pkcs12Config testKeys() {
        return Pkcs12Config.builder().pkcs12Store(Path.of("../simple-java-mail/src/test/resources/pkcs12/smime_keystore.pkcs12"))
                .storePassword("letmein").keyAlias("smime_test_user_alias_rsa").keyPassword("letmein").build();
    }

    private static void signAndVerify(final SimpleJavaMail factory, final Mailer mailer, final Pkcs12Config keys) {
        final Email outgoing = factory.emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org")
                .withPlainText("Spring signed content")
                .signWithSmime(SmimeSigningConfig.builder().pkcs12Config(keys).build()).buildEmail();
        final Email inspected = factory.converter().fromEml(mailer.rehearse(outgoing).getEmlBytes()).toEmail();
        assertThat(inspected.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.VERIFIED);
        assertThat(inspected.getPlainText()).contains("Spring signed content");
    }

    private static byte[] encryptedInput(final Pkcs12Config keys) throws Exception {
        final KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(keys.getPkcs12StoreData()), keys.getStorePassword());
        final SimpleJavaMail factory = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
        final Email outgoing = factory.emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org")
                .withPlainText("Spring decrypted content").withAttachment("proof.txt", new byte[] {1}, "text/plain")
                .encryptWithSmime(SmimeEncryptionConfig.builder().x509Certificate((X509Certificate) store.getCertificate(keys.getKeyAlias())).build())
                .buildEmail();
        return factory.converter().fromEmail(outgoing).toEmlBytes();
    }

    private static Pkcs12Config equivalentMaterialInAnotherStore(final Pkcs12Config keys) throws Exception {
        final KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(keys.getPkcs12StoreData()), keys.getStorePassword());
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        store.store(bytes, keys.getStorePassword());
        final Pkcs12Config result = Pkcs12Config.builder().pkcs12Store(bytes.toByteArray()).storePassword(keys.getStorePassword())
                .keyAlias(keys.getKeyAlias()).keyPassword(keys.getKeyPassword()).build();
        assertThat(result).isNotEqualTo(keys);
        return result;
    }

    private static void decrypt(final SimpleJavaMail factory, final byte[] encrypted, final Pkcs12Config keys) {
        final Email incoming = factory.converter().fromEml(encrypted).withSmimeDecryption(keys).toEmail();
        assertThat(incoming.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
        assertThat(incoming.getPlainText()).contains("Spring decrypted content");
    }

    private static Object ownerModule(final SimpleJavaMail factory) throws Exception {
        final Object owner = ((ConfiguredEmailConverterImpl) factory.converter()).smimeRuntime();
        final Field module = owner.getClass().getDeclaredField("module");
        module.setAccessible(true);
        return module.get(owner);
    }

    private static Map<?, ?> entries(final SimpleJavaMail factory) throws Exception {
        final Object module = ownerModule(factory);
        assertThat(module).isNotNull();
        final Field cacheField = module.getClass().getDeclaredField("keyCache");
        cacheField.setAccessible(true);
        final Object cache = cacheField.get(module);
        final Field entries = cache.getClass().getDeclaredField("entries");
        entries.setAccessible(true);
        return (Map<?, ?>) entries.get(cache);
    }
}
