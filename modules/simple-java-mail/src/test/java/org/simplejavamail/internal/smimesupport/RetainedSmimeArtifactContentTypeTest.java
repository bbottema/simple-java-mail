package org.simplejavamail.internal.smimesupport;

import jakarta.activation.DataHandler;
import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder;
import org.bouncycastle.mail.smime.SMIMESignedGenerator;
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
import static org.simplejavamail.api.email.OriginalSmimeDetails.SmimeMode.PLAIN;
import static org.simplejavamail.api.email.OriginalSmimeDetails.VerificationStatus.VALID;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class RetainedSmimeArtifactContentTypeTest {

    private static final String ARTIFACT_NAME = "signed payload.p7m";
    private static final String SIGNED_BODY = "Retained opaque signed body";

    @ParameterizedTest
    @MethodSource("signedImports")
    void retainedOpaqueArtifactKeepsItsTypeAndBytesAcrossSnapshots(final String mediaType,
            final boolean fetchAttachmentData, final boolean emlInput) throws Exception {
        final MimeBodyPart signed = opaqueSigned();
        final byte[] payload;
        try (InputStream input = signed.getInputStream()) {
            payload = input.readAllBytes();
        }
        final String contentType = mediaType + "; smime-type=signed-data; name=\"" + ARTIFACT_NAME
                + "\"; x-retained=\"one;two\"";
        final MimeMessage source = message(payload, contentType);
        final byte[] originalEml = serialize(source);
        final SimpleJavaMail mail = SimpleJavaMail.fromDefaults();

        final Email received = emlInput
                ? mail.converter().fromEml(originalEml).withFetchAttachmentData(fetchAttachmentData).toEmail()
                : mail.converter().fromMimeMessage(source).withFetchAttachmentData(fetchAttachmentData).toEmail();

        assertThat(received.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(VALID);
        assertThat(received.getOriginalSmimeDetails().getOriginalProtectedMessage()).containsExactly(originalEml);
        assertArtifact(received, mediaType, "signed-data", payload);
        assertArtifact(mail.emailBuilder().copying(received).buildEmail(), mediaType, "signed-data", payload);
        assertArtifact(roundTrip(received), mediaType, "signed-data", payload);
    }

    private static Stream<Arguments> signedImports() {
        return Stream.of("application/pkcs7-mime", "Application/X-PKCS7-Mime")
                .flatMap(type -> Stream.of(true, false)
                        .flatMap(eager -> Stream.of(true, false).map(eml -> Arguments.of(type, eager, eml))));
    }

    @ParameterizedTest
    @MethodSource("mediaTypes")
    void reusedOpaqueArtifactIsStillReadAsSignedContent(final String mediaType) throws Exception {
        final MimeBodyPart signed = opaqueSigned();
        final byte[] payload;
        try (InputStream input = signed.getInputStream()) {
            payload = input.readAllBytes();
        }
        final SimpleJavaMail mail = SimpleJavaMail.fromDefaults();
        final Email received = mail.converter().fromMimeMessage(message(payload,
                mediaType + "; smime-type=signed-data; name=\"" + ARTIFACT_NAME + "\"")).toEmail();
        final Email forwarding = mail.emailBuilder().startingBlank()
                .from("sender@example.org").to("reader@example.org")
                .withPlainText("Ordinary parent")
                .withAttachment(artifact(received))
                .withAttachment("note.txt", "Ordinary note".getBytes(UTF_8), "text/plain; charset=UTF-8")
                .buildEmail();

        final Email imported = mail.converter().fromMimeMessage(mail.converter().fromEmail(forwarding).toMimeMessage()).toEmail();

        assertThat(imported.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(PLAIN);
        final AttachmentResource clear = imported.getDecryptedAttachments().stream()
                .filter(attachment -> "signed-email.eml".equals(attachment.getName()))
                .findFirst().orElseThrow(IllegalStateException::new);
        assertThat(mail.converter().fromEml(clear.readAllBytes()).toEmail().getPlainText()).contains(SIGNED_BODY);
    }

    @ParameterizedTest
    @MethodSource("mediaTypes")
    void unopenedEncryptedArtifactAlsoKeepsItsParameters(final String mediaType) throws Exception {
        final Pkcs12Config keys = loadPkcs12KeyStore();
        final KeyStore store = store(keys);
        final MimeBodyPart plain = new MimeBodyPart();
        plain.setText("Encrypted control", "UTF-8");
        final MimeBodyPart encrypted = SmimeCryptoSupport.encrypt(plain,
                (X509Certificate) store.getCertificate(keys.getKeyAlias()),
                SmimeCryptoSupport.DEFAULT_KEY_ENCAPSULATION_ALGORITHM, SmimeCryptoSupport.DEFAULT_CIPHER);
        final byte[] payload;
        try (InputStream input = encrypted.getInputStream()) {
            payload = input.readAllBytes();
        }
        final Email received = SimpleJavaMail.fromDefaults().converter().fromMimeMessage(message(payload,
                mediaType + "; smime-type=enveloped-data; name=\"" + ARTIFACT_NAME
                        + "\"; x-retained=\"one;two\"")).toEmail();

        assertArtifact(received, mediaType, "enveloped-data", payload);
    }

    private static Stream<String> mediaTypes() {
        return Stream.of("application/pkcs7-mime", "Application/X-PKCS7-Mime");
    }

    private static void assertArtifact(final Email email, final String mediaType,
            final String smimeType, final byte[] payload) throws Exception {
        final AttachmentResource retained = artifact(email);
        final ContentType type = new ContentType(retained.getDataSource().getContentType());
        assertThat(type.getBaseType()).isEqualToIgnoringCase(mediaType);
        assertThat(type.getParameter("smime-type")).isEqualTo(smimeType);
        assertThat(type.getParameter("name")).isEqualTo(ARTIFACT_NAME);
        assertThat(type.getParameter("x-retained")).isEqualTo("one;two");
        assertThat(retained.readAllBytes()).containsExactly(payload);
    }

    private static AttachmentResource artifact(final Email email) {
        return email.getAttachments().stream().filter(attachment -> ARTIFACT_NAME.equals(attachment.getName()))
                .findFirst().orElseThrow(IllegalStateException::new);
    }

    private static MimeBodyPart opaqueSigned() throws Exception {
        final Pkcs12Config keys = loadPkcs12KeyStore();
        final KeyStore store = store(keys);
        final X509Certificate certificate = (X509Certificate) store.getCertificate(keys.getKeyAlias());
        final SMIMESignedGenerator generator = new SMIMESignedGenerator();
        generator.addCertificates(new JcaCertStore(List.of(certificate)));
        generator.addSignerInfoGenerator(new JcaSimpleSignerInfoGeneratorBuilder()
                .setProvider(SmimeCryptoSupport.BOUNCY_CASTLE)
                .build("SHA256withRSA", (PrivateKey) store.getKey(keys.getKeyAlias(), keys.getKeyPassword()), certificate));
        final MimeBodyPart text = new MimeBodyPart();
        text.setText(SIGNED_BODY, "UTF-8");
        final MimeMultipart multipart = new MimeMultipart();
        multipart.addBodyPart(text);
        final MimeBodyPart clear = new MimeBodyPart();
        clear.setContent(multipart);
        return generator.generateEncapsulated(clear);
    }

    private static KeyStore store(final Pkcs12Config keys) throws Exception {
        final KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(keys.getPkcs12StoreData()), keys.getStorePassword());
        return store;
    }

    private static MimeMessage message(final byte[] payload, final String contentType) throws Exception {
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom("sender@example.org");
        message.setRecipients(jakarta.mail.Message.RecipientType.TO, "reader@example.org");
        message.setDataHandler(new DataHandler(new ByteArrayDataSource(payload, contentType)));
        message.saveChanges();
        return message;
    }

    private static byte[] serialize(final MimeMessage message) throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        message.writeTo(output);
        return output.toByteArray();
    }

    private static Email roundTrip(final Email email) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(email);
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (Email) input.readObject();
        }
    }
}
