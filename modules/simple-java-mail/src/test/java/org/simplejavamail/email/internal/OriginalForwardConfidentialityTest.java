package org.simplejavamail.email.internal;

import jakarta.mail.internet.MimeMessage;
import jakarta.mail.Multipart;
import jakarta.mail.BodyPart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.converter.EmailConverter;
import testutil.ConfigLoaderTestHelper;
import testutil.smtp.RecordingSmtpServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class OriginalForwardConfidentialityTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    private static final byte[] PLAIN_CHILD = ("From: child-author@example.org\r\nTo: child-reader@example.org\r\n"
            + "Subject: Private child topic\r\nMessage-ID: <private-child@example.org>\r\n"
            + "Content-Type: text/plain; charset=UTF-8\r\n\r\nSensitive child body\r\n").getBytes(UTF_8);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aChildExtractedThroughEncryptionNeedsProtectionEvenWhenItsOwnBodyWasAlreadyEncrypted(final boolean encryptedChildBody) throws Exception {
        final byte[] childBytes = encryptedChildBody ? MAIL.converter().fromEmail(MAIL.emailBuilder().startingBlank()
                .from("child-author@example.org").to("child-reader@example.org").withSubject("Clear child envelope topic")
                .withPlainText("Sensitive child body").encryptWithSmime(encryption()).buildEmail()).toEmlBytes() : PLAIN_CHILD;
        final AttachmentResource attached = AttachmentResource.builder().withName("Acme acquisition.eml")
                .withDescription("Acquisition plan").withContentId("private-original@example.org").withData(childBytes, "message/rfc822").build();
        final Parent fixture = parent(attached);
        final Email child = fixture.email.getAttachments().get(0).getEmail();
        assertThat(child).isNotNull();
        assertThat(child.getPlainText()).contains("Sensitive child body");
        assertThat(child.getInspection().getOriginalMessage().readAllBytes()).containsExactly(childBytes);
        assertThat(child.getInspection().getOriginalMessage().getName()).isEqualTo("Acme acquisition.eml");
        final Email draft = forwardOriginal(child);
        assertThatThrownBy(() -> MAIL.converter().fromEmail(draft).toEmlBytes()).isInstanceOf(MailException.class).hasMessageContaining("confidential");
        final byte[] permitted = MAIL.converter().fromEmail(draft).allowUnencryptedContent().toEmlBytes();
        final Email read = MAIL.converter().fromEml(permitted).toEmail();
        assertThat(read.getAttachments()).singleElement().satisfies(resource -> {
            assertThat(resource.readAllBytes()).containsExactly(childBytes);
            assertThat(resource.getName()).isEqualTo("original-message.eml");
            assertThat(resource.getDescription()).isNullOrEmpty();
            assertThat(resource.getContentId()).isNotEqualTo("private-original@example.org");
        });
        assertThatThrownBy(() -> MAIL.converter().fromEmail(draft).toEmlBytes()).isInstanceOf(MailException.class);
        final Email protectedDraft = MAIL.emailBuilder().copying(draft).encryptWithSmime(encryption()).buildEmail();
        final Email protectedRead = MAIL.converter().fromEml(MAIL.converter().fromEmail(protectedDraft).toEmlBytes())
                .withSmimeDecryption(loadPkcs12KeyStore()).toEmail();
        assertThat(protectedRead.getAttachments().get(0).readAllBytes()).containsExactly(childBytes);

        final Email edited = MAIL.emailBuilder().copying(child).withPlainText("New interpreted text").clearHTMLText().clearHeaders().buildEmail();
        assertThatThrownBy(() -> MAIL.converter().fromEmail(forwardOriginal(snapshot(edited))).toEmlBytes()).isInstanceOf(MailException.class);
        final Email withoutOriginal = MAIL.emailBuilder().copying(draft).clearAttachments().withPlainText("New public cover note").buildEmail();
        assertThat(MAIL.converter().fromEmail(withoutOriginal).toEmlBytes()).isNotEmpty();

        if (!encryptedChildBody) {
            final Email parentOriginal = forwardOriginal(fixture.email);
            final Email parentRead = MAIL.converter().fromEml(MAIL.converter().fromEmail(parentOriginal).toEmlBytes()).toEmail();
            assertThat(parentRead.getAttachments().get(0).readAllBytes()).containsExactly(fixture.original);
        }
        assertThat(child.getInspection().getOriginalMessage().getName()).isEqualTo("Acme acquisition.eml");
        assertThat(child.getInspection().getOriginalMessage().readAllBytes()).containsExactly(childBytes);
    }

    @Test
    void anIntactOpaqueEncryptedOriginalKeepsItsProtectionWhileNewCarrierMetadataIsNeutral() throws Exception {
        final Email authored = MAIL.converter().fromEml(PLAIN_CHILD).toEmail();
        final MimeMessage encrypted = EmailConverter.emlToMimeMessage(new ByteArrayInputStream(ProtectedHeaderFixture.encrypted(MAIL, authored)));
        final byte[] ciphertext;
        try (InputStream input = encrypted.getInputStream()) { ciphertext = input.readAllBytes(); }
        final AttachmentResource sealed = AttachmentResource.builder().withName("Acme acquisition.p7m")
                .withDescription("Acquisition plan").withContentId("private-original@example.org")
                .withData(ciphertext, encrypted.getContentType()).build();
        final Email child = parent(sealed).email.getAttachments().get(0).getEmail();
        assertThat(child).isNotNull();
        assertThat(child.getPlainText()).contains("Sensitive child body");
        final Email draft = forwardOriginal(child);
        final Email read = MAIL.converter().fromEml(MAIL.converter().fromEmail(draft).toEmlBytes()).toEmail();
        assertThat(read.getAttachments()).singleElement().satisfies(resource -> {
            assertThat(resource.readAllBytes()).containsExactly(ciphertext);
            assertThat(resource.getName()).isEqualTo("original-message.p7m");
            assertThat(resource.getDescription()).isNullOrEmpty();
            assertThat(resource.getContentId()).isNotEqualTo("private-original@example.org");
        });
        assertThat(child.getInspection().getOriginalMessage().getName()).isEqualTo("Acme acquisition.p7m");
        assertThat(child.getInspection().getOriginalMessage().readAllBytes()).containsExactly(ciphertext);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CRLF", "LF"})
    void ordinaryPlainOriginalForwardingDoesNotAcquireABlanketParsedMessageGate(final String lineEndings) {
        final byte[] supplied = "LF".equals(lineEndings) ? new String(PLAIN_CHILD, UTF_8).replace("\r\n", "\n").getBytes(UTF_8) : PLAIN_CHILD;
        final Email received = MAIL.converter().fromEml(supplied).toEmail();
        final Email draft = forwardOriginal(received);
        final Email read = MAIL.converter().fromEml(MAIL.converter().fromEmail(draft).toEmlBytes()).toEmail();
        assertThat(read.getAttachments().get(0).readAllBytes()).containsExactly(supplied);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CRLF", "LF"})
    void actualSmtpPreservesOriginalFilePayload(final String lineEndings) throws Exception {
        final byte[] supplied = "LF".equals(lineEndings) ? new String(PLAIN_CHILD, UTF_8).replace("\r\n", "\n").getBytes(UTF_8) : PLAIN_CHILD;
        final Email draft = forwardOriginal(MAIL.converter().fromEml(supplied).toEmail());
        final MimeMessage artifact = MAIL.converter().fromEmail(draft).toMimeMessage();
        final Multipart content = (Multipart) artifact.getContent();
        final BodyPart carrier = content.getBodyPart(content.getCount() - 1);
        try (RecordingSmtpServer server = new RecordingSmtpServer(); Mailer mailer = MAIL.mailerBuilder()
                .withSMTPServer("localhost", server.port())
                .withConnectionConfig(ConnectionConfig.builder().withSmtpClientHostname("probe.example.test").build())
                .withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).withMaxSize(1).build()).buildMailer()) {
            mailer.sync().sendMail(draft);
            assertThat(server.messages()).hasSize(1);
            final byte[] captured = server.messages().get(0);
            final Email received = MAIL.converter().fromEml(captured).toEmail();
            assertThat(received.getAttachments().get(0).readAllBytes())
                    .as("actual SMTP original payload for %s, carrier=%s, transfer-encoding=%s", lineEndings, carrier.getContentType(),
                            java.util.Arrays.toString(carrier.getHeader("Content-Transfer-Encoding")))
                    .containsExactly(supplied);
        }
    }

    private static Email forwardOriginal(final Email source) {
        return MAIL.emailBuilder().forwardingOriginal(source).from("forwarder@example.org").to("partner@example.org").buildEmail();
    }

    private static Parent parent(final AttachmentResource child) throws Exception {
        final Email authored = MAIL.emailBuilder().startingBlank().from("parent@example.org").to("reader@example.org")
                .withSubject("Parent topic").withPlainText("Parent sensitive message").withAttachment(child).buildEmail();
        final byte[] original = ProtectedHeaderFixture.encrypted(MAIL, authored);
        return new Parent(MAIL.converter().fromEml(original).withSmimeDecryption(loadPkcs12KeyStore()).toEmail(), original);
    }

    private static SmimeEncryptionConfig encryption() throws Exception {
        final Pkcs12Config keys = loadPkcs12KeyStore();
        final KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(keys.getPkcs12StoreData()), keys.getStorePassword());
        return SmimeEncryptionConfig.builder().x509Certificate((X509Certificate) store.getCertificate(keys.getKeyAlias())).build();
    }

    private static Email snapshot(final Email source) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(source); }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) { return (Email) input.readObject(); }
    }

    private static final class Parent {
        final Email email;
        final byte[] original;
        Parent(final Email email, final byte[] original) { this.email = email; this.original = original; }
    }
}
