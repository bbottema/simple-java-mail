package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.email.config.OpenPgpEncryptionConfig;
import org.simplejavamail.api.email.config.OpenPgpSigningConfig;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.config.ConfigLoader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static jakarta.mail.Message.RecipientType.TO;

final class EmailContractTestSupport {
    static final byte[] KEY_BYTES = "controlled key bytes".getBytes(StandardCharsets.UTF_8);
    static final String ADDRESS = "new@example.org";
    static final String NAME = "New Name";
    static final String TEXT = "new text";
    final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
    final Recipient recipient = new Recipient(NAME, ADDRESS, TO, null);
    final Path textFile;
    final Path keyFile;
    final Path certificateFile;
    final Path storeFile;
    final X509Certificate certificate;
    final Pkcs12Config store;
    final DkimConfig dkim;
    final SmimeSigningConfig smimeSigning;
    final SmimeEncryptionConfig smimeEncryption;
    final OpenPgpSigningConfig pgpSigning;
    final OpenPgpEncryptionConfig pgpEncryption;
    final AttachmentResource resource;

    EmailContractTestSupport(final Path directory) throws Exception {
        textFile = Files.writeString(directory.resolve("text.txt"), TEXT);
        keyFile = Files.write(directory.resolve("key.bin"), KEY_BYTES);
        certificateFile = Files.write(directory.resolve("certificate.pem"), resourceBytes("/pkcs12/smime_test_user.pem.standard.crt"));
        storeFile = Files.write(directory.resolve("store.pkcs12"), resourceBytes("/pkcs12/smime_keystore.pkcs12"));
        try (InputStream input = Files.newInputStream(certificateFile)) {
            certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
        store = Pkcs12Config.builder().pkcs12Store(Files.readAllBytes(storeFile)).storePassword("store-password")
                .keyAlias("alias").keyPassword("key-password").build();
        dkim = DkimConfig.builder().dkimPrivateKeyData(KEY_BYTES).dkimSelector("selector").dkimSigningDomain("example.org").build();
        smimeSigning = SmimeSigningConfig.builder().pkcs12Config(store).build();
        smimeEncryption = SmimeEncryptionConfig.builder().x509Certificate(certificate).build();
        pgpSigning = OpenPgpSigningConfig.builder().secretKeyRing(KEY_BYTES).build();
        pgpEncryption = OpenPgpEncryptionConfig.builder().addRecipientPublicKeyRing(KEY_BYTES).build();
        resource = mail.emailBuilder().startingBlank().withAttachment("file.txt", TEXT.getBytes(StandardCharsets.UTF_8), "text/plain")
                .buildEmail().getAttachments().get(0);
    }

    static Stream<Method> routes(final Set<ConfigurationAdoptionCatalogue.Owner> owners, final Class<?>... contracts) {
        return Arrays.stream(contracts).flatMap(contract -> Arrays.stream(contract.getDeclaredMethods()))
                .filter(method -> ConfigurationAdoptionCatalogue.API_OWNERS.containsKey(ConfigurationAdoptionCatalogue.signature(method)))
                .filter(method -> owners.contains(ConfigurationAdoptionCatalogue.API_OWNERS.get(ConfigurationAdoptionCatalogue.signature(method))))
                .sorted(java.util.Comparator.comparing(ConfigurationAdoptionCatalogue::signature));
    }

    static Stream<Method> routes(final Class<?>... contracts) {
        return routes(Set.of(ConfigurationAdoptionCatalogue.Owner.values()), contracts);
    }

    static Object value(final Object target, final String getter) throws Exception {
        return target.getClass().getMethod(getter).invoke(target);
    }

    static Object captured(final Object target, final String name) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                final java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException absent) {
                // Converter option storage can be inherited from its common source stage.
            }
        }
        throw new AssertionError("No captured member " + name + " on " + target.getClass().getName());
    }

    static byte[] resourceBytes(final String path) throws IOException {
        try (InputStream input = EmailContractTestSupport.class.getResourceAsStream(path)) {
            if (input == null) { throw new IOException("Missing controlled fixture " + path); }
            return input.readAllBytes();
        }
    }

    static final class BorrowedInput extends ByteArrayInputStream {
        boolean closed;
        BorrowedInput(final byte[] bytes) { super(bytes); }
        @Override public void close() { closed = true; }
    }
}
