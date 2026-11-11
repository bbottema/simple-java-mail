package org.simplejavamail.internal.smimesupport;

import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.DecryptionStatus;
import org.simplejavamail.api.email.EncryptionStatus;
import org.simplejavamail.api.email.SignatureStatus;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.converter.config.ImportLimitsConfig;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.converter.EmailConverter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Properties;
import java.util.stream.Stream;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;
import static org.simplejavamail.internal.smimesupport.SmimeInspectionAssertions.*;

class NestedSmimeEncryptionInspectionTest {

    @ParameterizedTest(name = "inner type={0}, signed={1}, EML={2}")
    @MethodSource("nestedInputs")
    void eachSameMessageEnvelopeIsOpenedWithoutInventingASeparateEmail(final String innerType, final boolean signed,
            final boolean eml) throws Exception {
        final byte[] inner = encrypted(content(signed));
        final MimeBodyPart nested = new MimeBodyPart(new ByteArrayInputStream(inner));
        final ContentType type = new ContentType(nested.getContentType());
        type.setSubType(innerType.substring(innerType.indexOf('/') + 1));
        nested.setHeader("Content-Type", type.toString());
        final byte[] source = encrypted(nested);
        final SimpleJavaMail mail = SimpleJavaMail.fromDefaults();
        final Email email = eml
                ? mail.converter().fromEml(source).withSmimeDecryption(loadPkcs12KeyStore()).toEmail()
                : mail.converter().fromMimeMessage(message(source)).withSmimeDecryption(loadPkcs12KeyStore()).toEmail();

        assertOpenedLayers(email, signed, eml ? source : null);
        assertOpenedLayers(mail.emailBuilder().copying(email).buildEmail(), signed, eml ? source : null);
        assertOpenedLayers(serializedCopy(email), signed, eml ? source : null);
        final Email openedInner = mail.converter().fromEml(inner).withSmimeDecryption(loadPkcs12KeyStore()).toEmail();
        assertPlainProtection(openedInner, signed ? SignatureStatus.VERIFIED : SignatureStatus.UNSIGNED, EncryptionStatus.DECRYPTED);
        assertThat(openedInner.getInspection().getSmime().getDecryptions()).hasSize(1);
    }

    private static Stream<Arguments> nestedInputs() {
        return Stream.of("application/pkcs7-mime", "application/x-pkcs7-mime").flatMap(type ->
                Stream.of(false, true).flatMap(signed -> Stream.of(false, true).map(eml -> Arguments.of(type, signed, eml))));
    }

    @Test
    void staticConversionsOpenBothLayersAndKeepOriginalOnlyForRawInput() throws Exception {
        final byte[] source = encrypted(new MimeBodyPart(new ByteArrayInputStream(encrypted(content(true)))));
        assertOpenedLayers(EmailConverter.emlToEmail(new ByteArrayInputStream(source), loadPkcs12KeyStore()), true, source);
        assertOpenedLayers(EmailConverter.mimeMessageToEmail(message(source), loadPkcs12KeyStore()), true, null);
    }

    @Test
    void sharedPartAllowanceStopsAnInnerLayerWithoutCallingItUnsigned() throws Exception {
        final byte[] source = encrypted(new MimeBodyPart(new ByteArrayInputStream(encrypted(content(true)))));
        final Email email = SimpleJavaMail.fromDefaults().converter().fromEml(source)
                .withSmimeDecryption(loadPkcs12KeyStore())
                .withImportLimitsConfig(ImportLimitsConfig.builder().withMaximumPartCount(2).build()).toEmail();
        assertThat(email.getProblems()).anyMatch(problem -> problem.getCode() == EmailProblem.Code.IMPORT_LIMIT_EXCEEDED);
        assertThat(email.getInspection().getSmime().isInspectionComplete()).isFalse();
        assertThat(email.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
        assertThat(email.getInspection().getSmime().getDecryptions()).noneSatisfy(attempt ->
                assertThat(attempt.getStatus()).isEqualTo(DecryptionStatus.FAILED));
        assertOriginal(email, source);
    }

    @Test
    void missingOuterKeyKeepsUnknownInnerSignatureAndCompleteInput() throws Exception {
        final byte[] source = encrypted(new MimeBodyPart(new ByteArrayInputStream(encrypted(content(true)))));
        final Email email = SimpleJavaMail.fromDefaults().converter().fromEml(source).toEmail();
        assertUnopenedFamily(email, DecryptionStatus.UNAVAILABLE);
        assertThat(email.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
        assertOriginal(email, source);
    }

    private static void assertOpenedLayers(final Email email, final boolean signed, final byte[] original) {
        assertPlainProtection(email, signed ? SignatureStatus.VERIFIED : SignatureStatus.UNSIGNED, EncryptionStatus.DECRYPTED);
        assertThat(email.getPlainText()).contains(signed ? "For testing purposes" : "Unsigned content inside both encrypted envelopes");
        assertThat(email.getInspection().getSmime().getDecryptions()).hasSize(2)
                .allSatisfy(attempt -> assertThat(attempt.getStatus()).isEqualTo(DecryptionStatus.DECRYPTED));
        if (original == null) assertThat(email.getInspection().getOriginalMessage()).isNull();
        else assertOriginal(email, original);
    }

    private static MimeBodyPart content(final boolean signed) throws Exception {
        if (signed) {
            final Path file = Path.of(determineResourceFolder("simple-java-mail"),
                    "test/resources/pkcs12/test messages/S_MIME test message signed.eml");
            return new MimeBodyPart(new ByteArrayInputStream(Files.readAllBytes(file)));
        }
        final MimeBodyPart plain = new MimeBodyPart();
        plain.setText("Unsigned content inside both encrypted envelopes", "UTF-8");
        return plain;
    }

    private static byte[] encrypted(final MimeBodyPart content) throws Exception {
        final Pkcs12Config keys = loadPkcs12KeyStore();
        final KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(keys.getPkcs12StoreData()), keys.getStorePassword());
        final X509Certificate certificate = (X509Certificate) store.getCertificate(keys.getKeyAlias());
        final MimeBodyPart encrypted = SmimeCryptoSupport.encrypt(content, certificate,
                SmimeCryptoSupport.DEFAULT_KEY_ENCAPSULATION_ALGORITHM, SmimeCryptoSupport.DEFAULT_CIPHER);
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom("sender@example.org");
        message.setSubject("Nested S/MIME encryption");
        message.setDataHandler(encrypted.getDataHandler());
        message.saveChanges();
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        message.writeTo(output);
        return output.toByteArray();
    }

    private static MimeMessage message(final byte[] source) throws Exception {
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(source));
    }

    private static Email serializedCopy(final Email email) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(email);
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (Email) input.readObject();
        }
    }
}
