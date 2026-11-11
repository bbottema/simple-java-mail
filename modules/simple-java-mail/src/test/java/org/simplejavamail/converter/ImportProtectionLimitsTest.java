package org.simplejavamail.converter;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.mail.smime.SMIMESignedGenerator;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.converter.config.ImportLimitsConfig;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.email.SignatureStatus;
import org.simplejavamail.api.email.DecryptionStatus;
import org.simplejavamail.api.email.EncryptionStatus;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.smimesupport.ProtectedCalendarFixture;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class ImportProtectionLimitsTest {
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

    @Test
    void limitedUnrecognizedProtectionDiagnosticsDoNotReadTheOmittedChild() throws Exception {
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        final MimeMultipart parts = new MimeMultipart("signed");
        final MimeBodyPart body = new MimeBodyPart();
        body.setText("Readable first child", "UTF-8");
        parts.addBodyPart(body);
        final MimeBodyPart omitted = new MimeBodyPart();
        omitted.setDisposition("attachment");
        omitted.setFileName("omitted.bin");
        omitted.setDataHandler(new jakarta.activation.DataHandler(new jakarta.activation.DataSource() {
            @Override public InputStream getInputStream() { throw new AssertionError("Omitted child was read for diagnostics"); }
            @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
            @Override public String getContentType() { return "application/octet-stream"; }
            @Override public String getName() { return "omitted.bin"; }
        }));
        parts.addBodyPart(omitted);
        message.setContent(parts);
        message.setHeader("Content-Type", parts.getContentType() + "; protocol=\"application/unknown-signature\"");

        final Email imported = mail.converter().fromMimeMessage(message)
                .withImportLimitsConfig(ImportLimitsConfig.builder().withMaximumPartCount(2).build()).toEmail();

        assertThat(imported.getPlainText()).contains("Readable first child");
        assertThat(imported.getAttachments()).isEmpty();
        assertThat(imported.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
        assertThat(imported.getSourceData().getHeaders()).filteredOn(header -> header.getName().equalsIgnoreCase("Content-Type"))
                .anySatisfy(header -> assertThat(header.getRawValue()).contains("application/unknown-signature"));
        assertThat(imported.getInspection().getOriginalMessage()).isNull();
        assertThat(imported.getProblems()).anySatisfy(problem -> assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.IMPORT_LIMIT_EXCEEDED));
    }

    @Test
    void limitedUnrecognizedProtectionKeepsAlreadyCapturedRawBytes() {
        final byte[] source = ("From: sender@example.org\r\nContent-Type: multipart/signed; boundary=parts; "
                + "protocol=\"application/unknown-signature\"\r\n\r\n"
                + "--parts\r\nContent-Type: text/plain\r\n\r\nReadable first child\r\n"
                + "--parts\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=omitted.bin\r\n\r\n"
                + "omitted payload\r\n--parts--\r\n").getBytes(StandardCharsets.US_ASCII);

        final Email imported = mail.converter().fromEml(source)
                .withImportLimitsConfig(ImportLimitsConfig.builder().withMaximumPartCount(2).build()).toEmail();

        assertThat(imported.getPlainText()).contains("Readable first child");
        assertThat(imported.getAttachments()).isEmpty();
        assertThat(imported.getInspection().getOriginalMessage().readAllBytes()).containsExactly(source);
        assertThat(imported.getProblems()).anySatisfy(problem -> assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.IMPORT_LIMIT_EXCEEDED));
    }

    @Test
    void omittingANeighborDoesNotTurnAnIndependentSignedFileIntoParentProtection() throws Exception {
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom("sender@example.org");
        message.setRecipients(jakarta.mail.Message.RecipientType.TO, "reader@example.org");
        message.setSubject("Plain parent");
        final MimeMultipart parts = new MimeMultipart("mixed");
        final MimeBodyPart body = new MimeBodyPart();
        body.setText("Plain parent body", "UTF-8");
        parts.addBodyPart(body);
        final MimeBodyPart signedFile = opaqueSignedFile();
        signedFile.setDisposition("attachment");
        signedFile.setFileName("independent.p7m");
        parts.addBodyPart(signedFile);
        final MimeBodyPart omitted = new MimeBodyPart();
        omitted.setText("Other independent file");
        omitted.setDisposition("attachment");
        omitted.setFileName("omitted.txt");
        parts.addBodyPart(omitted);
        message.setContent(parts);
        message.saveChanges();

        final Email imported = mail.converter().fromMimeMessage(message)
                .withImportLimitsConfig(ImportLimitsConfig.builder().withMaximumPartCount(3).build()).toEmail();

        assertThat(imported.getPlainText()).contains("Plain parent body").doesNotContain("Independent child body");
        assertThat(imported.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.UNSIGNED);
        assertThat(imported.getInspection().getSmime().getSignatures()).isEmpty();
        assertThat(imported.getProblems()).anySatisfy(problem -> assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.IMPORT_LIMIT_EXCEEDED));
    }

    @Test
    void anUnvisitedProtectedInnerBodyKeepsTheCompletedNativeOuterViewAndItsOwnInspection() throws Exception {
        final Path resources = Path.of("src/test/resources");
        final byte[] encrypted = ProtectedCalendarFixture.encryptedEml(resources);
        final byte[] msg = withOuterBody(ProtectedCalendarFixture.outlook(resources, encrypted), "Completed outer body");

        final Email imported = mail.converter().fromOutlookMsg(new ByteArrayInputStream(msg))
                .withSmimeDecryption(ProtectedCalendarFixture.keys(resources))
                .withImportLimitsConfig(ImportLimitsConfig.builder().withMaximumNestingDepth(2).build()).toEmail();

        assertThat(imported.getPlainText()).contains("Completed outer body");
        assertThat(imported.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(EncryptionStatus.NOT_ENCRYPTED);
        assertThat(imported.getProblems()).anySatisfy(problem ->
                assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.IMPORT_LIMIT_EXCEEDED));
        assertThat(imported.getInspection().getSmime().getDecryptions()).extracting("status").contains(DecryptionStatus.DECRYPTED);
        final Email copy = mail.emailBuilder().copying(imported).withSubject("Edited current subject").buildEmail();
        assertThat(copy.getPlainText()).contains("Completed outer body");
        assertThat(copy.getSourceData().getOrigin().getReference()).isEqualTo(imported.getSourceData().getOrigin().getReference());
        assertThat(copy.getInspection().getOriginalMessage()).isSameAs(imported.getInspection().getOriginalMessage());
        assertThat(copy.getInspection().getPlainText()).isSameAs(imported.getInspection().getPlainText());
    }

    @Test
    void aReadableProtectedBodyIsSelectedWhenOnlyALaterFileIsOmitted() throws Exception {
        final Path resources = Path.of("src/test/resources");
        final Email clear = mail.emailBuilder().startingBlank().from("payload@example.org").to("reader@example.org")
                .withPlainText("Useful inner body")
                .withAttachment("not-admitted.txt", "Inner file".getBytes(StandardCharsets.US_ASCII), "text/plain").buildEmail();
        final byte[] encrypted = ProtectedCalendarFixture.encryptedMessage(resources, mail.converter().fromEmail(clear).toMimeMessage());
        final byte[] msg = withOuterBody(ProtectedCalendarFixture.outlook(resources, encrypted), "Outer wrapper body");

        final Email imported = mail.converter().fromOutlookMsg(new ByteArrayInputStream(msg))
                .withSmimeDecryption(ProtectedCalendarFixture.keys(resources))
                .withImportLimitsConfig(ImportLimitsConfig.builder().withMaximumPartCount(6).build()).toEmail();

        assertThat(imported.getPlainText()).contains("Useful inner body").doesNotContain("Outer wrapper body");
        assertThat(imported.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
        assertThat(imported.getAttachments()).noneMatch(resource -> "not-admitted.txt".equals(resource.getName()));
        assertThat(imported.getProblems()).anySatisfy(problem -> assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.IMPORT_LIMIT_EXCEEDED));
    }

    @Test
    void anOmittedDeclaredNativeEnvelopeKeepsItsUninspectedFactsUnknown() throws Exception {
        final Path resources = Path.of("src/test/resources");
        final byte[] msg = ProtectedCalendarFixture.outlook(resources, ProtectedCalendarFixture.encryptedEml(resources));

        final Email imported = mail.converter().fromOutlookMsg(new ByteArrayInputStream(msg))
                .withSmimeDecryption(ProtectedCalendarFixture.keys(resources))
                .withImportLimitsConfig(ImportLimitsConfig.builder().withMaximumPartCount(1).build()).toEmail();

        assertThat(imported.getInspection().getSmime().isInspectionComplete()).isFalse();
        assertThat(imported.getInspection().getSmime().getSignatures()).isEmpty();
        assertThat(imported.getInspection().getSmime().getDecryptions()).isEmpty();
        assertThat(imported.getInspection().getOriginalMessage().readAllBytes()).containsExactly(msg);
        assertThat(imported.getProblems()).anySatisfy(problem -> assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.IMPORT_LIMIT_EXCEEDED));
    }

    private static MimeBodyPart opaqueSignedFile() throws Exception {
        final MimeMultipart content = new MimeMultipart("mixed");
        final MimeBodyPart text = new MimeBodyPart();
        text.setText("Independent child body", "UTF-8");
        content.addBodyPart(text);
        final MimeBodyPart file = new MimeBodyPart();
        file.setText("Independent child file");
        file.setDisposition("attachment");
        file.setFileName("inside.txt");
        content.addBodyPart(file);
        final MimeBodyPart entity = new MimeBodyPart();
        entity.setContent(content);
        final Pkcs12Config keys = loadPkcs12KeyStore();
        final KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(keys.getPkcs12StoreData()), keys.getStorePassword());
        final X509Certificate certificate = (X509Certificate) store.getCertificate(keys.getKeyAlias());
        final SMIMESignedGenerator signer = new SMIMESignedGenerator();
        signer.addCertificates(new JcaCertStore(List.of(certificate)));
        signer.addSignerInfoGenerator(new JcaSimpleSignerInfoGeneratorBuilder().setProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider())
                .build("SHA256withRSA", (PrivateKey) store.getKey(keys.getKeyAlias(), keys.getKeyPassword()), certificate));
        return signer.generateEncapsulated(entity);
    }

    private static byte[] withOuterBody(final byte[] msg, final String text) throws Exception {
        try (POIFSFileSystem compound = new POIFSFileSystem(new ByteArrayInputStream(msg))) {
            for (String otherBody : List.of("__substg1.0_10090102", "__substg1.0_10130102", "__substg1.0_1013001F", "__substg1.0_1013001E")) {
                if (compound.getRoot().hasEntry(otherBody)) compound.getRoot().getEntry(otherBody).delete();
            }
            compound.getRoot().createOrUpdateDocument("__substg1.0_1000001F",
                    new ByteArrayInputStream((text + "\0").getBytes(StandardCharsets.UTF_16LE)));
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            compound.writeFilesystem(bytes);
            return bytes.toByteArray();
        }
    }
}
