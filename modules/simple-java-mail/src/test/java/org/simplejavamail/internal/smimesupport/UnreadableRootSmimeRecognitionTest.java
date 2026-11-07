package org.simplejavamail.internal.smimesupport;

import jakarta.activation.DataHandler;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.internal.smimesupport.model.SmimePreprocessingResult;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.converter.EmailConverter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Properties;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class UnreadableRootSmimeRecognitionTest {
    private static final String CONTENT_TYPE = "text/plain; charset=UTF-8";
    private static final String BODY = "Readable body despite unavailable root metadata";
    private static final String SUBJECT = "Readable neighboring subject";
    private static final String FAILURE_MESSAGE = "Provider could not read the MIME content type";
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

    @ParameterizedTest
    @MethodSource("importPaths")
    void unreadableRootDeclarationRetainsUnknownProtectionAndReadableNeighbors(final boolean fluent,
            final boolean fetchAttachmentData) throws Exception {
        final MimeMessage source = unreadableType(new MessagingException(FAILURE_MESSAGE));
        final Email email = fluent
                ? MAIL.converter().fromMimeMessage(source).withFetchAttachmentData(fetchAttachmentData).toEmail()
                : EmailConverter.mimeMessageToEmail(source, null, fetchAttachmentData);

        assertUnknown(email.getOriginalSmimeDetails());
        assertThat(email.getSubject()).isEqualTo(SUBJECT);
        assertThat(email.getPlainText()).isEqualTo(BODY);
        assertThat(email.getFromRecipient().getAddress()).isEqualTo("sender@example.org");
        assertThat(email.getProblems()).anySatisfy(problem -> {
            assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.CONTENT_READ_FAILED);
            assertThat(problem.getLocation()).contains("Content-Type");
            assertThat(problem.getSourceValue()).isEqualTo(CONTENT_TYPE);
            assertThat(problem.getMessage()).contains(FAILURE_MESSAGE);
            assertThat(problem.getSources()).isNotEmpty();
            assertThat(problem.getOrigin()).isEqualTo(email.getSourceData().getOrigin());
        }).noneMatch(problem -> problem.getCode() == EmailProblem.Code.PROTECTION_FAILED
                || problem.getCode() == EmailProblem.Code.SIGNATURE_INVALID);
        assertThat(email.getProblems()).filteredOn(problem -> problem.getCode() == EmailProblem.Code.CONTENT_READ_FAILED
                && problem.getLocation().contains("Content-Type")).hasSize(1);
        assertThat(email.getSourceData().getHeaders()).anySatisfy(header -> {
            assertThat(header.getName()).isEqualToIgnoringCase("Content-Type");
            assertThat(header.getRawValue()).isEqualTo(CONTENT_TYPE);
        });
        assertUnknown(MAIL.emailBuilder().copying(email).buildEmail().getOriginalSmimeDetails());
    }

    private static Stream<Arguments> importPaths() {
        return Stream.of(true, false).flatMap(fluent -> Stream.of(true, false)
                .map(eager -> Arguments.of(fluent, eager)));
    }

    @Test
    void unreadableRootStillProcessesAnIndependentSignedAttachment() throws Exception {
        final Path signedFile = Path.of(determineResourceFolder("simple-java-mail"),
                "test/resources/pkcs12/test messages/S_MIME test message signed.eml");
        final MimeBodyPart signed = new MimeBodyPart(new ByteArrayInputStream(Files.readAllBytes(signedFile)));
        signed.setDisposition("attachment");
        signed.setFileName("independent-signed.mime");
        final MimeMessage source = unreadableWithAttachment(signed);

        final Email email = MAIL.converter().fromMimeMessage(source).toEmail();

        assertUnknown(email.getOriginalSmimeDetails());
        assertThat(email.getPlainText()).isEqualTo(BODY);
        assertThat(email.getAttachments()).extracting("name").contains("independent-signed.mime");
        assertThat(email.getDecryptedAttachments()).anySatisfy(attachment -> {
            assertThat(attachment.getDataSource().getContentType()).isEqualTo("message/rfc822");
            assertThat(attachment.readAllData(UTF_8)).contains("For testing purposes");
        });
        assertThat(email.getSmimeSignedEmail()).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void suppliedKeysStillOpenAnEncryptedChildWithoutPromotingTheRoot(final boolean fetchAttachmentData) throws Exception {
        final MimeMessage source = unreadableWithAttachment(encryptedAttachment(false));

        final Email email = MAIL.converter().fromMimeMessage(source).withSmimeDecryption(loadPkcs12KeyStore())
                .withFetchAttachmentData(fetchAttachmentData).toEmail();

        assertUnknown(email.getOriginalSmimeDetails());
        assertThat(email.getPlainText()).isEqualTo(BODY);
        assertThat(email.getAttachments()).extracting("name").contains("independent-encrypted.p7m");
        assertThat(email.getDecryptedAttachments()).singleElement().satisfies(attachment -> {
            assertThat(attachment.getDataSource().getContentType()).isEqualTo("message/rfc822");
            assertThat(attachment.readAllData(UTF_8)).contains("Independent decrypted content");
        });
        assertThat(email.getSmimeSignedEmail()).isNull();
    }

    @Test
    void anUnrelatedDecryptionKeyStillFailsForAnEncryptedChild() throws Exception {
        final MimeMessage source = unreadableWithAttachment(encryptedAttachment(true));

        assertThatThrownBy(() -> MAIL.converter().fromMimeMessage(source).withSmimeDecryption(loadPkcs12KeyStore()).toEmail())
                .isInstanceOf(SmimeException.class)
                .hasRootCauseMessage("The supplied S/MIME key is not a recipient of this message");
    }

    @Test
    void failedDeclarationReadDoesNotClaimProtectionRecognition() throws Exception {
        final MimeMessage source = unreadableType(new MessagingException(FAILURE_MESSAGE));

        final SmimePreprocessingResult result = new SMIMESupport().processIncoming(source.getSession(), source, null);

        assertThat(result.isRecognized()).isFalse();
        assertThat(result.getEffectiveMimeMessage()).isSameAs(source);
        assertUnknown(result.getDetails());
        assertThat(result.getProtectedAttachments()).isEmpty();
        assertThat(result.getDecryptedArtifacts()).isEmpty();
    }

    @Test
    void typedInterruptionDuringRecognitionRemainsCancellationWithAClearThreadFlag() throws Exception {
        final InterruptedIOException interruption = new InterruptedIOException("Metadata acquisition interrupted");
        final MessagingException failure = new MessagingException(FAILURE_MESSAGE, interruption);
        final MimeMessage source = unreadableType(failure);

        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertThatThrownBy(() -> new SMIMESupport().processIncoming(source.getSession(), source, null))
                .isInstanceOf(CancellationException.class).hasCause(failure);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void applicationFailureDuringRecognitionIsNotClassifiedAsMalformedMail() throws Exception {
        final IllegalStateException failure = new IllegalStateException("Application metadata reader failed");
        final MimeMessage source = new MimeMessage(Session.getInstance(new Properties())) {
            @Override public String getContentType() { throw failure; }
        };

        assertThatThrownBy(() -> new SMIMESupport().processIncoming(source.getSession(), source, null))
                .isSameAs(failure);
    }

    private static void assertUnknown(final OriginalSmimeDetails details) {
        assertThat(details.getSmimeMode()).isEqualTo(OriginalSmimeDetails.SmimeMode.UNKNOWN);
        assertThat(details.getVerificationStatus()).isEqualTo(OriginalSmimeDetails.VerificationStatus.UNKNOWN);
        assertThat(details.getDecryptionStatus()).isEqualTo(OriginalSmimeDetails.DecryptionStatus.UNKNOWN);
        assertThat(details.getSmimeSignatureValid()).isNull();
        assertThat(details.getFailureReason()).isNull();
        assertThat(details.getOriginalProtectedMessage()).isNull();
    }

    private static MimeMessage unreadableType(final MessagingException failure) throws Exception {
        final String eml = "From: sender@example.org\r\nTo: recipient@example.org\r\nSubject: " + SUBJECT
                + "\r\nContent-Type: " + CONTENT_TYPE + "\r\n\r\n" + BODY;
        return unreadableType(eml.getBytes(UTF_8), new DataHandler(BODY, CONTENT_TYPE), failure);
    }

    private static MimeMessage unreadableType(final byte[] eml, final DataHandler availableContent, final MessagingException failure) throws Exception {
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(eml)) {
            // A provider can still supply already available content while a separate metadata read fails.
            @Override public String getContentType() throws MessagingException { throw failure; }
            @Override public DataHandler getDataHandler() { return availableContent; }
        };
    }

    private static MimeMessage unreadableWithAttachment(final MimeBodyPart attachment) throws Exception {
        final MimeBodyPart body = new MimeBodyPart();
        body.setText(BODY, "UTF-8");
        final MimeMultipart multipart = new MimeMultipart();
        multipart.addBodyPart(body);
        multipart.addBodyPart(attachment);
        final MimeMessage healthy = new MimeMessage(Session.getInstance(new Properties()));
        healthy.setFrom("sender@example.org");
        healthy.setContent(multipart);
        healthy.saveChanges();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        healthy.writeTo(bytes);
        return unreadableType(bytes.toByteArray(), healthy.getDataHandler(), new MessagingException(FAILURE_MESSAGE));
    }

    private static MimeBodyPart encryptedAttachment(final boolean unrelatedRecipient) throws Exception {
        final Pkcs12Config keys = loadPkcs12KeyStore();
        final KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(keys.getPkcs12StoreData()), keys.getStorePassword());
        final X509Certificate recipient = unrelatedRecipient
                ? SmimeEncryptionConfig.builder().x509Certificate(Path.of(determineResourceFolder("simple-java-mail"),
                        "test/resources/pkcs12/ca.crt")).build().getX509Certificate()
                : (X509Certificate) store.getCertificate(keys.getKeyAlias());
        final MimeBodyPart text = new MimeBodyPart();
        text.setText("Independent decrypted content", "UTF-8");
        final MimeMultipart parts = new MimeMultipart();
        parts.addBodyPart(text);
        final MimeBodyPart content = new MimeBodyPart();
        content.setContent(parts);
        final MimeBodyPart encrypted = SmimeCryptoSupport.encrypt(content, recipient,
                SmimeCryptoSupport.DEFAULT_KEY_ENCAPSULATION_ALGORITHM, SmimeCryptoSupport.DEFAULT_CIPHER);
        encrypted.setDisposition("attachment");
        encrypted.setFileName("independent-encrypted.p7m");
        return encrypted;
    }
}
