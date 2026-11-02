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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.OriginalSmimeDetails;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Properties;
import java.util.stream.Stream;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.email.OriginalSmimeDetails.DecryptionStatus.DECRYPTED;
import static org.simplejavamail.api.email.OriginalSmimeDetails.DecryptionStatus.KEY_MISSING;
import static org.simplejavamail.api.email.OriginalSmimeDetails.DecryptionStatus.NOT_ENCRYPTED;
import static org.simplejavamail.api.email.OriginalSmimeDetails.SmimeMode.ENCRYPTED;
import static org.simplejavamail.api.email.OriginalSmimeDetails.SmimeMode.PLAIN;
import static org.simplejavamail.api.email.OriginalSmimeDetails.VerificationStatus.NOT_SIGNED;
import static org.simplejavamail.api.email.OriginalSmimeDetails.VerificationStatus.UNKNOWN;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class OutlookSinglePartDecryptionFactsTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @ParameterizedTest(name = "{0}, fluent={1}, merge={2}")
    @MethodSource("singleParts")
    void completedNativeDecryptionDoesNotDependOnAnAvailableReadableView(final boolean binary,
            final boolean fluent, final boolean merge) throws Exception {
        final byte[] ciphertext = encryptedPayload(singlePart(binary));
        final byte[] msg = outlookMessage(ciphertext);
        final Email fallback = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).toEmail();
        final EmailPopulatingBuilder builder = fluent
                ? MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).withSmimeDecryption(loadPkcs12KeyStore()).toEmailBuilder()
                : EmailConverter.outlookMsgToEmailBuilderWithOutlookData(new ByteArrayInputStream(msg), loadPkcs12KeyStore()).getEmailBuilder();
        if (!merge) builder.notMergingSingleSMIMESignedAttachment();
        final Email email = builder.buildEmail();

        assertFacts(email, NOT_SIGNED);
        assertFallback(email, fallback, ciphertext);
        final Email copied = MAIL.emailBuilder().copying(email).buildEmail();
        assertFacts(copied, NOT_SIGNED);
        assertFallback(copied, fallback, ciphertext);
        final Email restored = serializedCopy(email);
        assertFacts(restored, NOT_SIGNED);
        assertFallback(restored, fallback, ciphertext);
    }

    private static Stream<Arguments> singleParts() {
        return Stream.of(false, true).flatMap(binary -> Stream.of(false, true).flatMap(fluent ->
                Stream.of(false, true).map(merge -> Arguments.of(binary, fluent, merge))));
    }

    @Test
    void outerDecryptionDoesNotClaimThatUnopenedNestedCiphertextIsUnsigned() throws Exception {
        final byte[] innerCiphertext = encryptedPayload(singlePart(false));
        final MimeBodyPart innerEnvelope = new MimeBodyPart();
        innerEnvelope.setDataHandler(new DataHandler(new ByteArrayDataSource(innerCiphertext,
                "application/pkcs7-mime; smime-type=enveloped-data")));
        innerEnvelope.setHeader("Content-Type", "application/pkcs7-mime; smime-type=enveloped-data");
        innerEnvelope.setHeader("Content-Transfer-Encoding", "base64");
        final byte[] ciphertext = encryptedPayload(innerEnvelope);
        final byte[] msg = outlookMessage(ciphertext);
        final Email fallback = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).toEmail();
        final Email email = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg))
                .withSmimeDecryption(loadPkcs12KeyStore()).toEmail();

        assertThat(email.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(UNKNOWN);
        assertFacts(email, UNKNOWN);
        assertFallback(email, fallback, ciphertext);
        assertFacts(MAIL.emailBuilder().copying(email).buildEmail(), UNKNOWN);
        assertFacts(serializedCopy(email), UNKNOWN);
    }

    @Test
    void noKeyRetainsItsExistingMissingKeyResultAndWrongPasswordsStillThrow() throws Exception {
        final byte[] ciphertext = encryptedPayload(singlePart(false));
        final byte[] msg = outlookMessage(ciphertext);
        final Email noKey = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).toEmail();
        assertThat(noKey.getOriginalSmimeDetails().getDecryptionStatus()).isEqualTo(KEY_MISSING);
        assertThat(noKey.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(UNKNOWN);
        assertThat(noKey.getAttachments().get(0).readAllBytes()).containsExactly(ciphertext);
        final Pkcs12Config keys = loadPkcs12KeyStore();
        final Pkcs12Config wrongPassword = Pkcs12Config.builder().pkcs12Store(keys.getPkcs12StoreData())
                .storePassword("wrong password").keyAlias(keys.getKeyAlias()).keyPassword(keys.getKeyPassword()).build();
        assertThatThrownBy(() -> MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg))
                .withSmimeDecryption(wrongPassword).toEmail()).isInstanceOf(SmimeException.class);
    }

    @Test
    void decryptingASingleIndependentFileDoesNotPromoteItsPlainParent() throws Exception {
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

        assertThat(email.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(PLAIN);
        assertThat(email.getOriginalSmimeDetails().getDecryptionStatus()).isEqualTo(NOT_ENCRYPTED);
        assertThat(email.getPlainText()).isEqualTo("The parent remains plain");
        assertThat(email.getDecryptedAttachments()).singleElement()
                .satisfies(resource -> assertThat(resource.readAllBytes()).containsExactly(ciphertext));
    }

    private static void assertFacts(final Email email, final OriginalSmimeDetails.VerificationStatus verification) {
        final OriginalSmimeDetails details = email.getOriginalSmimeDetails();
        assertThat(details.getSmimeMode()).isEqualTo(ENCRYPTED);
        assertThat(details.getDecryptionStatus()).isEqualTo(DECRYPTED);
        assertThat(details.getVerificationStatus()).isEqualTo(verification);
        assertThat(details.getSmimeSignatureValid()).isNull();
        assertThat(details.getOriginalProtectedMessage()).isNull();
    }

    private static void assertFallback(final Email email, final Email withoutKey, final byte[] ciphertext) {
        assertThat(email.getPlainText()).isEqualTo(withoutKey.getPlainText());
        assertThat(email.getHTMLText()).isEqualTo(withoutKey.getHTMLText());
        assertThat(email.getSmimeSignedEmail()).isNull();
        assertThat(email.getAttachments()).hasSize(1);
        assertThat(email.getDecryptedAttachments()).hasSize(1);
        final AttachmentResource original = email.getAttachments().get(0);
        final AttachmentResource retained = email.getDecryptedAttachments().get(0);
        assertThat(original.getName()).isEqualTo(withoutKey.getAttachments().get(0).getName());
        assertThat(retained.getName()).isEqualTo(original.getName());
        assertThat(original.readAllBytes()).containsExactly(ciphertext);
        assertThat(retained.readAllBytes()).containsExactly(ciphertext);
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
