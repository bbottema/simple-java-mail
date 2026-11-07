package org.simplejavamail.openpgp;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.DeliveryAction;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.OriginalOpenPgpDetails;
import org.simplejavamail.api.email.config.OpenPgpReceiveConfig;
import org.simplejavamail.internal.moduleloader.ModuleLoader;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class OpenPgpPlainMimeReadBoundaryTest {
    private final SimpleJavaMail mail = SimpleJavaMail.fromDefaults();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void attachmentFetchingAloneSelectsWhetherPlainMimeAttachmentContentIsRead(final boolean fetchAttachments) throws Exception {
        assertThat(ModuleLoader.openPgpModuleAvailable()).isTrue();
        final CountingSource source = new CountingSource("payload.bin", "application/octet-stream", "borrowed payload");
        final CountingMessage message = multipartMessage("mixed", textPart("Readable body"), resourcePart(source));
        source.reset();

        final Email imported = mail.converter().fromMimeMessage(message)
                .withFetchAttachmentData(fetchAttachments).toEmail();

        assertThat(message.serializations).as("negative OpenPGP recognition does not serialize ordinary MIME").isZero();
        assertPlain(imported);
        assertThat(imported.getPlainText()).isEqualTo("Readable body");
        assertThat(imported.getAttachments()).hasSize(1);
        assertThat(source.opens).isEqualTo(fetchAttachments ? 1 : 0);
        assertThat(source.closes).isEqualTo(source.opens);
        assertThat(imported.getAttachments().get(0).readAllBytes()).isEqualTo("borrowed payload".getBytes(UTF_8));
        assertThat(source.opens).as("eager snapshots avoid another source read; lazy resources defer their first read").isEqualTo(1);
        assertThat(source.closes).isEqualTo(1);
    }

    @Test
    void unavailableLazyAttachmentDoesNotPreventPlainProtectionFacts() throws Exception {
        final CountingSource source = new CountingSource("offline.bin", "application/octet-stream", "unused payload");
        final CountingMessage message = multipartMessage("mixed", textPart("Readable neighbor"), resourcePart(source));
        source.reset();
        source.unavailable = true;

        final Email imported = mail.converter().fromMimeMessage(message).withFetchAttachmentData(false).toEmail();

        assertThat(source.opens).as("unselected attachment content remains unopened").isZero();
        assertThat(message.serializations).isZero();
        assertPlain(imported);
        assertThat(imported.getPlainText()).isEqualTo("Readable neighbor");
        assertThat(imported.getAttachments()).hasSize(1);
    }

    @Test
    void unsavedOrdinaryMultipartDoesNotReadItsPendingAttachmentContent() throws Exception {
        final CountingSource source = new CountingSource("payload.bin", "application/octet-stream", "borrowed payload");
        final MimeMultipart content = new MimeMultipart("mixed");
        content.addBodyPart(textPart("Pending ordinary body"));
        content.addBodyPart(resourcePart(source));
        final CountingMessage message = new CountingMessage();
        message.setContent(content);
        assertThat(message.getHeader("Content-Type")).isNull();

        final Email imported = mail.converter().fromMimeMessage(message).withFetchAttachmentData(false).toEmail();

        assertThat(message.serializations).isZero();
        assertThat(source.opens).isZero();
        assertPlain(imported);
        assertThat(imported.getPlainText()).isEqualTo("Pending ordinary body");
        assertThat(imported.getAttachments()).hasSize(1);
    }

    @Test
    void ordinaryBodyInterpretationStillReadsItsSelectedStream() throws Exception {
        final CountingSource source = new CountingSource("body.txt", "text/plain; charset=UTF-8", "Readable body \u20ac");
        final CountingMessage message = new CountingMessage();
        message.setDataHandler(new DataHandler(source));
        message.saveChanges();
        source.reset();

        final Email imported = mail.converter().fromMimeMessage(message).withFetchAttachmentData(false).toEmail();

        assertThat(message.serializations).isZero();
        assertPlain(imported);
        assertThat(imported.getPlainText()).isEqualTo("Readable body \u20ac");
        assertThat(source.opens).isEqualTo(1);
        assertThat(source.closes).isEqualTo(1);
    }

    @Test
    void optionalDeliveryReportInterpretationStillReadsSelectedStatusContent() throws Exception {
        final CountingSource status = new CountingSource("delivery-status", "message/delivery-status",
                "Reporting-MTA: dns; relay.example.org\r\n\r\n"
                        + "Final-Recipient: rfc822; receiver@example.org\r\nAction: failed\r\nStatus: 5.1.1\r\n");
        final CountingMessage message = multipartMessage("report", textPart("Delivery failed"), resourcePart(status));
        message.setHeader("Content-Type", message.getContentType() + "; report-type=delivery-status");
        status.reset();

        final Email imported = mail.converter().fromMimeMessage(message).withFetchAttachmentData(false).toEmail();

        assertThat(message.serializations).isZero();
        assertPlain(imported);
        assertThat(imported.getPlainText()).isEqualTo("Delivery failed");
        assertThat(imported.getDeliveryStatusReport()).isNotNull();
        assertThat(imported.getDeliveryStatusReport().getRecipients()).hasSize(1);
        assertThat(imported.getDeliveryStatusReport().getRecipients().get(0).getAction()).isEqualTo(DeliveryAction.FAILED);
        assertThat(status.opens).isEqualTo(1);
        assertThat(status.closes).isEqualTo(1);
    }

    @Test
    void defaultedPlainDeclarationDoesNotRequireSerialization() throws Exception {
        final CountingMessage message = new CountingMessage();
        message.setText("Default plain body", UTF_8.name());
        message.saveChanges();
        message.removeHeader("Content-Type");

        final Email imported = mail.converter().fromMimeMessage(message).toEmail();

        assertThat(message.serializations).isZero();
        assertPlain(imported);
        assertThat(imported.getPlainText()).isEqualTo("Default plain body");
    }

    @ParameterizedTest
    @ValueSource(strings = {"signed-mixed.eml", "encrypted-mixed.eml"})
    void unsavedNativeProtectedContentStillUsesTheExistingProtectionPath(final String fixtureName) throws Exception {
        final MimeMessage fixture = new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(resource(fixtureName)));
        final CountingMessage message = new CountingMessage();
        message.setContent((MimeMultipart) fixture.getContent());
        assertThat(message.getHeader("Content-Type")).as("native content headers are still pending").isNull();
        assertThat(message.getContentType()).isEqualTo("text/plain");
        final OpenPgpReceiveConfig keys = OpenPgpReceiveConfig.builder()
                .addVerificationKeyRing(resource("public-key.asc"))
                .addDecryptionKeyRing(resource("private-key.asc"), "openpgpjs-fixture-passphrase".toCharArray())
                .build();

        final Email imported = mail.converter().fromMimeMessage(message)
                .withFetchAttachmentData(false).withOpenPgpReceiveConfig(keys).toEmail();

        assertThat(message.serializations).as("pending protected content still needs its finalized representation").isEqualTo(1);
        assertThat(imported.getPlainText()).contains("H\u00e9llo from OpenPGP.js");
        assertThat(imported.getOriginalOpenPgpDetails().getOriginalProtectedMessage()).isNotEmpty();
        if (fixtureName.startsWith("signed")) {
            assertThat(imported.getOriginalOpenPgpDetails().getOpenPgpMode()).isEqualTo(OriginalOpenPgpDetails.OpenPgpMode.SIGNED);
            assertThat(imported.getOriginalOpenPgpDetails().getSignatureStatus()).isEqualTo(OriginalOpenPgpDetails.SignatureStatus.VALID);
        } else {
            assertThat(imported.getOriginalOpenPgpDetails().getDecryptionStatus()).isEqualTo(OriginalOpenPgpDetails.DecryptionStatus.DECRYPTED);
        }
    }

    @Test
    void pendingProtectedMetadataDoesNotReplaceAnExistingOrdinaryDeclaration() throws Exception {
        final MimeMessage fixture = new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(resource("signed-mixed.eml")));
        final CountingMessage message = new CountingMessage();
        message.setContent((MimeMultipart) fixture.getContent());
        message.setHeader("Content-Type", "text/plain");

        final Email imported = mail.converter().fromMimeMessage(message).withFetchAttachmentData(false).toEmail();

        assertThat(message.serializations).isZero();
        assertPlain(imported);
        assertThat(message.getContentType()).isEqualTo("text/plain");
    }

    private static byte[] resource(final String name) throws IOException {
        try (InputStream input = OpenPgpPlainMimeReadBoundaryTest.class.getResourceAsStream("/openpgpjs/" + name)) {
            if (input == null) throw new AssertionError("Missing fixture: " + name);
            return input.readAllBytes();
        }
    }

    private static void assertPlain(final Email email) {
        final OriginalOpenPgpDetails details = email.getOriginalOpenPgpDetails();
        assertThat(details.getOpenPgpMode()).isEqualTo(OriginalOpenPgpDetails.OpenPgpMode.PLAIN);
        assertThat(details.getSignatureStatus()).isEqualTo(OriginalOpenPgpDetails.SignatureStatus.NOT_PRESENT);
        assertThat(details.getDecryptionStatus()).isEqualTo(OriginalOpenPgpDetails.DecryptionStatus.NOT_ENCRYPTED);
    }

    private static CountingMessage multipartMessage(final String subtype, final MimeBodyPart... parts) throws MessagingException {
        final MimeMultipart content = new MimeMultipart(subtype);
        for (MimeBodyPart part : parts) content.addBodyPart(part);
        final CountingMessage message = new CountingMessage();
        message.setContent(content);
        message.saveChanges();
        return message;
    }

    private static MimeBodyPart textPart(final String value) throws MessagingException {
        final MimeBodyPart part = new MimeBodyPart();
        part.setText(value, UTF_8.name());
        return part;
    }

    private static MimeBodyPart resourcePart(final CountingSource source) throws MessagingException {
        final MimeBodyPart part = new MimeBodyPart();
        part.setDataHandler(new DataHandler(source));
        part.setFileName(source.getName());
        part.setDisposition(MimeBodyPart.ATTACHMENT);
        return part;
    }

    private static final class CountingMessage extends MimeMessage {
        private int serializations;

        private CountingMessage() {
            super(Session.getInstance(new Properties()));
        }

        @Override
        public void writeTo(final OutputStream output) throws IOException, MessagingException {
            serializations++;
            super.writeTo(output);
        }
    }

    private static final class CountingSource implements DataSource {
        private final String name;
        private final String type;
        private final byte[] content;
        private int opens;
        private int closes;
        private boolean unavailable;

        private CountingSource(final String name, final String type, final String content) {
            this.name = name;
            this.type = type;
            this.content = content.getBytes(UTF_8);
        }

        private void reset() {
            opens = 0;
            closes = 0;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            opens++;
            if (unavailable) throw new IOException("Attachment is offline");
            return new FilterInputStream(new ByteArrayInputStream(content)) {
                @Override
                public void close() throws IOException {
                    closes++;
                    super.close();
                }
            };
        }

        @Override public OutputStream getOutputStream() throws IOException { throw new IOException("Read-only test source"); }
        @Override public String getContentType() { return type; }
        @Override public String getName() { return name; }
    }
}
