package org.simplejavamail.internal.smimesupport;

import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder;
import org.bouncycastle.mail.smime.SMIMESignedGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.config.Pkcs12Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.email.OriginalSmimeDetails.VerificationStatus.ERROR;
import static org.simplejavamail.api.email.OriginalSmimeDetails.VerificationStatus.INVALID;
import static org.simplejavamail.api.email.OriginalSmimeDetails.VerificationStatus.VALID;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class DetachedSmimeFilenameCollisionTest {
    private static final String SIGNATURE_NAME = "smime.p7s";
    private static final String SIGNATURE_TYPE = "application/pkcs7-signature";
    private static final String BODY = "Signed message with a separately attached file";
    private static final byte[] FILE_BYTES = "Ordinary file content".getBytes(UTF_8);

    @ParameterizedTest(name = "eager={0}, EML={1}, file type={2}, file name={3}")
    @MethodSource("imports")
    void distinctSourceOccurrencesSurviveMatchingFilenames(final boolean eager, final boolean eml,
            final String fileType, final String fileName) throws Exception {
        final Fixture fixture = signedMessage(fileName, fileType);
        assertThat(SmimeCryptoSupport.checkSignature(message(fixture.wire))).isTrue();

        final Email email = importEmail(fixture.wire, eager, eml);

        assertThat(email.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(VALID);
        assertThat(email.getOriginalSmimeDetails().getOriginalProtectedMessage()).containsExactly(fixture.wire);
        assertThat(email.getPlainText()).contains(BODY);
        assertAttachments(email, fileName, fileType, FILE_BYTES, fixture.signature);
        assertAttachments(SimpleJavaMail.fromDefaults().emailBuilder().copying(email).buildEmail(),
                fileName, fileType, FILE_BYTES, fixture.signature);
        assertAttachments(serializedCopy(email), fileName, fileType, FILE_BYTES, fixture.signature);

        final byte[] exported = SimpleJavaMail.fromDefaults().converter().fromEmail(email).toEmlBytes();
        assertAttachments(importEmail(exported, eager, eml), fileName, fileType, FILE_BYTES, fixture.signature);
    }

    private static Stream<Arguments> imports() {
        return Stream.of(true, false).flatMap(eager -> Stream.of(true, false).flatMap(eml -> Stream.of(
                Arguments.of(eager, eml, "application/octet-stream", SIGNATURE_NAME),
                Arguments.of(eager, eml, SIGNATURE_TYPE, SIGNATURE_NAME),
                Arguments.of(eager, eml, "application/octet-stream", "ordinary.p7s"))));
    }

    @Test
    void identicalMetadataAndBytesStillDescribeTwoSourceOccurrences() throws Exception {
        final Fixture fixture = signedMessage(SIGNATURE_NAME, SIGNATURE_TYPE);
        final MimeMessage changed = message(fixture.wire);
        final MimeMultipart signed = (MimeMultipart) changed.getContent();
        final MimeMultipart clear = (MimeMultipart) signed.getBodyPart(0).getContent();
        final MimeBodyPart file = (MimeBodyPart) clear.getBodyPart(1);
        file.setDataHandler(new jakarta.activation.DataHandler(new ByteArrayDataSource(fixture.signature, SIGNATURE_TYPE)));
        file.setFileName(SIGNATURE_NAME);
        changed.saveChanges();

        final Email email = importEmail(bytes(changed), true, true);

        // Changing the signed file invalidates the signature but does not merge its two source occurrences.
        assertThat(email.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(INVALID);
        assertAttachments(email, SIGNATURE_NAME, SIGNATURE_TYPE, fixture.signature, fixture.signature);
    }

    @Test
    void failedPreprocessingDoesNotAddTheSignatureAlreadyVisitedByTheParser() throws Exception {
        final Fixture fixture = signedMessage(SIGNATURE_NAME, "application/octet-stream");
        final MimeMessage changed = message(fixture.wire);
        final MimeMultipart signed = (MimeMultipart) changed.getContent();
        final MimeBodyPart signature = (MimeBodyPart) signed.getBodyPart(1);
        final byte[] malformedSignature = new byte[] {1, 2, 3};
        signature.setDataHandler(new jakarta.activation.DataHandler(new ByteArrayDataSource(malformedSignature, SIGNATURE_TYPE)));
        signature.setFileName(SIGNATURE_NAME);
        changed.saveChanges();

        final Email email = importEmail(bytes(changed), false, true);

        assertThat(email.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(ERROR);
        assertThat(email.getPlainText()).contains(BODY);
        assertAttachments(email, SIGNATURE_NAME, "application/octet-stream", FILE_BYTES, malformedSignature);
    }

    private static void assertAttachments(final Email email, final String fileName, final String fileType,
            final byte[] fileBytes, final byte[] signatureBytes) throws Exception {
        final List<AttachmentResource> attachments = email.getAttachments();
        assertThat(attachments).extracting(AttachmentResource::getName).containsExactly(fileName, SIGNATURE_NAME);
        assertThat(new ContentType(attachments.get(0).getDataSource().getContentType()).getBaseType()).isEqualTo(fileType);
        assertThat(attachments.get(0).readAllBytes()).containsExactly(fileBytes);
        assertThat(new ContentType(attachments.get(1).getDataSource().getContentType()).getBaseType()).isEqualTo(SIGNATURE_TYPE);
        assertThat(attachments.get(1).readAllBytes()).containsExactly(signatureBytes);
    }

    private static Email importEmail(final byte[] wire, final boolean eager, final boolean eml) throws Exception {
        final SimpleJavaMail mail = SimpleJavaMail.fromDefaults();
        if (!eml) {
            return mail.converter().fromMimeMessage(message(wire)).withFetchAttachmentData(eager).toEmail();
        }
        final BorrowedInput input = new BorrowedInput(wire);
        final Email email = mail.converter().fromEml(input).withFetchAttachmentData(eager).toEmail();
        assertThat(input.closed).isFalse();
        return email;
    }

    private static Fixture signedMessage(final String fileName, final String fileType) throws Exception {
        final Pkcs12Config keys = loadPkcs12KeyStore();
        final KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(keys.getPkcs12StoreData()), keys.getStorePassword());
        final X509Certificate certificate = (X509Certificate) store.getCertificate(keys.getKeyAlias());
        final SMIMESignedGenerator generator = new SMIMESignedGenerator();
        generator.addCertificates(new JcaCertStore(List.of(certificate)));
        generator.addSignerInfoGenerator(new JcaSimpleSignerInfoGeneratorBuilder()
                .setProvider(SmimeCryptoSupport.BOUNCY_CASTLE)
                .build("SHA256withRSA", (PrivateKey) store.getKey(keys.getKeyAlias(), keys.getKeyPassword()), certificate));
        final MimeBodyPart text = new MimeBodyPart();
        text.setText(BODY, "UTF-8");
        final MimeBodyPart file = new MimeBodyPart();
        file.setDataHandler(new jakarta.activation.DataHandler(new ByteArrayDataSource(FILE_BYTES, fileType)));
        file.setFileName(fileName);
        final MimeMultipart clear = new MimeMultipart("mixed");
        clear.addBodyPart(text);
        clear.addBodyPart(file);
        final MimeBodyPart entity = new MimeBodyPart();
        entity.setContent(clear);
        final MimeMultipart signed = generator.generate(entity);
        ((MimeBodyPart) signed.getBodyPart(1)).setFileName(SIGNATURE_NAME);
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom("sender@example.org");
        message.setRecipients(jakarta.mail.Message.RecipientType.TO, "reader@example.org");
        message.setContent(signed);
        message.saveChanges();
        final byte[] wire = bytes(message);
        final byte[] signature;
        try (InputStream input = signed.getBodyPart(1).getInputStream()) {
            signature = input.readAllBytes();
        }
        return new Fixture(wire, signature);
    }

    private static MimeMessage message(final byte[] wire) throws Exception {
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(wire));
    }

    private static byte[] bytes(final MimeMessage message) throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        message.writeTo(output);
        return output.toByteArray();
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

    private static final class Fixture {
        private final byte[] wire;
        private final byte[] signature;

        private Fixture(final byte[] wire, final byte[] signature) {
            this.wire = wire;
            this.signature = signature;
        }
    }

    private static final class BorrowedInput extends ByteArrayInputStream {
        private boolean closed;

        private BorrowedInput(final byte[] bytes) { super(bytes); }
        @Override public void close() { closed = true; }
    }
}
