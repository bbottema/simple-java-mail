package org.simplejavamail.converter.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.converter.EmailConverter;
import testutil.ConfigLoaderTestHelper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class HtmlCharacterReferenceCidTest {
    private static final byte[] IMAGE = {0, 1, 2, 3};
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @ParameterizedTest
    @EnumSource(ImportRoute.class)
    void characterReferencesIdentifyTheImageAcrossImportRoutes(final ImportRoute route) {
        final String html = "<img src=\"cid:logo&#64;example.org\">";
        final String source = message("logo@example.org", html);
        final Email imported = convert(route, source);

        assertReferenced(imported, "logo@example.org", html);
        if (route == ImportRoute.EXACT) {
            assertThat(mail.converter().fromEmail(imported).toEmlBytes()).isEqualTo(source.getBytes(UTF_8));
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "logo@example.org|<img src=\"cid:logo&#64;example.org\">",
            "logo@example.org|<img src='cid:logo&#x40;example.org'>",
            "logo@example.org|<img src=\"cid:logo&#64;example.org \">",
            "logo@example.org|<img src=\" cid:logo&#64;example.org \">",
            "logo@example.org|<img src=cid:logo&commat;example.org>",
            "logo&tag@example.org|<img src=\"cid:logo&amp;tag@example.org\">",
            "logo/1@example.org|<img src=\"cid:logo&#37;2F1@example.org\">",
            "logo%2F1@example.org|<img src=\"cid:logo&#37;252F1@example.org\">",
            "logo+1@example.org|<img src=\"cid:logo&#43;1@example.org\">",
            "Logo@example.org|<img src=\"c&#105;d:Logo&commat;example.org\">",
            "logo@example.org|<div style=\"background-image:url(cid:logo&#64;example.org)\"></div>",
            "logo'1@example.org|<img src=\"cid:logo&apos;1@example.org\">",
            "logo\"1@example.org|<img src=\"cid:logo&quot;1@example.org\">"
    })
    void decodesAttributeValuesOnceWithoutChangingHtmlOrResourceMetadata(final String cid, final String html) {
        for (boolean eager : new boolean[] {false, true}) {
            final Email imported = mail.converter().fromEml(message(cid, html)).withFetchAttachmentData(eager).toEmail();
            assertReferenced(imported, cid, html);
            final Email copied = mail.emailBuilder().copying(imported).buildEmail();
            assertReferenced(copied, cid, html);
            final Email reimported = mail.converter().fromEml(mail.converter().fromEmail(copied).toEml())
                    .withFetchAttachmentData(eager).toEmail();
            assertReferenced(reimported, cid, html);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<style>div { background-image:url(cid:logo&#64;example.org) }</style>",
            "<img src=\"cid:Logo&#64;example.org\">",
            "<img src=\"cid:logo&#64;example.org.extra\">",
            "<img src=\"cid:logo&#37;GG@example.org\">",
            "<img src=\"cid:logo&amp;#64;example.org\">"
    })
    void unrelatedReferencesDoNotClaimAnImage(final String html) {
        final Email imported = mail.converter().fromEml(message("logo@example.org", html)).toEmail();
        assertThat(imported.getHTMLText()).isEqualTo(html);
        assertThat(imported.getEmbeddedImages()).isEmpty();
        assertThat(imported.getAttachments()).extracting(AttachmentResource::getContentId).containsExactly("logo@example.org");
        assertThat(imported.getAttachments().get(0).readAllBytes()).isEqualTo(IMAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<style>div { background-image:url(cid:logo%401@example.org) }</style>",
            "<div style=\"background-image:url(cid:logo%401@example.org)\"></div>"
    })
    void cssStillInterpretsCidUrlEscapes(final String html) {
        assertReferenced(mail.converter().fromEml(message("logo@1@example.org", html)).toEmail(), "logo@1@example.org", html);
    }

    @Test
    void encodedAttributeSpellingDoesNotClaimADifferentLiteralContentId() {
        final String cid = "logo&#64;example.org";
        final String html = "<img src=\"cid:logo&#64;example.org\">";
        final Email imported = mail.converter().fromEml(message(cid, html)).toEmail();
        assertThat(imported.getHTMLText()).isEqualTo(html);
        assertThat(imported.getEmbeddedImages()).isEmpty();
        assertThat(imported.getAttachments()).extracting(AttachmentResource::getContentId).containsExactly(cid);
    }

    private static void assertReferenced(final Email imported, final String cid, final String html) {
        assertThat(imported.getHTMLText()).isEqualTo(html);
        assertThat(imported.getEmbeddedImages()).hasSize(1);
        final AttachmentResource image = imported.getEmbeddedImages().get(0);
        assertThat(image.getName()).isEqualTo("logo.png");
        assertThat(image.getContentId()).isEqualTo(cid);
        assertThat(image.getDescription()).isEqualTo("Company logo");
        assertThat(image.readAllBytes()).isEqualTo(IMAGE);
        assertThat(imported.getAttachments()).isEmpty();
    }

    private Email convert(final ImportRoute route, final String eml) {
        switch (route) {
            case STATIC: return EmailConverter.emlToEmail(eml);
            case FLUENT_EAGER: return mail.converter().fromEml(eml).withFetchAttachmentData(true).toEmail();
            case FLUENT_LAZY: return mail.converter().fromEml(eml).withFetchAttachmentData(false).toEmail();
            case EXACT: return mail.emailBuilder().startingFromExactEml(eml.getBytes(UTF_8)).withEnvelopeRecipients("reader@example.org").buildEmail();
            default: throw new AssertionError(route);
        }
    }

    private static String message(final String cid, final String html) {
        return "From: sender@example.org\r\nTo: reader@example.org\r\nSubject: HTML CID\r\n"
                + "Date: Wed, 28 Oct 2026 10:00:00 +0100\r\nMessage-ID: <html-cid@example.org>\r\n"
                + "MIME-Version: 1.0\r\nContent-Type: multipart/related; boundary=cid-parts\r\n\r\n"
                + "--cid-parts\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n" + html + "\r\n"
                + "--cid-parts\r\nContent-Type: image/png; name=logo.png\r\nContent-Disposition: inline; filename=logo.png\r\n"
                + "Content-ID: <" + cid + ">\r\nContent-Description: Company logo\r\nContent-Transfer-Encoding: base64\r\n\r\nAAECAw==\r\n"
                + "--cid-parts--\r\n";
    }

    private enum ImportRoute { STATIC, FLUENT_EAGER, FLUENT_LAZY, EXACT }
}
