package org.simplejavamail.converter.internal;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.converter.EmailConverter;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.util.Properties;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.email.ContentTransferEncoding.BASE_64;

class EscapedCidReferenceTest {

    private static final byte[] IMAGE = {0, 1, 2, 3};
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @ParameterizedTest
    @EnumSource(ImportRoute.class)
    void escapedReferencesUseTheSameResourceAcrossPublicImportRoutes(final ImportRoute route) throws Exception {
        final String html = "<img src=\"cid:logo%2F1@example.org\">";
        final String source = message("logo/1@example.org", html);
        final Email imported = convert(route, source);

        assertReferenced(imported, "logo/1@example.org", html);
        if (route == ImportRoute.EXACT) {
            assertThat(mail.converter().fromEmail(imported).toEmlBytes()).isEqualTo(source.getBytes(UTF_8));
        }
    }

    @ParameterizedTest
    @CsvSource({
            "logo/1@example.org, cid:logo%2F1@example.org",
            "logo/1@example.org, cid:logo%2f1@example.org",
            "logo%1@example.org, cid:logo%251@example.org",
            "logo+1@example.org, cid:logo+1@example.org",
            "logo+1@example.org, cid:logo%2B1@example.org",
            "logo'1@example.org, cid:logo'1@example.org",
            "logo(1)@example.org, cid:logo(1)@example.org",
            "logo%2F1@example.org, cid:logo%252F1@example.org",
            "Logo@example.org, CID:Logo@example.org",
            "Logo@example.org, cId:%4cogo@example.org",
            "logo@example.org, cid:logo@example.org"
    })
    void interpretsUriEscapesWithoutChangingIdentityOrHtml(final String cid, final String reference) {
        final String html = "<img src=\"" + reference + "\">";
        for (boolean eager : new boolean[] {false, true}) {
            assertReferenced(mail.converter().fromEml(message(cid, html)).withFetchAttachmentData(eager).toEmail(), cid, html);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "logo@example.org, cid:logo@example.org.extra",
            "Logo@example.org, cid:logo@example.org",
            "logo+1@example.org, cid:logo%201@example.org",
            "logo@example.org, cid:logo@example.org%",
            "logo@example.org, cid:logo@example.org%2",
            "logo@example.org, cid:logo@example.org%GG",
            "logo@example.org, notcid:logo@example.org",
            "logo@example.org, cid:logo@example.org%FF"
    })
    void unrelatedOrMalformedReferencesDoNotClaimAnImage(final String cid, final String reference) {
        final String html = "<img src=\"" + reference + "\">";
        for (boolean eager : new boolean[] {false, true}) {
            final Email imported = mail.converter().fromEml(message(cid, html)).withFetchAttachmentData(eager).toEmail();
            assertThat(imported.getHTMLText()).isEqualTo(html);
            assertThat(imported.getEmbeddedImages()).isEmpty();
            assertThat(imported.getAttachments()).extracting(AttachmentResource::getName).containsExactly("logo.png", "unused.png");
            assertThat(imported.getAttachments().get(0).getContentId()).isEqualTo(cid);
            assertThat(imported.getAttachments().get(0).readAllBytes()).isEqualTo(IMAGE);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cssReferencesAndReexportRetainTheResource(final boolean eager) {
        final String cid = "logo/1@example.org";
        final String html = "<div style=\"background-image:url(cid:logo%2F1@example.org)\"></div>";
        final Email imported = mail.converter().fromEml(message(cid, html)).withFetchAttachmentData(eager).toEmail();
        assertReferenced(imported, cid, html);

        final Email copied = mail.emailBuilder().copying(imported).buildEmail();
        assertReferenced(copied, cid, html);
        assertThat(copied.getEmbeddedImages().get(0).getDataSource()).isSameAs(imported.getEmbeddedImages().get(0).getDataSource());

        final String exported = mail.converter().fromEmail(copied).toEml();
        assertReferenced(mail.converter().fromEml(exported).withFetchAttachmentData(eager).toEmail(), cid, html);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void escapedReferenceAndDispositionFreeRelatedClassificationWorkTogether(final boolean eager) {
        final String cid = "logo/1@example.org";
        final String html = "<img src=\"cid:logo%2F1@example.org\">";
        final String source = message(cid, html).replace("Content-Disposition: inline; filename=logo.png\r\n", "");
        final Email imported = mail.converter().fromEml(source).withFetchAttachmentData(eager).toEmail();

        assertReferenced(imported, cid, html);
        final String exported = mail.converter().fromEmail(imported).toEml();
        assertReferenced(mail.converter().fromEml(exported).withFetchAttachmentData(eager).toEmail(), cid, html);
    }

    private static void assertReferenced(final Email imported, final String cid, final String html) {
        assertThat(imported.getHTMLText()).isEqualTo(html);
        assertThat(imported.getEmbeddedImages()).hasSize(1);
        final AttachmentResource image = imported.getEmbeddedImages().get(0);
        assertThat(image.getName()).isEqualTo("logo.png");
        assertThat(image.getContentId()).isEqualTo(cid);
        assertThat(image.getDescription()).isEqualTo("Company logo");
        assertThat(image.getContentTransferEncoding()).isEqualTo(BASE_64);
        assertThat(image.readAllBytes()).isEqualTo(IMAGE);
        assertThat(imported.getAttachments()).extracting(AttachmentResource::getName).containsExactly("unused.png");
    }

    private Email convert(final ImportRoute route, final String eml) throws Exception {
        switch (route) {
            case STATIC_EML: return EmailConverter.emlToEmail(eml);
            case FLUENT_EML_EAGER: return mail.converter().fromEml(eml).withFetchAttachmentData(true).toEmail();
            case FLUENT_EML_LAZY: return mail.converter().fromEml(eml).withFetchAttachmentData(false).toEmail();
            case EXACT: return mail.emailBuilder().startingFromExactEml(eml.getBytes(UTF_8)).withEnvelopeRecipients("reader@example.org").buildEmail();
            default:
                final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(eml.getBytes(UTF_8)));
                switch (route) {
                    case STATIC_MIME_EAGER: return EmailConverter.mimeMessageToEmail(message, null, true);
                    case STATIC_MIME_LAZY: return EmailConverter.mimeMessageToEmail(message, null, false);
                    case FLUENT_MIME_EAGER: return mail.converter().fromMimeMessage(message).withFetchAttachmentData(true).toEmail();
                    case FLUENT_MIME_LAZY: return mail.converter().fromMimeMessage(message).withFetchAttachmentData(false).toEmail();
                    default: throw new AssertionError(route);
                }
        }
    }

    private static String message(final String cid, final String html) {
        return "From: sender@example.org\r\nTo: reader@example.org\r\nSubject: Escaped CID\r\n"
                + "Date: Wed, 28 Oct 2026 10:00:00 +0100\r\nMessage-ID: <escaped-cid@example.org>\r\n"
                + "MIME-Version: 1.0\r\nContent-Type: multipart/related; boundary=cid-parts\r\n\r\n"
                + "--cid-parts\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n" + html + "\r\n"
                + "--cid-parts\r\nContent-Type: image/png; name=logo.png\r\nContent-Disposition: inline; filename=logo.png\r\n"
                + "Content-ID: <" + cid + ">\r\nContent-Description: Company logo\r\nContent-Transfer-Encoding: base64\r\n\r\nAAECAw==\r\n"
                + "--cid-parts\r\nContent-Type: image/png; name=unused.png\r\nContent-Disposition: inline; filename=unused.png\r\n"
                + "Content-ID: <unused@example.org>\r\nContent-Transfer-Encoding: base64\r\n\r\ndW51c2Vk\r\n--cid-parts--\r\n";
    }

    private enum ImportRoute {
        STATIC_EML, FLUENT_EML_EAGER, FLUENT_EML_LAZY, STATIC_MIME_EAGER, STATIC_MIME_LAZY, FLUENT_MIME_EAGER, FLUENT_MIME_LAZY, EXACT
    }
}
