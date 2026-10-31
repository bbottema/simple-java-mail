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
import org.simplejavamail.api.email.OriginalSmimeDetails;
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
import static org.simplejavamail.api.email.OriginalSmimeDetails.DecryptionStatus.DECRYPTED;
import static org.simplejavamail.api.email.OriginalSmimeDetails.DecryptionStatus.KEY_MISSING;
import static org.simplejavamail.api.email.OriginalSmimeDetails.SmimeMode.ENCRYPTED;
import static org.simplejavamail.api.email.OriginalSmimeDetails.VerificationStatus.NOT_SIGNED;
import static org.simplejavamail.api.email.OriginalSmimeDetails.VerificationStatus.UNKNOWN;
import static org.simplejavamail.api.email.OriginalSmimeDetails.VerificationStatus.VALID;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class NestedSmimeEncryptionInspectionTest {

    @ParameterizedTest(name = "inner type={0}, signed={1}, EML={2}, merge={3}")
    @MethodSource("nestedInputs")
    void unopenedInnerCiphertextKeepsSignatureUnknown(final String innerType, final boolean signed,
            final boolean eml, final boolean merge) throws Exception {
        final byte[] inner = encrypted(content(signed));
        final MimeBodyPart nested = new MimeBodyPart(new ByteArrayInputStream(inner));
        final ContentType type = new ContentType(nested.getContentType());
        type.setSubType(innerType.substring(innerType.indexOf('/') + 1));
        nested.setHeader("Content-Type", type.toString());
        final byte[] source = encrypted(nested);

        final SimpleJavaMail mail = SimpleJavaMail.fromDefaults();
        final EmailPopulatingBuilder builder = eml
                ? mail.converter().fromEml(source).withSmimeDecryption(loadPkcs12KeyStore()).toEmailBuilder()
                : mail.converter().fromMimeMessage(message(source)).withSmimeDecryption(loadPkcs12KeyStore()).toEmailBuilder();
        if (!merge) {
            builder.notMergingSingleSMIMESignedAttachment();
        }
        final Email email = builder.buildEmail();

        assertOuterFacts(email, source);
        assertOuterFacts(mail.emailBuilder().copying(email).buildEmail(), source);
        assertOuterFacts(serializedCopy(email), source);
        final OriginalSmimeDetails unopened = email.getSmimeSignedEmail().getOriginalSmimeDetails();
        assertThat(unopened.getSmimeMode()).isEqualTo(ENCRYPTED);
        assertThat(unopened.getVerificationStatus()).isEqualTo(UNKNOWN);
        assertThat(unopened.getDecryptionStatus()).isEqualTo(KEY_MISSING);

        // The unopened layer can hide either signed or unsigned content; opening it establishes that separate fact.
        final Email openedInner = mail.converter().fromEml(inner).withSmimeDecryption(loadPkcs12KeyStore()).toEmail();
        assertThat(openedInner.getOriginalSmimeDetails().getDecryptionStatus()).isEqualTo(DECRYPTED);
        assertThat(openedInner.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(signed ? VALID : NOT_SIGNED);
    }

    private static Stream<Arguments> nestedInputs() {
        return Stream.of("application/pkcs7-mime", "application/x-pkcs7-mime").flatMap(type ->
                Stream.of(false, true).flatMap(signed -> Stream.of(false, true).flatMap(eml ->
                        Stream.of(false, true).map(merge -> Arguments.of(type, signed, eml, merge)))));
    }

    @Test
    void staticConversionsRetainUnknownVerificationAfterOuterDecryption() throws Exception {
        final byte[] source = encrypted(new MimeBodyPart(new ByteArrayInputStream(encrypted(content(true)))));

        assertOuterFacts(EmailConverter.emlToEmail(new ByteArrayInputStream(source), loadPkcs12KeyStore()), source);
        assertOuterFacts(EmailConverter.mimeMessageToEmail(message(source), loadPkcs12KeyStore()), source);
    }

    @Test
    void missingOuterKeyStillReportsMissingKeyAndUnknownVerification() throws Exception {
        final byte[] source = encrypted(new MimeBodyPart(new ByteArrayInputStream(encrypted(content(true)))));
        final Email email = SimpleJavaMail.fromDefaults().converter().fromEml(source).toEmail();

        assertThat(email.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(UNKNOWN);
        assertThat(email.getOriginalSmimeDetails().getDecryptionStatus()).isEqualTo(KEY_MISSING);
        assertThat(email.getOriginalSmimeDetails().getSmimeSignatureValid()).isNull();
        assertThat(email.getOriginalSmimeDetails().getOriginalProtectedMessage()).containsExactly(source);
    }

    private static void assertOuterFacts(final Email email, final byte[] source) {
        final OriginalSmimeDetails details = email.getOriginalSmimeDetails();
        assertThat(details.getSmimeMode()).isEqualTo(ENCRYPTED);
        assertThat(details.getDecryptionStatus()).isEqualTo(DECRYPTED);
        assertThat(details.getVerificationStatus()).isEqualTo(UNKNOWN);
        assertThat(details.getSmimeSignatureValid()).isNull();
        assertThat(details.getFailureReason()).isNull();
        assertThat(details.getOriginalProtectedMessage()).containsExactly(source);
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
