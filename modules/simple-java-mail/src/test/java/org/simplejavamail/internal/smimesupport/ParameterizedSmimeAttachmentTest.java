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
import org.simplejavamail.api.email.SignatureStatus;
import org.simplejavamail.api.email.EncryptionStatus;
import org.simplejavamail.api.email.DecryptionStatus;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.mailer.config.Pkcs12Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class ParameterizedSmimeAttachmentTest {

    private static final String SIGNED_BODY = "Independent signed content";
    private static final String TAMPERED_BODY = "Independent signed altered";

    @ParameterizedTest
    @MethodSource("encryptedImports")
    void requestedDecryptionOpensParameterizedAttachmentsWithoutChangingTheirSource(final String mediaType,
            final boolean fetchAttachmentData, final boolean emlInput) throws Exception {
        final MimeBodyPart protectedFile = new MimeBodyPart(new ByteArrayInputStream(Files.readAllBytes(
                resource("pkcs12/test messages/S_MIME test message encrypted.eml"))));
        protectedFile.setHeader("Content-Type", mediaType + "; smime-type=enveloped-data; name=protected.p7m; x-source=retained");
        protectedFile.setFileName("protected.p7m");
        final byte[] protectedBytes;
        try (InputStream input = protectedFile.getInputStream()) {
            protectedBytes = input.readAllBytes();
        }
        final MimeMessage source = parent(protectedFile);

        final Email imported = importEmail(source, fetchAttachmentData, emlInput);

        assertPlainParent(imported);
        assertThat(imported.getAttachments()).hasSize(2);
        final AttachmentResource original = imported.getInspection().getOriginalAttachment(0);
        assertThat(original.readAllBytes()).containsExactly(protectedBytes);
        final ContentType originalType = new ContentType(original.getDataSource().getContentType());
        assertThat(originalType.getBaseType()).isEqualToIgnoringCase(mediaType);
        assertThat(originalType.getParameter("smime-type")).isEqualTo("enveloped-data");
        assertThat(originalType.getParameter("name")).isEqualTo("protected.p7m");
        assertThat(originalType.getParameter("x-source")).isEqualTo("retained");
        final AttachmentResource decrypted = imported.getAttachments().get(0);
        assertThat(decrypted.getEmail()).isNull();
        assertThat(readSelectedResource(decrypted).getPlainText()).contains("This is an encrypted message");
        assertThat(imported.getInspection().getAttachment(0).getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
        assertThat(imported.getAttachments().get(1).readAllData(UTF_8)).isEqualTo("Ordinary note");
        final Email copy = SimpleJavaMail.fromDefaults().emailBuilder().copying(imported).buildEmail();
        assertThat(copy.getInspection().getOriginalAttachment(0).readAllBytes()).containsExactly(protectedBytes);
        assertThat(copy.getAttachments().get(0).readAllBytes()).containsExactly(decrypted.readAllBytes());
    }

    private static Stream<Arguments> encryptedImports() {
        return Stream.of("application/pkcs7-mime", "Application/X-PKCS7-Mime")
                .flatMap(type -> Stream.of(true, false)
                        .flatMap(eager -> Stream.of(true, false).map(eml -> Arguments.of(type, eager, eml))));
    }

    @ParameterizedTest
    @MethodSource("signedImports")
    void parameterizedSignedAttachmentsAreInspected(final boolean fetchAttachmentData,
            final boolean emlInput, final boolean opaque, final boolean tampered) throws Exception {
        final MimeBodyPart protectedFile = signedAttachment(opaque, tampered);
        protectedFile.setFileName("protected.mime");
        final MimeMessage source = parent(protectedFile);
        final ContentType suppliedType = new ContentType(protectedFile.getContentType());
        assertThat(suppliedType.getBaseType()).isEqualToIgnoringCase(opaque ? "application/pkcs7-mime" : "multipart/signed");
        if (!tampered) {
            assertThat(SmimeCryptoSupport.checkSignature(protectedFile)).isTrue();
        }

        final Email imported = importEmail(source, fetchAttachmentData, emlInput);

        assertPlainParent(imported);
        assertThat(imported.getAttachments()).hasSize(2);
        final AttachmentResource selected = imported.getAttachments().get(0);
        assertThat(selected.getEmail()).isNull();
        assertThat(readSelectedResource(selected).getPlainText()).contains(tampered ? TAMPERED_BODY : SIGNED_BODY);
        assertThat(imported.getInspection().getAttachment(0).getSignatureStatus())
                .isEqualTo(tampered ? SignatureStatus.INVALID : SignatureStatus.VERIFIED);
        final ContentType retainedType = new ContentType(imported.getInspection().getOriginalAttachment(0).getDataSource().getContentType());
        assertThat(retainedType.getParameter("boundary")).isEqualTo(suppliedType.getParameter("boundary"));
        assertThat(retainedType.getParameter("protocol")).isEqualTo(suppliedType.getParameter("protocol"));
    }

    private static Stream<Arguments> signedImports() {
        return Stream.of(true, false).flatMap(eager -> Stream.of(true, false)
                .flatMap(eml -> Stream.of(true, false).flatMap(opaque -> Stream.of(false, true)
                        .map(tampered -> Arguments.of(eager, eml, opaque, tampered)))));
    }

    @Test
    void malformedEncryptedFileKeepsOriginalAndReadableNeighbors() throws Exception {
        final MimeBodyPart broken = new MimeBodyPart();
        broken.setDataHandler(new jakarta.activation.DataHandler(new ByteArrayDataSource(new byte[] {1, 2, 3},
                "application/pkcs7-mime; smime-type=enveloped-data; name=broken.p7m")));
        broken.setFileName("broken.p7m");
        final MimeMessage source = parent(broken);

        final Email imported = importEmail(source, true, true);
        assertPlainParent(imported);
        assertThat(imported.getAttachments()).hasSize(2);
        assertThat(imported.getAttachments().get(0).readAllBytes()).containsExactly(1, 2, 3);
        assertThat(imported.getAttachments().get(1).readAllData(UTF_8)).isEqualTo("Ordinary note");
        assertThat(imported.getProblems()).anyMatch(problem -> problem.getCode() == EmailProblem.Code.PROTECTION_FAILED);
        assertThat(imported.getInspection().getAttachment(0).getDecryptions()).anySatisfy(attempt ->
                assertThat(attempt.getStatus()).isEqualTo(DecryptionStatus.FAILED));
    }

    @Test
    void unrelatedMediaTypesDoNotBecomeSmime() throws Exception {
        for (String type : new String[] {"application/octet-stream; name=smime.p7m", "text/plain; charset=UTF-8",
                "multipart/signed; protocol=\"application/pgp-signature\"; boundary=other",
                "application/pkcs7-mime-extra; name=smime.p7m"}) {
            assertThat(SmimeRecognitionUtil.isSmimeContentType(new ContentType(type))).as(type).isFalse();
        }
    }

    @Test
    void malformedDeclarationsAreRejectedByTheOwningMimeGrammar() {
        for (String type : new String[] {"not-a-content-type", "application/pkcs7-mime; =broken"}) {
            assertThatThrownBy(() -> new ContentType(type)).isInstanceOf(jakarta.mail.internet.ParseException.class);
        }
    }

    private static Email importEmail(final MimeMessage source, final boolean fetchAttachmentData,
            final boolean emlInput) throws Exception {
        if (!emlInput) {
            return SimpleJavaMail.fromDefaults().converter().fromMimeMessage(source)
                    .withFetchAttachmentData(fetchAttachmentData).withSmimeDecryption(loadPkcs12KeyStore()).toEmail();
        }
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        source.writeTo(output);
        final BorrowedInput input = new BorrowedInput(output.toByteArray());
        final Email imported = SimpleJavaMail.fromDefaults().converter().fromEml(input)
                .withFetchAttachmentData(fetchAttachmentData).withSmimeDecryption(loadPkcs12KeyStore()).toEmail();
        assertThat(input.closed).isFalse();
        return imported;
    }

    private static MimeMessage parent(final MimeBodyPart protectedFile) throws Exception {
        final MimeBodyPart text = new MimeBodyPart();
        text.setText("Plain parent body", "UTF-8");
        final MimeBodyPart note = new MimeBodyPart();
        note.setText("Ordinary note", "UTF-8");
        note.setFileName("note.txt");
        final MimeMultipart content = new MimeMultipart("mixed");
        content.addBodyPart(text);
        content.addBodyPart(protectedFile);
        content.addBodyPart(note);
        final Properties properties = new Properties();
        properties.setProperty("mail.host", "localhost");
        final MimeMessage message = new MimeMessage(Session.getInstance(properties));
        message.setFrom("sender@example.org");
        message.setRecipients(jakarta.mail.Message.RecipientType.TO, "recipient@example.org");
        message.setContent(content);
        message.saveChanges();
        return message;
    }

    private static MimeBodyPart signedAttachment(final boolean opaque, final boolean tampered) throws Exception {
        final Pkcs12Config keys = loadPkcs12KeyStore();
        final KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(keys.getPkcs12StoreData()), keys.getStorePassword());
        final X509Certificate certificate = (X509Certificate) store.getCertificate(keys.getKeyAlias());
        final SMIMESignedGenerator generator = new SMIMESignedGenerator();
        generator.addCertificates(new JcaCertStore(List.of(certificate)));
        generator.addSignerInfoGenerator(new JcaSimpleSignerInfoGeneratorBuilder().setProvider(SmimeCryptoSupport.BOUNCY_CASTLE)
                .build("SHA256withRSA", (PrivateKey) store.getKey(keys.getKeyAlias(), keys.getKeyPassword()), certificate));
        final MimeBodyPart text = new MimeBodyPart();
        text.setText(SIGNED_BODY, "UTF-8");
        final MimeMultipart multipart = new MimeMultipart();
        multipart.addBodyPart(text);
        final MimeBodyPart content = new MimeBodyPart();
        content.setContent(multipart);
        final MimeBodyPart result;
        if (opaque) {
            result = generator.generateEncapsulated(content);
        } else {
            result = new MimeBodyPart();
            result.setContent(generator.generate(content));
        }
        if (tampered) {
            final String contentType = result.getDataHandler().getContentType();
            final String original;
            try (InputStream input = result.getInputStream()) {
                original = new String(input.readAllBytes(), ISO_8859_1);
            }
            assertThat(original).contains(SIGNED_BODY);
            result.setDataHandler(new jakarta.activation.DataHandler(new ByteArrayDataSource(
                    original.replace(SIGNED_BODY, TAMPERED_BODY).getBytes(ISO_8859_1), contentType)));
            result.setHeader("Content-Type", contentType);
        }
        return result;
    }

    private static Email readSelectedResource(AttachmentResource resource) throws Exception {
        MimeMessage adapter = new MimeMessage(Session.getInstance(new Properties()));
        adapter.setDataHandler(new jakarta.activation.DataHandler(resource.getDataSource()));
        adapter.setHeader("Content-Type", resource.getDataSource().getContentType());
        return SimpleJavaMail.fromDefaults().converter().fromMimeMessage(adapter).toEmail();
    }

    private static void assertPlainParent(final Email email) {
        assertThat(email.getPlainText()).contains("Plain parent body");
        assertThat(email.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.UNSIGNED);
        assertThat(email.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(EncryptionStatus.NOT_ENCRYPTED);
    }

    private static Path resource(final String relative) {
        return Path.of(determineResourceFolder("simple-java-mail"), "test/resources", relative);
    }

    private static final class BorrowedInput extends ByteArrayInputStream {
        private boolean closed;

        private BorrowedInput(final byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
