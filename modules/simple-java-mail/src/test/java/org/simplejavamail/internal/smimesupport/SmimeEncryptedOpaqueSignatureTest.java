package org.simplejavamail.internal.smimesupport;

import jakarta.activation.DataHandler;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder;
import org.bouncycastle.mail.smime.SMIMESignedGenerator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.internal.outlooksupport.model.OutlookMessage;
import org.simplejavamail.api.internal.outlooksupport.model.OutlookSmime.OutlookSmimeApplicationSmime;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.internal.smimesupport.builder.SmimeParseResultBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.simplejavamail.api.email.OriginalSmimeDetails.DecryptionStatus.DECRYPTED;
import static org.simplejavamail.api.email.OriginalSmimeDetails.SmimeMode.SIGNED_ENCRYPTED;
import static org.simplejavamail.api.email.OriginalSmimeDetails.VerificationStatus.ERROR;
import static org.simplejavamail.api.email.OriginalSmimeDetails.VerificationStatus.INVALID;
import static org.simplejavamail.api.email.OriginalSmimeDetails.VerificationStatus.VALID;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class SmimeEncryptedOpaqueSignatureTest {

    private static final String ORIGINAL_BODY = "Original opaque body";
    private static final String TAMPERED_BODY = "Tampered opaque body";

    @ParameterizedTest
    @MethodSource("opaqueCases")
    void outerFactsRetainTheOpaqueSignatureAfterDecryption(final String mediaType,
            final OriginalSmimeDetails.VerificationStatus signatureStatus, final boolean mergeContent) throws Exception {
        final byte[] source = encrypted(opaqueSigned(mediaType, signatureStatus));
        final BorrowedInput input = new BorrowedInput(source);
        final EmailPopulatingBuilder builder = SimpleJavaMail.fromDefaults().converter().fromEml(input)
                .withSmimeDecryption(loadPkcs12KeyStore()).toEmailBuilder();
        if (!mergeContent) {
            builder.notMergingSingleSMIMESignedAttachment();
        }
        final Email email = builder.buildEmail();

        assertThat(input.closed).isFalse();
        assertFacts(email, signatureStatus, source);
        assertFacts(SimpleJavaMail.fromDefaults().emailBuilder().copying(email).buildEmail(), signatureStatus, source);
        assertFacts(roundTrip(email), signatureStatus, source);
        assertThat(email.getSmimeSignedEmail().getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(signatureStatus);
        final String content = signatureStatus == INVALID ? TAMPERED_BODY : ORIGINAL_BODY;
        assertThat(email.getSmimeSignedEmail().getPlainText()).contains(content);
        assertThat(email.getPlainText()).contains(content);
        final byte[] returnedBytes = email.getOriginalSmimeDetails().getOriginalProtectedMessage();
        returnedBytes[0] ^= 1;
        assertThat(email.getOriginalSmimeDetails().getOriginalProtectedMessage()).containsExactly(source);

        final Email staticImport = EmailConverter.emlToEmail(new ByteArrayInputStream(source), loadPkcs12KeyStore());
        assertFacts(staticImport, signatureStatus, source);
    }

    private static Stream<Arguments> opaqueCases() {
        return Stream.of("application/pkcs7-mime", "application/x-pkcs7-mime", "Application/PKCS7-Mime")
                .flatMap(type -> Stream.of(VALID, INVALID, ERROR)
                        .flatMap(status -> Stream.of(true, false).map(merge -> Arguments.of(type, status, merge))));
    }

    @ParameterizedTest
    @MethodSource("opaqueCases")
    void outlookEnvelopeRetainsItsDecryptedOpaqueSignatureFacts(final String mediaType,
            final OriginalSmimeDetails.VerificationStatus signatureStatus, final boolean mergeContent) throws Exception {
        final byte[] message = outlookMessage(encrypted(opaqueSigned(mediaType, signatureStatus)));
        final BorrowedInput input = new BorrowedInput(message);
        final EmailPopulatingBuilder builder = SimpleJavaMail.fromDefaults().converter().fromOutlookMsg(input)
                .withSmimeDecryption(loadPkcs12KeyStore()).toEmailBuilder();
        if (!mergeContent) {
            builder.notMergingSingleSMIMESignedAttachment();
        }
        final Email email = builder.buildEmail();

        assertThat(input.closed).isFalse();
        assertFacts(email, signatureStatus, null);
        assertFacts(SimpleJavaMail.fromDefaults().emailBuilder().copying(email).buildEmail(), signatureStatus, null);
        assertFacts(roundTrip(email), signatureStatus, null);
        final String content = signatureStatus == INVALID ? TAMPERED_BODY : ORIGINAL_BODY;
        assertThat(email.getSmimeSignedEmail().getPlainText()).contains(content);
        if (mergeContent) {
            assertThat(email.getPlainText()).contains(content);
        }
        assertFacts(EmailConverter.outlookMsgToEmail(new ByteArrayInputStream(message), loadPkcs12KeyStore()), signatureStatus, null);
    }

    @ParameterizedTest
    @MethodSource("independentAttachmentCases")
    void independentSignedEncryptedAttachmentsDoNotChangeTheirPlainParent(final String mediaType,
            final OriginalSmimeDetails.VerificationStatus signatureStatus) throws Exception {
        final MimeBodyPart protectedFile = new MimeBodyPart(new ByteArrayInputStream(encrypted(opaqueSigned(mediaType, signatureStatus))));
        protectedFile.setFileName("independent.p7m");
        final MimeBodyPart body = new MimeBodyPart();
        body.setText("Plain parent body", "UTF-8");
        final MimeBodyPart note = new MimeBodyPart();
        note.setText("Unrelated note", "UTF-8");
        note.setFileName("note.txt");
        final MimeMultipart content = new MimeMultipart();
        content.addBodyPart(body);
        content.addBodyPart(protectedFile);
        content.addBodyPart(note);
        final MimeMessage parent = new MimeMessage(Session.getInstance(new Properties()));
        parent.setFrom("sender@example.org");
        parent.setContent(content);
        parent.saveChanges();

        final Email email = EmailConverter.mimeMessageToEmail(parent, loadPkcs12KeyStore());

        assertThat(email.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(OriginalSmimeDetails.SmimeMode.PLAIN);
        assertThat(email.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(OriginalSmimeDetails.VerificationStatus.NOT_SIGNED);
        assertThat(email.getOriginalSmimeDetails().getDecryptionStatus()).isEqualTo(OriginalSmimeDetails.DecryptionStatus.NOT_ENCRYPTED);
        assertThat(email.getOriginalSmimeDetails().getOriginalProtectedMessage()).isNull();
        assertThat(email.getPlainText()).contains("Plain parent body");
        assertThat(email.getDecryptedAttachments()).extracting("name").contains("independent.p7m", "note.txt");
    }

    private static Stream<Arguments> independentAttachmentCases() {
        return Stream.of("application/pkcs7-mime", "application/x-pkcs7-mime")
                .flatMap(type -> Stream.of(VALID, INVALID, ERROR).map(status -> Arguments.of(type, status)));
    }

    @ParameterizedTest
    @MethodSource("independentAttachmentCases")
    void additionalProtectedFilesCannotSupplyAnOutlookEnvelopeSignature(final String mediaType,
            final OriginalSmimeDetails.VerificationStatus signatureStatus) throws Exception {
        final MimeBodyPart body = new MimeBodyPart();
        body.setText("Unsigned owning envelope", "UTF-8");
        final MimeMultipart content = new MimeMultipart();
        content.addBodyPart(body);
        final MimeBodyPart owningEntity = new MimeBodyPart();
        owningEntity.setContent(content);
        final AttachmentResource envelope = encryptedAttachment(owningEntity, "smime.p7m");
        final AttachmentResource independent = encryptedAttachment(opaqueSigned(mediaType, signatureStatus), "independent.p7m");
        final OutlookSmimeApplicationSmime declaration = mock(OutlookSmimeApplicationSmime.class);
        when(declaration.getSmimeMime()).thenReturn("application/pkcs7-mime");
        when(declaration.getSmimeType()).thenReturn("enveloped-data");
        final OutlookMessage outlook = mock(OutlookMessage.class);
        when(outlook.getSmimeMime()).thenReturn(declaration);

        final SmimeParseResultBuilder parsed = new SMIMESupport().decryptAttachments(List.of(envelope, independent), outlook, loadPkcs12KeyStore());

        assertThat(parsed.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(OriginalSmimeDetails.SmimeMode.ENCRYPTED);
        assertThat(parsed.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(OriginalSmimeDetails.VerificationStatus.NOT_SIGNED);
        assertThat(parsed.getOriginalSmimeDetails().getDecryptionStatus()).isEqualTo(DECRYPTED);
        assertThat(parsed.getSmimeSignedOrEncryptedEmail()).isNull();
    }

    private static void assertFacts(final Email email, final OriginalSmimeDetails.VerificationStatus expected,
            final byte[] source) {
        final OriginalSmimeDetails details = email.getOriginalSmimeDetails();
        assertThat(details.getSmimeMode()).isEqualTo(SIGNED_ENCRYPTED);
        assertThat(details.getDecryptionStatus()).isEqualTo(DECRYPTED);
        assertThat(details.getVerificationStatus()).isEqualTo(expected);
        assertThat(details.getSmimeSignatureValid()).isEqualTo(expected == VALID);
        if (source == null) {
            assertThat(details.getOriginalProtectedMessage()).isNull();
        } else {
            assertThat(details.getOriginalProtectedMessage()).containsExactly(source);
        }
        if (expected == VALID) {
            assertThat(details.getFailureReason()).isNull();
        } else {
            assertThat(details.getFailureReason()).isNotEmpty();
        }
    }

    private static MimeBodyPart opaqueSigned(final String mediaType,
            final OriginalSmimeDetails.VerificationStatus expected) throws Exception {
        final Pkcs12Config keys = loadPkcs12KeyStore();
        final KeyStore store = store(keys);
        final X509Certificate certificate = (X509Certificate) store.getCertificate(keys.getKeyAlias());
        final SMIMESignedGenerator generator = new SMIMESignedGenerator();
        if (expected != ERROR) {
            generator.addCertificates(new JcaCertStore(List.of(certificate)));
        }
        generator.addSignerInfoGenerator(new JcaSimpleSignerInfoGeneratorBuilder()
                .setProvider(SmimeCryptoSupport.BOUNCY_CASTLE)
                .build("SHA256withRSA", (PrivateKey) store.getKey(keys.getKeyAlias(), keys.getKeyPassword()), certificate));
        final MimeBodyPart text = new MimeBodyPart();
        text.setText(ORIGINAL_BODY, "UTF-8");
        final MimeMultipart content = new MimeMultipart();
        content.addBodyPart(text);
        final MimeBodyPart entity = new MimeBodyPart();
        entity.setContent(content);
        final MimeBodyPart signed = generator.generateEncapsulated(entity);
        final byte[] encoded;
        try (java.io.InputStream input = signed.getInputStream()) {
            final String original = new String(input.readAllBytes(), ISO_8859_1);
            if (expected == INVALID) {
                assertThat(original).contains(ORIGINAL_BODY);
            }
            encoded = (expected == INVALID ? original.replace(ORIGINAL_BODY, TAMPERED_BODY) : original).getBytes(ISO_8859_1);
        }
        signed.setDataHandler(new DataHandler(new ByteArrayDataSource(encoded, mediaType + "; smime-type=SIGNED-DATA")));
        signed.setHeader("Content-Type", mediaType + "; smime-type=SIGNED-DATA");
        signed.setHeader("Content-Transfer-Encoding", "base64");
        return signed;
    }

    private static byte[] encrypted(final MimeBodyPart content) throws Exception {
        final Pkcs12Config keys = loadPkcs12KeyStore();
        final X509Certificate certificate = (X509Certificate) store(keys).getCertificate(keys.getKeyAlias());
        final MimeBodyPart encrypted = SmimeCryptoSupport.encrypt(content, certificate,
                SmimeCryptoSupport.DEFAULT_KEY_ENCAPSULATION_ALGORITHM, SmimeCryptoSupport.DEFAULT_CIPHER);
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom("sender@example.org");
        message.setDataHandler(encrypted.getDataHandler());
        message.saveChanges();
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        message.writeTo(output);
        return output.toByteArray();
    }

    private static KeyStore store(final Pkcs12Config keys) throws Exception {
        final KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(keys.getPkcs12StoreData()), keys.getStorePassword());
        return store;
    }

    private static byte[] outlookMessage(final byte[] encryptedEml) throws Exception {
        final MimeMessage encrypted = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(encryptedEml));
        final byte[] encryptedPayload;
        try (java.io.InputStream input = encrypted.getInputStream()) {
            encryptedPayload = input.readAllBytes();
        }
        final Path fixture = Path.of(determineResourceFolder("simple-java-mail"),
                "test/resources/pkcs12/test messages/S_MIME test message encrypted.msg");
        try (java.io.InputStream input = Files.newInputStream(fixture); POIFSFileSystem file = new POIFSFileSystem(input)) {
            assertThat(replaceEncryptedAttachment(file.getRoot(), encryptedPayload)).isEqualTo(1);
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            file.writeFilesystem(output);
            return output.toByteArray();
        }
    }

    private static AttachmentResource encryptedAttachment(final MimeBodyPart content, final String name) throws Exception {
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(encrypted(content)));
        try (java.io.InputStream input = message.getInputStream()) {
            return AttachmentResource.builder().withName(name)
                    .withDataSource(new ByteArrayDataSource(input.readAllBytes(), "application/pkcs7-mime")).build();
        }
    }

    private static int replaceEncryptedAttachment(final DirectoryNode node, final byte[] payload) throws Exception {
        int replaced = 0;
        if (node.getName().startsWith("__attach_version1.0_") && node.hasEntry("__substg1.0_37010102")) {
            node.createOrUpdateDocument("__substg1.0_37010102", new ByteArrayInputStream(payload));
            final byte[] properties;
            try (java.io.InputStream input = node.createDocumentInputStream("__properties_version1.0")) {
                properties = input.readAllBytes();
            }
            final ByteBuffer records = ByteBuffer.wrap(properties).order(ByteOrder.LITTLE_ENDIAN);
            for (int offset = 8; offset + 16 <= properties.length; offset += 16) {
                if (records.getInt(offset) == 0x37010102) {
                    records.putInt(offset + 8, payload.length);
                }
            }
            node.createOrUpdateDocument("__properties_version1.0", new ByteArrayInputStream(properties));
            replaced++;
        }
        for (Entry entry : node) {
            if (entry.isDirectoryEntry()) {
                replaced += replaceEncryptedAttachment((DirectoryNode) entry, payload);
            }
        }
        return replaced;
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

    private static final class BorrowedInput extends ByteArrayInputStream {
        private boolean closed;

        private BorrowedInput(final byte[] bytes) {
            super(bytes);
        }

        @Override public void close() {
            closed = true;
        }
    }
}
