package org.simplejavamail.internal.clisupport.daemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.config.ConfigKeys;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.converter.internal.ConfiguredEmailConverterImpl;
import org.simplejavamail.internal.clisupport.CliExecutionEnvironment;
import org.simplejavamail.internal.clisupport.CliExecutionResult;
import org.simplejavamail.internal.clisupport.CliMailerProfile;
import org.simplejavamail.internal.clisupport.CliSupport;
import org.simplejavamail.internal.clisupport.MailerProvider;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static testutil.configcontracts.ConfigurationContractCoverage.record;

class CliSmimeCacheCapacityTest {
    @TempDir Path directory;

    @Test
    void capacityChangesSeparateRetainedMailersAndActualSigningAndImportUseTheirRequestOwner() throws Exception {
        final Pkcs12Config keys = testKeys();
        final Pkcs12Config secondKeys = equivalentMaterialInAnotherStore(keys);
        final Pkcs12Config thirdKeys = equivalentMaterialInAnotherStore(keys);
        final Path store = Files.write(directory.resolve("signing.p12"), keys.getPkcs12StoreData());
        final SimpleJavaMailConfig disabledConfig = config(0, store, keys);
        final SimpleJavaMailConfig retainedConfig = config(2, store, keys);
        final byte[] daemonKey = new byte[32];
        final CliMailerProfile disabledProfile = CliMailerProfile.create(disabledConfig, List.of(), daemonKey, directory);
        final CliMailerProfile retainedProfile = CliMailerProfile.create(retainedConfig, List.of(), daemonKey, directory);
        assertThat(disabledProfile).isNotEqualTo(retainedProfile);
        final AtomicInteger constructions = new AtomicInteger();
        try (DaemonMailerRegistry registry = new DaemonMailerRegistry(2, Duration.ofMinutes(1));
                CliExecutionEnvironment disabled = new CliExecutionEnvironment(disabledConfig, borrow(registry), directory);
                CliExecutionEnvironment retained = new CliExecutionEnvironment(retainedConfig, borrow(registry), directory)) {
            final Mailer first = register(registry, disabledProfile, disabled.simpleJavaMail(), constructions);
            final Mailer second = register(registry, retainedProfile, retained.simpleJavaMail(), constructions);
            assertThat(second).isNotSameAs(first);
            assertThat(register(registry, disabledProfile, disabled.simpleJavaMail(), constructions)).isSameAs(first);
            assertThat(constructions).hasValue(2);

            validateSignedMessage(disabled, daemonKey);
            validateSignedMessage(retained, daemonKey);
            signAndVerify(retained.simpleJavaMail(), second, secondKeys);
            assertThat(entries(disabled.simpleJavaMail())).isEmpty();
            assertThat(entries(retained.simpleJavaMail())).hasSize(2);
            final Object resident = entries(retained.simpleJavaMail()).entrySet().stream()
                    .filter(entry -> entry.getKey().equals(keys)).findFirst().orElseThrow().getValue();

            final byte[] encrypted = encryptedInput(keys);
            decrypt(disabled.simpleJavaMail(), encrypted, keys);
            decrypt(retained.simpleJavaMail(), encrypted, keys);
            signAndVerify(retained.simpleJavaMail(), second, thirdKeys);

            assertThat(entries(disabled.simpleJavaMail())).isEmpty();
            assertThat(entries(retained.simpleJavaMail())).hasSize(2);
            assertThat(entries(retained.simpleJavaMail()).containsKey(keys)).isTrue();
            assertThat(entries(retained.simpleJavaMail()).containsKey(thirdKeys)).isTrue();
            assertThat(entries(retained.simpleJavaMail()).containsKey(secondKeys)).isFalse();
            assertThat(entries(retained.simpleJavaMail()).entrySet().stream().filter(entry -> entry.getKey().equals(keys))
                    .findFirst().orElseThrow().getValue()).isSameAs(resident);
            assertThat(constructions).hasValue(2);
        }
        record("adapter", "cli.smime-cache-profile");
        record("case", "cli.smime-cache-ownership");
    }

