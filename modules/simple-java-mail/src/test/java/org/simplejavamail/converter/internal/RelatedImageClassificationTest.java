package org.simplejavamail.converter.internal;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.mail.Multipart;
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

class RelatedImageClassificationTest {

    private static final String HTML = "<img src=\"cid:logo@example.org\">";
    private static final byte[] IMAGE = {0, 1, 2, 3};
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void dispositionFreeRelatedImageIsImportedAndExportedOnce(final boolean eager) throws Exception {
        final Email imported = importEmail(message("related", HTML, image("logo@example.org", "")), eager);

        assertThat(imported.getAttachments()).isEmpty();
        assertThat(imported.getEmbeddedImages()).hasSize(1);
        assertLogo(imported.getEmbeddedImages().get(0));
        final MimeMessage exported = mail.converter().fromEmail(imported).toMimeMessage();
        assertThat(countImages(exported)).isEqualTo(1);
        final Email reimported = mail.converter().fromMimeMessage(exported).withFetchAttachmentData(eager).toEmail();
        assertThat(reimported.getAttachments()).isEmpty();
        assertThat(reimported.getEmbeddedImages()).hasSize(1);
        assertLogo(reimported.getEmbeddedImages().get(0));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void relatedContainerNestedInsideMixedKeepsOnlyItsOwnImageEmbedded(final boolean eager) {
        final String nested = "--parts\r\nContent-Type: multipart/related; boundary=related\r\n\r\n"
                + "--related\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n" + HTML + "\r\n"
                + image("logo@example.org", "").replace("--parts", "--related") + "--related--\r\n";
        final Email imported = importEmail(headers("mixed") + nested
                + "--parts\r\nContent-Type: application/octet-stream; name=document.bin\r\n"
                + "Content-Disposition: attachment; filename=document.bin\r\n\r\nordinary\r\n--parts--\r\n", eager);

        assertThat(imported.getEmbeddedImages()).hasSize(1);
        assertThat(imported.getAttachments()).extracting(AttachmentResource::getName).containsExactly("document.bin");
        assertLogo(imported.getEmbeddedImages().get(0));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unreferencedDispositionFreeRelatedImageRemainsOneAttachment(final boolean eager) {
        final Email imported = importEmail(message("related", "<p>No image reference</p>", image("logo@example.org", "")), eager);

        assertThat(imported.getEmbeddedImages()).isEmpty();
        assertThat(imported.getAttachments()).hasSize(1);
        assertLogo(imported.getAttachments().get(0));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unreferencedRelatedImagesPreserveTheirSourceOrder(final boolean eager) {
        final Email imported = importEmail(message("related", "<p>No image references</p>",
                image("z@example.org", "") + image("a@example.org", "")), eager);

        assertThat(imported.getEmbeddedImages()).isEmpty();
        assertThat(imported.getAttachments()).extracting(AttachmentResource::getContentId)
                .containsExactly("z@example.org", "a@example.org");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unreferencedRelatedImagesKeepTheirSlotsAroundOrdinaryAttachments(final boolean eager) {
        final Email imported = importEmail(message("related", "<p>No image references</p>",
                image("z@example.org", "") + ordinaryAttachment() + image("a@example.org", "")), eager);

        assertThat(imported.getEmbeddedImages()).isEmpty();
        assertThat(imported.getAttachments()).extracting(AttachmentResource::getContentId)
                .containsExactly("z@example.org", null, "a@example.org");
        assertThat(imported.getAttachments()).extracting(AttachmentResource::getName)
                .containsExactly("logo.png", "document.bin", "logo.png");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void removingReferencedRelatedImagesKeepsTheRemainingAttachmentOrder(final boolean eager) {
        final Email imported = importEmail(message("related", HTML,
                image("z@example.org", "") + image("logo@example.org", "")
                        + ordinaryAttachment() + image("a@example.org", "")), eager);

        assertThat(imported.getEmbeddedImages()).extracting(AttachmentResource::getContentId)
                .containsExactly("logo@example.org");
        assertThat(imported.getAttachments()).extracting(AttachmentResource::getContentId)
                .containsExactly("z@example.org", null, "a@example.org");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitlyAttachedRelatedImageRemainsBothDownloadableAndEmbedded(final boolean eager) {
        final Email imported = importEmail(message("related", HTML,
                image("logo@example.org", "Content-Disposition: attachment; filename=logo.png\r\n")), eager);

        assertThat(imported.getEmbeddedImages()).hasSize(1);
        assertThat(imported.getAttachments()).hasSize(1);
        assertLogo(imported.getEmbeddedImages().get(0));
        assertLogo(imported.getAttachments().get(0));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitlyInlineRelatedImageRemainsOneEmbeddedResource(final boolean eager) {
        final Email imported = importEmail(message("related", HTML,
                image("logo@example.org", "Content-Disposition: inline; filename=logo.png\r\n")), eager);

        assertThat(imported.getEmbeddedImages()).hasSize(1);
        assertThat(imported.getAttachments()).isEmpty();
        assertLogo(imported.getEmbeddedImages().get(0));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void dispositionFreeImageOutsideRelatedKeepsExistingClassification(final boolean eager) {
        final Email imported = importEmail(message("mixed", HTML, image("logo@example.org", "")), eager);

        assertThat(imported.getEmbeddedImages()).hasSize(1);
        assertThat(imported.getAttachments()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void twoDistinctSourceImagesWithTheSameFilenameAreRetained(final boolean eager) {
        final Email imported = importEmail(message("related", HTML + "<img src=\"cid:other@example.org\">",
                image("logo@example.org", "") + image("other@example.org", "")), eager);

        assertThat(imported.getEmbeddedImages()).extracting(AttachmentResource::getContentId)
                .containsExactly("logo@example.org", "other@example.org");
        assertThat(imported.getAttachments()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void relatedImageWithoutContentIdRemainsOneAttachment(final boolean eager) {
        final String imageWithoutId = image("logo@example.org", "").replace("Content-ID: <logo@example.org>\r\n", "");
        final Email imported = importEmail(message("related", HTML, imageWithoutId), eager);

        assertThat(imported.getEmbeddedImages()).isEmpty();
        assertThat(imported.getAttachments()).hasSize(1);
        assertThat(imported.getAttachments().get(0).getContentId()).isNull();
        assertThat(imported.getAttachments().get(0).readAllBytes()).containsExactly(IMAGE);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void relatedNonImageResourceKeepsExistingClassification(final boolean eager) {
        final String document = image("logo@example.org", "").replace("image/png", "application/pdf");
        final Email imported = importEmail(message("related", HTML, document), eager);

        assertThat(imported.getEmbeddedImages()).hasSize(1);
        assertThat(imported.getAttachments()).hasSize(1);
    }

    @Test
    void releasedStaticConversionUsesTheSameRelatedClassification() {
        final Email imported = EmailConverter.emlToEmail(message("related", HTML, image("logo@example.org", "")));

        assertThat(imported.getEmbeddedImages()).hasSize(1);
        assertThat(imported.getAttachments()).isEmpty();
        assertLogo(imported.getEmbeddedImages().get(0));
    }

    @Test
    void exactInspectionClassifiesTheRelatedImageWithoutChangingAuthoritativeBytes() {
        final byte[] source = message("related", HTML, image("logo@example.org", "")).getBytes(UTF_8);
        final Email exact = mail.emailBuilder().startingFromExactEml(source).withEnvelopeRecipients("reader@example.org").buildEmail();

        assertThat(exact.getEmbeddedImages()).hasSize(1);
        assertThat(exact.getAttachments()).isEmpty();
        assertLogo(exact.getEmbeddedImages().get(0));
        assertThat(mail.converter().fromEmail(exact).toEmlBytes()).containsExactly(source);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void classificationAddsNoContentReadsAndRetainsParserStreamOwnership(final boolean eager) throws Exception {
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final MimeBodyPart image = new MimeBodyPart();
        image.setDataHandler(new DataHandler(new DataSource() {
            @Override public InputStream getInputStream() {
                reads.incrementAndGet();
                return new ByteArrayInputStream(IMAGE) {
                    @Override public void close() { closes.incrementAndGet(); }
                };
            }
            @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
            @Override public String getContentType() { return "image/png"; }
            @Override public String getName() { return "logo.png"; }
        }));
        image.setHeader("Content-Type", "image/png; name=logo.png");
        image.setContentID("<logo@example.org>");
        final MimeBodyPart html = new MimeBodyPart();
        html.setContent(HTML, "text/html; charset=UTF-8");
        final MimeMultipart related = new MimeMultipart("related");
        related.addBodyPart(html);
        related.addBodyPart(image);
        final MimeMessage source = new MimeMessage(Session.getInstance(new Properties()));
        source.setContent(related);

        final Email imported = MimeMessageEmailMapper.populate(mail.emailBuilder().startingBlank(),
                MimeMessageParser.parseMimeMessage(source, eager)).buildEmail();

        assertThat(imported.getEmbeddedImages()).hasSize(1);
        assertThat(imported.getAttachments()).isEmpty();
        assertThat(reads.get()).isEqualTo(eager ? 1 : 0);
        assertThat(closes.get()).isEqualTo(eager ? 1 : 0);
    }

    private Email importEmail(final String eml, final boolean eager) {
        return mail.converter().fromEml(eml).withFetchAttachmentData(eager).toEmail();
    }

    private static void assertLogo(final AttachmentResource image) {
        assertThat(image.getName()).isEqualTo("logo.png");
        assertThat(image.getContentId()).isEqualTo("logo@example.org");
        assertThat(image.getDescription()).isEqualTo("Company logo");
        assertThat(image.getContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BASE_64);
        assertThat(image.getPreEncodedContentTransferEncoding()).isNull();
        assertThat(image.readAllBytes()).containsExactly(IMAGE);
    }

    private static int countImages(final Part part) throws Exception {
        if (part.isMimeType("image/*")) return 1;
        if (!part.isMimeType("multipart/*")) return 0;
        final Multipart multipart = (Multipart) part.getContent();
        int images = 0;
        for (int index = 0; index < multipart.getCount(); index++) images += countImages(multipart.getBodyPart(index));
        return images;
    }

    private static String message(final String subtype, final String html, final String resources) {
        return headers(subtype) + "--parts\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n"
                + html + "\r\n" + resources + "--parts--\r\n";
    }

    private static String headers(final String subtype) {
        return "From: sender@example.org\r\nTo: reader@example.org\r\nSubject: Related image\r\n"
                + "MIME-Version: 1.0\r\nContent-Type: multipart/" + subtype + "; boundary=parts\r\n\r\n";
    }

    private static String image(final String id, final String disposition) {
        return "--parts\r\nContent-Type: image/png; name=logo.png\r\n" + disposition
                + "Content-ID: <" + id + ">\r\nContent-Description: Company logo\r\n"
                + "Content-Transfer-Encoding: base64\r\n\r\nAAECAw==\r\n";
    }

    private static String ordinaryAttachment() {
        return "--parts\r\nContent-Type: application/octet-stream; name=document.bin\r\n"
                + "Content-Disposition: attachment; filename=document.bin\r\n\r\nordinary\r\n";
    }
}
