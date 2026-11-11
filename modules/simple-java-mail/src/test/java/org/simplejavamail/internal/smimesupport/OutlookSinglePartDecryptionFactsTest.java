package org.simplejavamail.internal.smimesupport;

import jakarta.activation.DataHandler;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.EntryUtils;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.converter.ConfiguredEmailConverter.OutlookConversion;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailSourceContent;
import org.simplejavamail.api.email.EmailSourceData;
import org.simplejavamail.api.email.EmailSourcePart;
import org.simplejavamail.api.email.DecryptionStatus;
import org.simplejavamail.api.email.EncryptionStatus;
import org.simplejavamail.api.email.SignatureStatus;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.converter.EmailConverter;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Properties;
import java.util.stream.Stream;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;
import static org.simplejavamail.internal.smimesupport.SmimeInspectionAssertions.*;

class OutlookSinglePartDecryptionFactsTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @ParameterizedTest(name = "binary={0}, fluent={1}")
    @MethodSource("singleParts")
    void completedNativeDecryptionExposesTheUsableSinglePart(final boolean binary, final boolean fluent) throws Exception {
        final byte[] ciphertext = encryptedPayload(singlePart(binary));
        final byte[] msg = outlookMessage(ciphertext);
        final Email fallback = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).toEmail();
        final Email email = fluent
                ? MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).withSmimeDecryption(loadPkcs12KeyStore()).toEmail()
                : EmailConverter.outlookMsgToEmailBuilderWithOutlookData(new ByteArrayInputStream(msg), loadPkcs12KeyStore()).buildEmail();
        for (Email value : java.util.List.of(email, MAIL.emailBuilder().copying(email).buildEmail(), serializedCopy(email))) {
            assertOriginal(value, msg);
            assertDecryptionObservation(value, DecryptionStatus.DECRYPTED);
            if (binary) {
                assertThat(value.getAttachments()).hasSize(1);
                assertThat(value.getAttachments().get(0).readAllBytes()).containsExactly(0, 10, 1, 13, 2);
                assertThat(value.getAttachments().get(0).getEmail()).isNull();
                assertThat(value.getInspection().getAttachment(0).getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
                assertThat(value.getInspection().getAttachment(0).getSignatureStatus()).isEqualTo(SignatureStatus.UNSIGNED);
                assertThat(value.getPlainText()).isEqualTo(fallback.getPlainText());
            } else {
                assertThat(value.getPlainText()).contains("Single clear MIME text that has no generated legacy attachment");
                assertPlainProtection(value, SignatureStatus.UNSIGNED, EncryptionStatus.DECRYPTED);
                assertThat(value.getAttachments()).isEmpty();
            }
            assertThat(value.getHTMLText()).isEqualTo(fallback.getHTMLText());
        }
    }

    private static Stream<Arguments> singleParts() {
        return Stream.of(false, true).flatMap(binary -> Stream.of(false, true).map(fluent -> Arguments.of(binary, fluent)));
    }

    @Test
    void repeatedNativeEnvelopeLayersOpenToTheSameReadableSinglePart() throws Exception {
        final byte[] innerCiphertext = encryptedPayload(singlePart(false));
        final MimeBodyPart innerEnvelope = new MimeBodyPart();
        innerEnvelope.setDataHandler(new DataHandler(new ByteArrayDataSource(innerCiphertext,
                "application/pkcs7-mime; smime-type=enveloped-data")));
        innerEnvelope.setHeader("Content-Type", "application/pkcs7-mime; smime-type=enveloped-data");
        innerEnvelope.setHeader("Content-Transfer-Encoding", "base64");
        final byte[] msg = outlookMessage(encryptedPayload(innerEnvelope));
        final Email email = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg))
                .withSmimeDecryption(loadPkcs12KeyStore()).toEmail();
        for (Email value : java.util.List.of(email, MAIL.emailBuilder().copying(email).buildEmail(), serializedCopy(email))) {
            assertPlainProtection(value, SignatureStatus.UNSIGNED, EncryptionStatus.DECRYPTED);
            assertThat(value.getPlainText()).contains("Single clear MIME text");
            assertThat(value.getInspection().getSmime().getDecryptions()).hasSize(2)
                    .allSatisfy(attempt -> assertThat(attempt.getStatus()).isEqualTo(DecryptionStatus.DECRYPTED));
            assertOriginal(value, msg);
        }
    }

    @Test
    void noKeyRetainsOriginalNativeInputAndWrongPasswordsStillThrow() throws Exception {
        final byte[] ciphertext = encryptedPayload(singlePart(false));
        final byte[] msg = outlookMessage(ciphertext);
        final Email noKey = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).toEmail();
        assertUnopenedFamily(noKey, DecryptionStatus.UNAVAILABLE);
        assertOriginal(noKey, msg);
        final Pkcs12Config keys = loadPkcs12KeyStore();
        final Pkcs12Config wrongPassword = Pkcs12Config.builder().pkcs12Store(keys.getPkcs12StoreData())
                .storePassword("wrong password").keyAlias(keys.getKeyAlias()).keyPassword(keys.getKeyPassword()).build();
        assertThatThrownBy(() -> MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg))
                .withSmimeDecryption(wrongPassword).toEmail()).isInstanceOf(SmimeException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void embeddedNativeBodyKeepsItsCapturedEnvelopeWithoutInventingAnOriginalMsg(final boolean useKey) throws Exception {
        final byte[] ciphertext = encryptedPayload(singlePart(false));
        final byte[] parentInput = withEmbeddedMessage(outlookMessage(ciphertext));
        final OutlookConversion conversion = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(parentInput));
        final Email parent = (useKey ? conversion.withSmimeDecryption(loadPkcs12KeyStore()) : conversion).toEmail();
        assertThat(parent.getPlainText()).isEqualTo("Native parent body");
        assertThat(parent.getInspection().getSmime().getDecryptions()).isEmpty();
        assertOriginal(parent, parentInput);
        assertThat(parent.getAttachments()).hasSize(1);
        final Email child = parent.getAttachments().get(0).getEmail();
        assertThat(child).isNotNull();
        for (final Email value : java.util.List.of(child, MAIL.emailBuilder().copying(child).buildEmail(), serializedCopy(child))) {
            assertThat(value.getSourceData().getFormat()).isEqualTo(EmailSourceData.Format.OUTLOOK_MSG);
            assertThat(value.getInspection().getOriginalMessage()).isNull();
            assertThat(value.getAttachments()).isEmpty();
            assertDecryptionObservation(value, useKey ? DecryptionStatus.DECRYPTED : DecryptionStatus.UNAVAILABLE);
            assertThat(value.getPlainText()).contains(useKey ? "Single clear MIME text" : "Native child fallback");
            final EmailSourcePart component = value.getSourceData().getParts().stream()
                    .filter(part -> part.getProperties().stream().anyMatch(property -> "nativeAttachmentIndex".equals(property.getName())
                            && "0".equals(property.getValue())))
                    .findFirst().orElseThrow();
            final EmailSourceContent retained = component.getContent();
            assertThat(retained).isNotNull();
            assertThat(retained.getRepresentation()).isEqualTo(EmailSourceContent.Representation.READER_BYTES);
            assertThat(retained.isFullyCaptured()).isTrue();
            assertThat(retained.readAllBytes()).containsExactly(ciphertext);
        }
    }

    private static byte[] withEmbeddedMessage(final byte[] childInput) throws Exception {
        try (POIFSFileSystem parent = new POIFSFileSystem();
                POIFSFileSystem child = new POIFSFileSystem(new ByteArrayInputStream(childInput));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            final DirectoryNode root = parent.getRoot();
            root.createDocument("__substg1.0_001A001F", nativeString("IPM.Note"));
            root.createDocument("__substg1.0_1000001F", nativeString("Native parent body"));
            final DirectoryNode attachment = (DirectoryNode) root.createDirectory("__attach_version1.0_#00000000");
            final DirectoryNode embedded = (DirectoryNode) attachment.createDirectory("__substg1.0_3701000D");
            EntryUtils.copyNodes(child.getRoot(), embedded);
            embedded.createOrUpdateDocument("__substg1.0_1000001F", nativeString("Native child fallback"));
            parent.writeFilesystem(output);
            return output.toByteArray();
        }
    }

    private static ByteArrayInputStream nativeString(final String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_16LE));
    }

    @Test
    void decryptingOneIndependentFileKeepsThePlainParentAndExposesItsClearPayload() throws Exception {
        final byte[] ciphertext = encryptedPayload(singlePart(false));
        final MimeBodyPart body = new MimeBodyPart();
        body.setText("The parent remains plain", "UTF-8");
        final MimeBodyPart attachment = new MimeBodyPart();
        attachment.setDataHandler(new DataHandler(new ByteArrayDataSource(ciphertext,
                "application/pkcs7-mime; smime-type=enveloped-data")));
        attachment.setFileName("independent.p7m");
        final MimeMultipart content = new MimeMultipart();
        content.addBodyPart(body);
        content.addBodyPart(attachment);
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setContent(content);
        message.saveChanges();
        final Email email = MAIL.converter().fromMimeMessage(message).withSmimeDecryption(loadPkcs12KeyStore()).toEmail();
        assertThat(email.getPlainText()).isEqualTo("The parent remains plain");
        assertPlainProtection(email, SignatureStatus.UNSIGNED, EncryptionStatus.NOT_ENCRYPTED);
        assertThat(email.getAttachments()).singleElement().satisfies(resource -> {
            assertThat(resource.readAllData()).contains("Single clear MIME text");
            assertThat(resource.getEmail()).isNull();
        });
        assertThat(email.getInspection().getOriginalAttachment(0).readAllBytes()).containsExactly(ciphertext);
        assertThat(email.getInspection().getAttachment(0).getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
    }

    private static MimeBodyPart singlePart(final boolean binary) throws Exception {
        final MimeBodyPart content = new MimeBodyPart();
        if (binary) {
            content.setDataHandler(new DataHandler(new ByteArrayDataSource(new byte[] {0, 10, 1, 13, 2}, "application/octet-stream")));
            content.setHeader("Content-Type", "application/octet-stream");
            content.setHeader("Content-Transfer-Encoding", "base64");
        } else {
            content.setText("Single clear MIME text that has no generated legacy attachment", "UTF-8");
        }
        return content;
    }

    private static byte[] encryptedPayload(final MimeBodyPart content) throws Exception {
        final Pkcs12Config keys = loadPkcs12KeyStore();
        final KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(keys.getPkcs12StoreData()), keys.getStorePassword());
        final X509Certificate certificate = (X509Certificate) store.getCertificate(keys.getKeyAlias());
        final MimeBodyPart encrypted = SmimeCryptoSupport.encrypt(content, certificate,
                SmimeCryptoSupport.DEFAULT_KEY_ENCAPSULATION_ALGORITHM, SmimeCryptoSupport.DEFAULT_CIPHER);
        try (InputStream input = encrypted.getInputStream()) {
            return input.readAllBytes();
        }
    }

    private static byte[] outlookMessage(final byte[] ciphertext) throws Exception {
        final Path fixture = Path.of(determineResourceFolder("simple-java-mail"),
                "test/resources/pkcs12/test messages/S_MIME test message encrypted.msg");
        try (InputStream input = Files.newInputStream(fixture); POIFSFileSystem file = new POIFSFileSystem(input)) {
            assertThat(replaceEncryptedAttachment(file.getRoot(), ciphertext)).isEqualTo(1);
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            file.writeFilesystem(output);
            return output.toByteArray();
        }
    }

    private static int replaceEncryptedAttachment(final DirectoryNode node, final byte[] payload) throws Exception {
        int replaced = 0;
        if (node.getName().startsWith("__attach_version1.0_") && node.hasEntry("__substg1.0_37010102")) {
            node.createOrUpdateDocument("__substg1.0_37010102", new ByteArrayInputStream(payload));
            final byte[] properties;
            try (InputStream input = node.createDocumentInputStream("__properties_version1.0")) {
                properties = input.readAllBytes();
            }
            final ByteBuffer records = ByteBuffer.wrap(properties).order(ByteOrder.LITTLE_ENDIAN);
            for (int offset = 8; offset + 16 <= properties.length; offset += 16) {
                if (records.getInt(offset) == 0x37010102) records.putInt(offset + 8, payload.length);
            }
            node.createOrUpdateDocument("__properties_version1.0", new ByteArrayInputStream(properties));
            replaced++;
        }
        for (Entry entry : node) if (entry.isDirectoryEntry()) replaced += replaceEncryptedAttachment((DirectoryNode) entry, payload);
        return replaced;
    }

    private static Email serializedCopy(final Email email) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(email); }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (Email) input.readObject();
        }
    }
}
