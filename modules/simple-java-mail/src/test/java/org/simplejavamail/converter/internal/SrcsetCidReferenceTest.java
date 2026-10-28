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

class SrcsetCidReferenceTest {

    private static final byte[] IMAGE = {0, 1, 2, 3};
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @ParameterizedTest
    @EnumSource(ImportRoute.class)
    void encodedCandidatesStayEmbeddedAcrossPublicImportRoutes(final ImportRoute route) throws Exception {
        final String html = "<img srcset=\"cid:logo&#64;example.org 1x, cid:large&#64;example.org 2x\">";
        final String source = message(html, "logo@example.org", "large@example.org");
        final Email imported = convert(route, source);

        assertEmbedded(imported, html, "logo@example.org", "large@example.org");
        if (route == ImportRoute.EXACT) {
            assertThat(mail.converter().fromEmail(imported).toEmlBytes()).isEqualTo(source.getBytes(UTF_8));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<img srcset=\"cid:logo@example.org 1x, cid:large@example.org 2x\">",
            "<img srcset=\"cid:logo&#64;example.org 1x, cid:large&#64;example.org 2x\">",
            "<img srcset=\"cid:logo@example.org, cid:large@example.org 2x\">",
            "<img srcset=\"cid:logo&#64;example.org, cid:large&#64;example.org 2x\">",
            "<img srcset=\"cid:logo@example.org 640w, cid:large@example.org 1280w\" sizes=\"100vw\">",
            "<img srcset=\"cid:logo&#64;example.org&#32;640w, cid:large&#64;example.org&#9;1280w\" sizes=\"100vw\">",
            "<picture><source SRCSET='CID:logo&#64;example.org 1x, cId:large&#64;example.org 2x'></picture>",
            "<img srcset=\"&#99;id:logo&#64;example.org 1x, cid:large@example.org 2x\">"
    })
    void equivalentCandidateListsSurviveCopyingAndComposedReexport(final String html) {
        for (boolean eager : new boolean[] {false, true}) {
            final Email imported = mail.converter().fromEml(message(html, "logo@example.org", "large@example.org"))
                    .withFetchAttachmentData(eager).toEmail();
            assertEmbedded(imported, html, "logo@example.org", "large@example.org");
            final Email copied = mail.emailBuilder().copying(imported).buildEmail();
            assertEmbedded(copied, html, "logo@example.org", "large@example.org");
            assertThat(copied.getEmbeddedImages().get(0).getDataSource()).isSameAs(imported.getEmbeddedImages().get(0).getDataSource());

            final String exported = mail.converter().fromEmail(copied).toEml();
            assertThat(exported).contains("multipart/related").doesNotContain("Content-Disposition: attachment");
            assertEmbedded(mail.converter().fromEml(exported).withFetchAttachmentData(eager).toEmail(),
                    html, "logo@example.org", "large@example.org");
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "logo@example.org|cid:logo&#64;example.org 1x",
            "logo@example.org|cid:logo@example.org",
            "logo@example.org|cid:logo&#64;example.org",
            "logo@example.org|cid:logo@example.org,",
            "logo@example.org|cid:logo&#64;example.org,",
            "logo,small@example.org|cid:logo,small&#64;example.org 1x",
            "logo,small@example.org|cid:logo,small@example.org 1x",
            "logo,small@example.org|cid:logo%2Csmall&#64;example.org 1x",
            "logo,@example.org|cid:logo%2C&#64;example.org,",
            "logo'1@example.org|cid:logo&apos;1@example.org 1x",
            "logo\"1@example.org|cid:logo&quot;1@example.org 1x",
            "logo(1)@example.org|cid:logo(1)&#64;example.org 1x",
            "logo%2F1@example.org|cid:logo&#37;252F1@example.org 1x",
            "logo+1@example.org|cid:logo&#43;1@example.org 1x"
    })
    void candidateBoundariesDoNotTruncateCidIdentity(final String cid, final String value) {
        final String html = "<img srcset=\"" + value + "\">";
        assertEmbedded(mail.converter().fromEml(message(html, cid)).toEmail(), html, cid);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<img srcset=\"cid:logo&#64;example.org.extra 1x\">",
            "<img srcset=\"cid:Logo&#64;example.org 1x\">",
            "<img srcset=\"cid:logo&#37;GG@example.org 1x\">",
            "<img srcset=\"cid:logo&#37;FF@example.org 1x\">",
            "<img srcset=\"cid:logo&amp;#64;example.org 1x\">",
            "<img srcset=\"https://example.org/cid:logo&#64;example.org 1x\">"
    })
    void malformedAndUnrelatedCandidateUrlsDoNotClaimAnImage(final String html) {
        final Email imported = mail.converter().fromEml(message(html, "logo@example.org")).toEmail();
        assertThat(imported.getHTMLText()).isEqualTo(html);
        assertThat(imported.getEmbeddedImages()).isEmpty();
        assertThat(imported.getAttachments()).extracting(AttachmentResource::getContentId).containsExactly("logo@example.org");
        assertThat(imported.getAttachments().get(0).readAllBytes()).isEqualTo(IMAGE);
    }

    private static void assertEmbedded(final Email imported, final String html, final String... cids) {
        assertThat(imported.getHTMLText()).isEqualTo(html);
        assertThat(imported.getEmbeddedImages()).extracting(AttachmentResource::getContentId).containsExactlyInAnyOrder(cids);
        assertThat(imported.getAttachments()).isEmpty();
        for (AttachmentResource image : imported.getEmbeddedImages()) {
            assertThat(image.getName()).startsWith("image-").endsWith(".png");
            assertThat(image.getDescription()).isEqualTo("Source image");
            assertThat(image.readAllBytes()).isEqualTo(IMAGE);
        }
    }

    private Email convert(final ImportRoute route, final String eml) throws Exception {
        switch (route) {
            case STATIC_EML: return EmailConverter.emlToEmail(eml);
            case FLUENT_EML_EAGER: return mail.converter().fromEml(eml).withFetchAttachmentData(true).toEmail();
            case FLUENT_EML_LAZY: return mail.converter().fromEml(eml).withFetchAttachmentData(false).toEmail();
            case EXACT: return mail.emailBuilder().startingFromExactEml(eml.getBytes(UTF_8))
                    .withEnvelopeRecipients("reader@example.org").buildEmail();
            default:
                final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()),
                        new ByteArrayInputStream(eml.getBytes(UTF_8)));
                switch (route) {
                    case STATIC_MIME_EAGER: return EmailConverter.mimeMessageToEmail(message, null, true);
                    case STATIC_MIME_LAZY: return EmailConverter.mimeMessageToEmail(message, null, false);
                    case FLUENT_MIME_EAGER: return mail.converter().fromMimeMessage(message).withFetchAttachmentData(true).toEmail();
                    case FLUENT_MIME_LAZY: return mail.converter().fromMimeMessage(message).withFetchAttachmentData(false).toEmail();
                    default: throw new AssertionError(route);
                }
        }
    }

    private static String message(final String html, final String... cids) {
        final StringBuilder source = new StringBuilder("From: sender@example.org\r\nTo: reader@example.org\r\nSubject: Srcset CID\r\n"
                + "Date: Wed, 28 Oct 2026 10:00:00 +0100\r\nMessage-ID: <srcset-cid@example.org>\r\n"
                + "MIME-Version: 1.0\r\nContent-Type: multipart/related; boundary=srcset-parts\r\n\r\n"
                + "--srcset-parts\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n" + html + "\r\n");
        for (int index = 0; index < cids.length; index++) {
            source.append("--srcset-parts\r\nContent-Type: image/png; name=image-").append(index).append(".png\r\n")
                    .append("Content-Disposition: inline; filename=image-").append(index).append(".png\r\n")
                    .append("Content-ID: <").append(cids[index]).append(">\r\nContent-Description: Source image\r\n")
                    .append("Content-Transfer-Encoding: base64\r\n\r\nAAECAw==\r\n");
        }
        return source.append("--srcset-parts--\r\n").toString();
    }

    private enum ImportRoute {
        STATIC_EML, FLUENT_EML_EAGER, FLUENT_EML_LAZY, STATIC_MIME_EAGER, STATIC_MIME_LAZY,
        FLUENT_MIME_EAGER, FLUENT_MIME_LAZY, EXACT
    }
}
