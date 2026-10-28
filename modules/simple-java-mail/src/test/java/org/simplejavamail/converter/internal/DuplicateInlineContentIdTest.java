package org.simplejavamail.converter.internal;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageParser;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class DuplicateInlineContentIdTest {

    private static final String CID = "same@example.org";
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fluentEmlImportRetainsUnreferencedOccurrencesInSourceOrder(final boolean eager) {
        assertRetainedResources(mail.converter().fromEml(message("body", false)).withFetchAttachmentData(eager).toEmail());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fluentMimeImportRetainsUnreferencedOccurrencesInSourceOrder(final boolean eager) throws Exception {
        final MimeMessage source = new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(message("body", false).getBytes(UTF_8)));
        assertRetainedResources(mail.converter().fromMimeMessage(source).withFetchAttachmentData(eager).toEmail());
    }

    @Test
    void staticEmlImportRetainsUnreferencedOccurrences() {
        assertRetainedResources(EmailConverter.emlToEmail(message("body", false)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void staticMimeImportRetainsUnreferencedOccurrences(final boolean eager) throws Exception {
        final MimeMessage source = new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(message("body", false).getBytes(UTF_8)));
        assertRetainedResources(EmailConverter.mimeMessageToEmailBuilder(source, null, eager).buildEmail());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void repeatedNamesAndIdsDoNotMergeDistinctSourceContent(final boolean eager) {
        final Email imported = mail.converter().fromEml(message("body", false).replace("second.png", "first.png"))
                .withFetchAttachmentData(eager).toEmail();

        assertThat(imported.getAttachments()).extracting(AttachmentResource::getName)
                .containsExactly("first.png", "middle.bin", "first.png");
        assertThat(imported.getAttachments().get(0).readAllBytes()).containsExactly(1);
        assertThat(imported.getAttachments().get(2).readAllBytes()).containsExactly(2);
    }

    @Test
    void exactInspectionRetainsOccurrencesWithoutChangingSourceBytes() {
        final byte[] source = message("body", false).getBytes(UTF_8);
        final Email exact = mail.emailBuilder().startingFromExactEml(source)
                .withEnvelopeRecipients("reader@example.org").buildEmail();

        assertRetainedResources(exact);
        assertThat(mail.converter().fromEmail(exact).toEmlBytes()).containsExactly(source);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void referencedDuplicateKeepsTheExistingSelectedResource(final boolean eager) {
        final Email imported = mail.converter().fromEml(message("<img src=\"cid:" + CID + "\">", true))
                .withFetchAttachmentData(eager).toEmail();

        assertThat(imported.getAttachments()).extracting(AttachmentResource::getName).containsExactly("middle.bin");
        assertThat(imported.getEmbeddedImages()).extracting(AttachmentResource::getName).containsExactly("second.png");
        assertThat(imported.getEmbeddedImages().get(0).readAllBytes()).containsExactly(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retainingOccurrencesAddsNoParserReadsAndClosesOnlyOpenedStreams(final boolean eager) throws Exception {
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final MimeMultipart content = new MimeMultipart("mixed");
        content.addBodyPart(trackedInline("first.png", (byte) 1, reads, closes));
        content.addBodyPart(trackedInline("second.png", (byte) 2, reads, closes));
        final MimeMessage source = new MimeMessage(Session.getInstance(new Properties()));
        source.setContent(content);

        final Email imported = MimeMessageEmailMapper.populate(mail.emailBuilder().startingBlank(),
                MimeMessageParser.parseMimeMessage(source, eager)).buildEmail();

        assertThat(imported.getAttachments()).extracting(AttachmentResource::getName)
                .containsExactly("first.png", "second.png");
        assertThat(reads.get()).isEqualTo(eager ? 2 : 0);
        assertThat(closes.get()).isEqualTo(eager ? 2 : 0);
        assertThat(imported.getAttachments().get(0).readAllBytes()).containsExactly(1);
        assertThat(imported.getAttachments().get(1).readAllBytes()).containsExactly(2);
        assertThat(reads.get()).isEqualTo(2);
        assertThat(closes.get()).isEqualTo(2);
    }

    private static void assertRetainedResources(final Email email) {
        assertThat(email.getEmbeddedImages()).isEmpty();
        assertThat(email.getAttachments()).extracting(AttachmentResource::getName)
                .containsExactly("first.png", "middle.bin", "second.png");
        assertThat(email.getAttachments()).extracting(AttachmentResource::getContentId).containsExactly(CID, null, CID);
        assertThat(email.getAttachments().get(0).readAllBytes()).containsExactly(1);
        assertThat(email.getAttachments().get(1).readAllBytes()).containsExactly(3);
        assertThat(email.getAttachments().get(2).readAllBytes()).containsExactly(2);
        assertThat(email.getAttachments().get(0).getContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BASE_64);
        assertThat(email.getAttachments().get(2).getContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BASE_64);
    }

    private static String message(final String body, final boolean html) {
        return "From: sender@example.org\r\nTo: reader@example.org\r\nSubject: Repeated inline IDs\r\n"
                + "MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=parts\r\n\r\n"
                + "--parts\r\nContent-Type: text/" + (html ? "html" : "plain") + "; charset=UTF-8\r\n\r\n" + body + "\r\n"
                + resource("first.png", "inline", CID, "AQ==")
                + resource("middle.bin", "attachment", null, "Aw==")
                + resource("second.png", "inline", CID, "Ag==") + "--parts--\r\n";
    }

    private static String resource(final String name, final String disposition, final String cid, final String encoded) {
        return "--parts\r\nContent-Type: " + (name.endsWith(".png") ? "image/png" : "application/octet-stream") + "\r\n"
                + "Content-Disposition: " + disposition + "; filename=" + name + "\r\n"
                + (cid == null ? "" : "Content-ID: <" + cid + ">\r\n")
                + "Content-Transfer-Encoding: base64\r\n\r\n" + encoded + "\r\n";
    }

    private static MimeBodyPart trackedInline(final String name, final byte data, final AtomicInteger reads,
            final AtomicInteger closes) throws Exception {
        final MimeBodyPart part = new MimeBodyPart();
        part.setDataHandler(new DataHandler(new DataSource() {
            @Override public InputStream getInputStream() {
                reads.incrementAndGet();
                return new ByteArrayInputStream(new byte[] {data}) {
                    @Override public void close() { closes.incrementAndGet(); }
                };
            }
            @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
            @Override public String getContentType() { return "image/png"; }
            @Override public String getName() { return name; }
        }));
        part.setHeader("Content-Type", "image/png");
        part.setDisposition(Part.INLINE);
        part.setFileName(name);
        part.setContentID("<" + CID + ">");
        return part;
    }
}