    private static SimpleJavaMailConfig config(final int capacity, final Path store, final Pkcs12Config keys) {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(ConfigKeys.SMIME_KEY_CACHE_CAPACITY.key(), capacity);
        properties.put("simplejavamail.smtp.host", "relay.example.test");
        properties.put("simplejavamail.transport.mode.logging.only", true);
        properties.put("simplejavamail.defaults.connectionpool.coresize", 0);
        properties.put("simplejavamail.smime.signing.keystore", store.toString());
        properties.put("simplejavamail.smime.signing.keystore_password", new String(keys.getStorePassword()));
        properties.put("simplejavamail.smime.signing.key_alias", keys.getKeyAlias());
        properties.put("simplejavamail.smime.signing.key_password", new String(keys.getKeyPassword()));
        return ConfigLoader.builder().withMap(properties).load();
    }

    private static Pkcs12Config testKeys() {
        return Pkcs12Config.builder().pkcs12Store(Path.of("../simple-java-mail/src/test/resources/pkcs12/smime_keystore.pkcs12"))
                .storePassword("letmein").keyAlias("smime_test_user_alias_rsa").keyPassword("letmein").build();
    }

    private static Mailer register(final DaemonMailerRegistry registry, final CliMailerProfile profile,
            final SimpleJavaMail factory, final AtomicInteger constructions) {
        try (MailerProvider.Lease lease = registry.acquire(profile, () -> {
            constructions.incrementAndGet();
            return factory.mailerBuilder().buildMailer();
        })) {
            return lease.mailer();
        }
    }

    private void validateSignedMessage(final CliExecutionEnvironment environment, final byte[] daemonKey) {
        final CliExecutionResult result = CliSupport.execute(new String[] {"validate", "--email:startingBlank",
                "--email:from", "sender@example.org", "--email:to", "reader@example.org",
                "--email:withPlainText", "CLI signed content"}, directory, UUID.randomUUID(), environment, daemonKey);
        assertThat(result.exitCode()).as(result.stderr()).isZero();
    }

    private static MailerProvider borrow(final DaemonMailerRegistry registry) {
        return new MailerProvider() {
            @Override public Lease acquire(final CliMailerProfile profile, final Supplier<Mailer> factory) {
                return registry.acquire(profile, factory);
            }
            @Override public Optional<Lease> tryAcquireExisting(final CliMailerProfile profile) {
                return registry.tryAcquireExisting(profile);
            }
        };
    }

    private static byte[] encryptedInput(final Pkcs12Config keys) throws Exception {
        final KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(keys.getPkcs12StoreData()), keys.getStorePassword());
        final SimpleJavaMail factory = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
        final Email outgoing = factory.emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org")
                .withPlainText("CLI decrypted content").withAttachment("proof.txt", new byte[] {1}, "text/plain")
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

    private static void signAndVerify(final SimpleJavaMail factory, final Mailer mailer, final Pkcs12Config keys) {
        final Email outgoing = factory.emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org")
                .withPlainText("CLI additional signing key")
                .signWithSmime(SmimeSigningConfig.builder().pkcs12Config(keys).build()).buildEmail();
        final Email inspected = factory.converter().fromEml(mailer.rehearse(outgoing).getEmlBytes()).toEmail();
        assertThat(inspected.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(OriginalSmimeDetails.VerificationStatus.VALID);
    }

    private static void decrypt(final SimpleJavaMail factory, final byte[] encrypted, final Pkcs12Config keys) {
        final Email incoming = factory.converter().fromEml(encrypted).withSmimeDecryption(keys).toEmail();
        assertThat(incoming.getOriginalSmimeDetails().getDecryptionStatus()).isEqualTo(OriginalSmimeDetails.DecryptionStatus.DECRYPTED);
        assertThat(incoming.getPlainText()).contains("CLI decrypted content");
    }

    private static Map<?, ?> entries(final SimpleJavaMail factory) throws Exception {
        final Object owner = ((ConfiguredEmailConverterImpl) factory.converter()).smimeRuntime();
        final Field moduleField = owner.getClass().getDeclaredField("module");
        moduleField.setAccessible(true);
        final Object module = moduleField.get(owner);
        assertThat(module).isNotNull();
        final Field cacheField = module.getClass().getDeclaredField("keyCache");
        cacheField.setAccessible(true);
        final Object cache = cacheField.get(module);
        final Field entriesField = cache.getClass().getDeclaredField("entries");
        entriesField.setAccessible(true);
        return (Map<?, ?>) entriesField.get(cache);
    }
}
