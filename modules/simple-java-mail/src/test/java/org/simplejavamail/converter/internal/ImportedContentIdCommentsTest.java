package org.simplejavamail.converter.internal;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.converter.EmailConverter;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Properties;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class ImportedContentIdCommentsTest {
    private static final String ID = "logo@example.org";
    private static final String NAME = "logo (draft).png";
    private static final String HTML = "<img src=\"cid:" + ID + "\">";
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @ParameterizedTest
    @MethodSource("commentedIds")
    void matchesReceivedIdentityAcrossPublicImportRoutes(final String contentId, final boolean explicitInline) throws Exception {
        final String eml = message(contentId, HTML, explicitInline ? "inline" : null);
        assertEmbedded(EmailConverter.emlToEmail(eml), ID);
        for (boolean eager : new boolean[] {false, true}) {
            assertEmbedded(mail.converter().fromEml(eml).withFetchAttachmentData(eager).toEmail(), ID);
            final MimeMessage source = mime(eml);
            assertEmbedded(EmailConverter.mimeMessageToEmail(source, null, eager), ID);
            assertEmbedded(mail.converter().fromMimeMessage(source).withFetchAttachmentData(eager).toEmail(), ID);
        }
    }

    private static Stream<Arguments> commentedIds() {
        return Stream.of(
                "<" + ID + ">",
                "<" + ID + "> (company logo)",
                "(company logo) <" + ID + ">",
                "(prefix (nested)) <" + ID + "> (tail (nested))",
                "(prefix <marker>) <" + ID + "> (tail > marker)",
                "(escaped \\(logo\\) \\\\) <" + ID + "> (tail)",
                "<" + ID + "> \t",
                "<" + ID + ">\r\n\t(company logo)",
                "(prefix)\r\n\t<" + ID + ">\r\n\t(tail)")
                .flatMap(value -> Stream.of(Arguments.of(value, false), Arguments.of(value, true)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesSnapshotsExportAndAuthoritativeBytes(final boolean eager) throws Exception {
        final String eml = message("(prefix) <" + ID + "> (company logo)", HTML, "inline");
        final Email imported = mail.converter().fromEml(eml).withFetchAttachmentData(eager).toEmail();
        assertEmbedded(mail.emailBuilder().copying(imported).buildEmail(), ID);
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(imported); }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertEmbedded((Email) input.readObject(), ID);
        }
        assertEmbedded(mail.converter().fromEml(mail.converter().fromEmail(imported).toEml()).toEmail(), ID);
        final byte[] original = eml.getBytes(UTF_8);
        final Email exact = mail.emailBuilder().startingFromExactEml(original)
                .withEnvelopeRecipients("reader@example.org").buildEmail();
        assertEmbedded(exact, ID);
        assertThat(mail.converter().fromEmail(exact).toEmlBytes()).containsExactly(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"<logo@example.org> (unclosed", "(unclosed <logo@example.org>",
            "<logo@example.org> <other@example.org>", "<logo@example.org> extra", "<<logo@example.org>>",
            "<logo@example.org> )", "<logo@example.org> (valid) extra", "<logo@example.org (unclosed)>"})
    void doesNotGuessAReferenceFromMalformedOrCompetingHeaders(final String contentId) {
        for (boolean eager : new boolean[] {false, true}) {
            final Email imported = mail.converter().fromEml(message(contentId, HTML, "inline"))
                    .withFetchAttachmentData(eager).toEmail();
            assertThat(imported.getEmbeddedImages()).isEmpty();
            assertThat(imported.getAttachments()).singleElement().satisfies(resource -> {
                assertThat(resource.getName()).isEqualTo(NAME);
                assertThat(resource.readAllBytes()).isEqualTo("image".getBytes(UTF_8));
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"LOGO@example.org", "logo@example.org.other"})
    void keepsTheCompleteCaseSensitiveIdentity(final String otherId) {
        final Email imported = mail.converter().fromEml(message("<" + otherId + "> (company logo)", HTML, null)).toEmail();
        assertThat(imported.getEmbeddedImages()).isEmpty();
        assertThat(imported.getAttachments()).singleElement()
                .extracting(AttachmentResource::getContentId).isEqualTo(otherId);
    }

    @Test
    void retainsExplicitlyAttachedReferencedImageViews() {
        final Email imported = mail.converter().fromEml(message("<" + ID + "> (company logo)", HTML, "attachment")).toEmail();
        assertThat(imported.getEmbeddedImages()).singleElement().satisfies(resource -> assertResource(resource, ID));
        assertThat(imported.getAttachments()).singleElement().satisfies(resource -> assertResource(resource, ID));
    }

    @Test
    void retainsLegacyUnbracketedIdentity() {
        assertEmbedded(mail.converter().fromEml(message(ID, HTML, null)).toEmail(), ID);
    }

    @ParameterizedTest
    @MethodSource("opaqueIds")
    void retainsOpaqueAndQuotedCoreSpelling(final String id, final String cidUrl) {
        final String html = "<img src=\"" + cidUrl + "\">";
        for (final String header : new String[] {"<" + id + ">", "(prefix) <" + id + "> (tail)"}) {
            final Email imported = mail.converter().fromEml(message(header, html, "inline")).toEmail();
            assertThat(imported.getHTMLText()).isEqualTo(html);
            assertThat(imported.getAttachments()).isEmpty();
            assertThat(imported.getEmbeddedImages()).singleElement().satisfies(resource -> assertResource(resource, id));
        }
    }

    private static Stream<Arguments> opaqueIds() {
        return Stream.of(
                Arguments.of("thumbsup", "cid:thumbsup"),
                Arguments.of("logo@[127.0.0.1]", "cid:logo@%5B127.0.0.1%5D"),
                Arguments.of("logo@[lo(go)]", "cid:logo@%5Blo(go)%5D"),
                Arguments.of("logo@[lo\"go]", "cid:logo@%5Blo%22go%5D"),
                Arguments.of("logo@[lo<go]", "cid:logo@%5Blo%3Cgo%5D"),
                Arguments.of("\"lo(go)\"@example.org", "cid:%22lo(go)%22@example.org"),
                Arguments.of("\"lo<go\"@example.org", "cid:%22lo%3Cgo%22@example.org"),
                Arguments.of("\"lo go\"@example.org", "cid:%22lo%20go%22@example.org"));
    }

    @ParameterizedTest
    @MethodSource("malformedCoreIds")
    void doesNotNormalizeMalformedCoreIntoANewReference(final String header, final String cidUrl) {
        final Email imported = mail.converter().fromEml(message(header, "<img src=\"" + cidUrl + "\">", "inline")).toEmail();
        assertThat(imported.getEmbeddedImages()).isEmpty();
        assertThat(imported.getAttachments()).hasSize(1);
    }

    private static Stream<Arguments> malformedCoreIds() {
        return Stream.of(
                Arguments.of("<logo @example.org> (tail)", "cid:logo%20@example.org"),
                Arguments.of("<logo@[127.0.0.1> (tail)", "cid:logo@%5B127.0.0.1"),
                Arguments.of("<\"logo@example.org> (tail)", "cid:%22logo@example.org"),
                Arguments.of("<logo@[[127.0.0.1]]> (tail)", "cid:logo@%5B%5B127.0.0.1%5D%5D"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesExistingOccurrenceRulesWhenCommentsDescribeTheSameId(final boolean referenced) {
        final String html = referenced ? HTML : "<p>No image references</p>";
        final String first = message("<" + ID + "> (first)", html, "inline");
        final String eml = first.replace("--b--\r\n", "--b\r\nContent-Type: image/png; name=\"second.png\"\r\n"
                + "Content-Disposition: inline; filename=\"second.png\"\r\nContent-ID: (second) <" + ID + ">\r\n"
                + "Content-Transfer-Encoding: base64\r\n\r\nc2Vjb25k\r\n--b--\r\n");
        final Email imported = mail.converter().fromEml(eml).toEmail();
        if (referenced) {
            assertThat(imported.getAttachments()).isEmpty();
            assertThat(imported.getEmbeddedImages()).singleElement().satisfies(resource -> {
                assertThat(resource.getName()).isEqualTo("second.png");
                assertThat(resource.getContentId()).isEqualTo(ID);
                assertThat(resource.readAllBytes()).isEqualTo("second".getBytes(UTF_8));
            });
        } else {
            assertThat(imported.getEmbeddedImages()).isEmpty();
            assertThat(imported.getAttachments()).extracting(AttachmentResource::getContentId).containsExactly(ID, ID);
            assertThat(imported.getAttachments()).extracting(AttachmentResource::getName).containsExactly(NAME, "second.png");
            assertThat(imported.getAttachments().get(0).readAllBytes()).isEqualTo("image".getBytes(UTF_8));
            assertThat(imported.getAttachments().get(1).readAllBytes()).isEqualTo("second".getBytes(UTF_8));
        }
    }

    private static MimeMessage mime(final String eml) throws Exception {
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(eml.getBytes(UTF_8)));
    }

    private static void assertEmbedded(final Email email, final String id) {
        assertThat(email.getHTMLText()).isEqualTo(HTML);
        assertThat(email.getAttachments()).isEmpty();
        assertThat(email.getEmbeddedImages()).singleElement().satisfies(resource -> assertResource(resource, id));
    }

    private static void assertResource(final AttachmentResource resource, final String id) {
        assertThat(resource.getName()).isEqualTo(NAME);
        assertThat(resource.getContentId()).isEqualTo(id);
        assertThat(resource.readAllBytes()).isEqualTo("image".getBytes(UTF_8));
    }

    private static String message(final String contentId, final String html, final String disposition) {
        return "From: sender@example.org\r\nTo: reader@example.org\r\nMIME-Version: 1.0\r\n"
                + "Content-Type: multipart/related; boundary=b\r\n\r\n"
                + "--b\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n" + html + "\r\n"
                + "--b\r\nContent-Type: image/png; name=\"" + NAME + "\"\r\n"
                + (disposition == null ? "" : "Content-Disposition: " + disposition + "; filename=\"" + NAME + "\"\r\n")
                + "Content-ID: " + contentId + "\r\nContent-Transfer-Encoding: base64\r\n\r\naW1hZ2U=\r\n--b--\r\n";
    }
}
