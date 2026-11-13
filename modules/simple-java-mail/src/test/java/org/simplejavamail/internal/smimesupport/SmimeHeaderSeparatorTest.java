package org.simplejavamail.internal.smimesupport;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.converter.EmailConverter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Properties;
import java.util.stream.Stream;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.email.DecryptionStatus.DECRYPTED;
import static org.simplejavamail.api.email.DecryptionStatus.FAILED;
import static org.simplejavamail.api.email.DecryptionStatus.UNAVAILABLE;
import static org.simplejavamail.api.email.SignatureStatus.UNSIGNED;
import static org.simplejavamail.api.email.SignatureStatus.VERIFIED;
import static org.simplejavamail.internal.smimesupport.SmimeInspectionAssertions.*;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class SmimeHeaderSeparatorTest {

    private static Pkcs12Config receivingKeys;
    private static byte[] unsignedEncrypted;
    private static byte[] signedEncrypted;

    @BeforeAll
    static void prepareEncryptedMessages() throws Exception {
        receivingKeys = loadPkcs12KeyStore();
        final KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(receivingKeys.getPkcs12StoreData()), receivingKeys.getStorePassword());
        final X509Certificate certificate = (X509Certificate) store.getCertificate(receivingKeys.getKeyAlias());
        final MimeBodyPart plain = new MimeBodyPart();
        plain.setText("Readable encrypted content", "UTF-8");
        unsignedEncrypted = encrypted(plain, certificate);
        final Path signedFile = Path.of(determineResourceFolder("simple-java-mail"),
                "test/resources/pkcs12/test messages/S_MIME test message signed.eml");
        signedEncrypted = encrypted(new MimeBodyPart(new ByteArrayInputStream(Files.readAllBytes(signedFile))), certificate);
    }

    @ParameterizedTest(name = "signed={0}, {1}, {2}")
    @MethodSource("encryptedImports")
    void firstOuterSeparatorKeepsCompleteCiphertext(final boolean signed, final LineEndings endings,
            final ImportRoute route) throws Exception {
        final byte[] original = signed ? signedEncrypted : unsignedEncrypted;
        final byte[] incoming = endings.apply(original);
        // The later empty base64 line is harmless: Jakarta Mail decodes the same complete ciphertext.
        assertThat(decodedCiphertext(incoming)).containsExactly(decodedCiphertext(original));

        final Email imported = route.read(incoming);

        assertDecryptionObservation(imported, DECRYPTED);
        assertThat(imported.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(org.simplejavamail.api.email.EncryptionStatus.DECRYPTED);
        assertThat(imported.getInspection().getPlainText().getSignatureStatus()).isEqualTo(signed ? VERIFIED : UNSIGNED);
        assertThat(imported.getInspection().getSmime().getSignatures()).allSatisfy(signature -> assertThat(signature.getFailureReason()).isNull());
        assertThat(imported.getPlainText()).contains(signed ? "For testing purposes" : "Readable encrypted content");
        assertOriginal(imported, incoming);
    }

    private static Stream<Arguments> encryptedImports() {
        // Both fluent routes cover every separator; the static stream route retains CRLF and all-LF for each payload.
        return Stream.of(false, true).flatMap(signed -> Arrays.stream(LineEndings.values())
                .flatMap(endings -> Arrays.stream(ImportRoute.values())
                        .filter(route -> route != ImportRoute.STATIC_STREAM || endings == LineEndings.CRLF || endings == LineEndings.ALL_LF)
                        .map(route -> Arguments.of(signed, endings, route))));
    }

    @Test
    void missingKeysKeepTheUnopenedSource() {
        final byte[] incoming = LineEndings.LF_OUTER.apply(signedEncrypted);
        final Email imported = SimpleJavaMail.fromDefaults().converter().fromEml(incoming).toEmail();

        assertUnopenedFamily(imported, UNAVAILABLE);
        assertOriginal(imported, incoming);
    }

    @Test
    void malformedCiphertextStillReportsFailedDecryption() {
        final byte[] incoming = ("From: sender@example.org\n"
                + "Content-Type: application/pkcs7-mime; smime-type=enveloped-data\n"
                + "Content-Transfer-Encoding: base64\n\nAQID\r\n").getBytes(ISO_8859_1);
        final Email imported = ImportRoute.FLUENT_BYTES.read(incoming);

        assertUnopenedFamily(imported, FAILED);
        assertOriginal(imported, incoming);
    }

    @Test
    void toleratingImportedLineEndingsDoesNotRelaxExactSubmission() {
        final byte[] incoming = LineEndings.LF_OUTER.apply(unsignedEncrypted);
        assertThatThrownBy(() -> SimpleJavaMail.fromDefaults().emailBuilder().startingFromExactEml(incoming)
                .withEnvelopeRecipients("receiver@example.org").buildEmail())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("CRLF");
    }

    private static byte[] encrypted(final MimeBodyPart content, final X509Certificate certificate) throws Exception {
        final MimeBodyPart encrypted = SmimeCryptoSupport.encrypt(content, certificate,
                SmimeCryptoSupport.DEFAULT_KEY_ENCAPSULATION_ALGORITHM, SmimeCryptoSupport.DEFAULT_CIPHER);
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom("sender@example.org");
        message.setSubject("Protected header boundary");
        message.setDataHandler(encrypted.getDataHandler());
        message.saveChanges();
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        message.writeTo(output);
        return output.toByteArray();
    }

    private static MimeMessage message(final byte[] source) throws Exception {
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(source));
    }

    private static byte[] decodedCiphertext(final byte[] source) throws Exception {
        try (InputStream input = message(source).getInputStream()) {
            return input.readAllBytes();
        }
    }

    private enum ImportRoute {
        FLUENT_BYTES, FLUENT_STREAM, STATIC_STREAM;

        Email read(final byte[] incoming) {
            switch (this) {
                case FLUENT_BYTES:
                    return SimpleJavaMail.fromDefaults().converter().fromEml(incoming).withSmimeDecryption(receivingKeys).toEmail();
                case FLUENT_STREAM:
                    return SimpleJavaMail.fromDefaults().converter().fromEml(new ByteArrayInputStream(incoming))
                            .withSmimeDecryption(receivingKeys).toEmail();
                case STATIC_STREAM:
                    return EmailConverter.emlToEmail(new ByteArrayInputStream(incoming), receivingKeys);
                default:
                    throw new AssertionError(this);
            }
        }
    }

    private enum LineEndings {
        CRLF, LF_OUTER, CRLF_THEN_LF, LF_THEN_CRLF, MIXED_HEADERS, ALL_LF;

        byte[] apply(final byte[] source) {
            final String message = new String(source, ISO_8859_1);
            final int separator = message.indexOf("\r\n\r\n");
            assertThat(separator).isPositive();
            final String headers = message.substring(0, separator);
            final String ciphertext = message.substring(separator + 4);
            assertThat(ciphertext.length()).isGreaterThan(64);
            final String body = ciphertext.substring(0, 64) + "\r\n\r\n" + ciphertext.substring(64);
            switch (this) {
                case CRLF:
                    return (headers + "\r\n\r\n" + body).getBytes(ISO_8859_1);
                case LF_OUTER:
                    return (headers.replace("\r\n", "\n") + "\n\n" + body).getBytes(ISO_8859_1);
                case CRLF_THEN_LF:
                    return (headers + "\r\n\n" + body).getBytes(ISO_8859_1);
                case LF_THEN_CRLF:
                    return (headers.replace("\r\n", "\n") + "\n\r\n" + body).getBytes(ISO_8859_1);
                case MIXED_HEADERS:
                    return (headers.replaceFirst("\r\n", "\n") + "\n\n" + body).getBytes(ISO_8859_1);
                case ALL_LF:
                    return (headers + "\r\n\r\n" + body).replace("\r\n", "\n").getBytes(ISO_8859_1);
                default:
                    throw new AssertionError(this);
            }
        }
    }
}
