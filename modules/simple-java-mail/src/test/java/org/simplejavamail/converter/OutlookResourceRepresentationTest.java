package org.simplejavamail.converter;

import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import testutil.ConfigLoaderTestHelper;
import testutil.ResourceRepresentationCase;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class OutlookResourceRepresentationTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    private static final String CID = "logo@example.org";
    private static final byte[] IMAGE = {0, 1, 2, 3};
    private static final byte[] DOCUMENT = {4, 5, 6};

    @ParameterizedTest
    @EnumSource(ResourceRepresentationCase.class)
    void msgNamesStayLiteralAndDistinctFromTheContentIdAcrossImportCopyAndMimeExport(final ResourceRepresentationCase representation)
            throws Exception {
        final byte[] msg = source(representation.getFilename());
        final Email fluent = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).toEmail();
        final Email legacy = EmailConverter.outlookMsgToEmail(new ByteArrayInputStream(msg));
        for (final Email email : Arrays.asList(fluent, legacy)) {
            assertResources(email, representation.getFilename());
            final Email copy = MAIL.emailBuilder().copying(email).buildEmail();
            assertResources(copy, representation.getFilename());
            assertThat(copy.getEmbeddedImages().get(0).getDataSource()).isSameAs(email.getEmbeddedImages().get(0).getDataSource());
            assertResources(MAIL.converter().fromEml(MAIL.converter().fromEmail(copy).toEmlBytes()).toEmail(), representation.getFilename());
            final Email reply = MAIL.emailBuilder().replyingTo(copy).buildEmail();
            assertThat(reply.getAttachments()).isEmpty();
            assertThat(reply.getEmbeddedImages()).hasSize(1);
            assertResource(reply.getEmbeddedImages().get(0), representation.getFilename(), CID, IMAGE);
        }
    }

    @ParameterizedTest
    @EnumSource(ResourceRepresentationCase.class)
    void msgHtmlUsesTheSameCidIdentityWhileFilenamesStayLiteral(final ResourceRepresentationCase representation) throws Exception {
        final String html = "<p>Resource identities</p><img src=\"" + representation.getHtmlCidReference() + "\">";
        final String otherFilename = "unrelated-" + representation.getFilename();
        final byte[] msg = source(representation.getFilename(), representation.getContentId(), html,
                otherFilename, representation.getDifferentContentId());
        for (final Email email : Arrays.asList(
                MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).toEmail(),
                EmailConverter.outlookMsgToEmail(new ByteArrayInputStream(msg)))) {
            assertSelectedResource(email, html, representation.getFilename(), representation.getContentId(), otherFilename);
            final Email copy = MAIL.emailBuilder().copying(email).buildEmail();
            assertSelectedResource(copy, html, representation.getFilename(), representation.getContentId(), otherFilename);
            assertSelectedResource(MAIL.converter().fromEml(MAIL.converter().fromEmail(copy).toEmlBytes()).toEmail(), html,
                    representation.getFilename(), representation.getContentId(), otherFilename);
            final Email reply = MAIL.emailBuilder().replyingTo(copy).buildEmail();
            assertThat(reply.getAttachments()).isEmpty();
            assertThat(reply.getEmbeddedImages()).hasSize(1);
            assertResource(reply.getEmbeddedImages().get(0), representation.getFilename(), representation.getContentId(), IMAGE);
        }
    }

    @ParameterizedTest
    @MethodSource("cidIdentityCases")
    void msgCidRecognitionUsesTheWholeCaseSensitiveIdentityAndDecodesPercentOnce(final String contentId,
            final String reference, final String differentContentId) throws Exception {
        final String html = "<img src=\"" + reference + "\">";
        final byte[] msg = source("selected.png", contentId, html, "different.png", differentContentId);
        final Email email = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).toEmail();
        assertSelectedResource(email, html, "selected.png", contentId, "different.png");
    }

    static Stream<Arguments> cidIdentityCases() {
        return Stream.of(
                Arguments.of("logo@example.org.extra", "cid:logo@example.org.extra", "logo@example.org"),
                Arguments.of("Logo@example.org", "CID:Logo@example.org", "logo@example.org"),
                Arguments.of("logo+1@example.org", "cId:logo+1@example.org", "logo 1@example.org"),
                Arguments.of("logo%2F1@example.org", "cid:logo%252F1@example.org", "logo/1@example.org"),
                Arguments.of("logo&tag@example.org", "c&#105;d:logo&amp;tag@example.org", "logo&amp;tag@example.org"));
    }

    @ParameterizedTest
    @MethodSource("cidFallbackCases")
    void msgCidCandidatesRetainExplicitThenShortThenLongFilenamePriority(final String html, final String expectedCid) throws Exception {
        final byte[] msg = source("long-name.png", "explicit@example.org", html, "ordinary.bin", null);
        final Email email = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).toEmail();
        assertSelectedResource(email, html, "long-name.png", expectedCid, "ordinary.bin");
    }

    static Stream<Arguments> cidFallbackCases() {
        return Stream.of(
                Arguments.of("<img src=\"cid:explicit@example.org\"><img src=\"cid:short-name.png\"><img src=\"cid:long-name.png\">",
                        "explicit@example.org"),
                Arguments.of("<img src=\"cid:short-name.png\"><img src=\"cid:long-name.png\">", "short-name.png"),
                Arguments.of("<img src=\"cid:long-name.png\">", "long-name.png"));
    }

    @Test
    void msgEmptyAttachmentIsNotPromotedToAnInlineImage() throws Exception {
        final byte[] msg = source("empty.png", "empty@example.org", "<img src=\"cid:empty&commat;example.org\">",
                "ordinary.bin", null, new byte[0]);
        final Email email = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).toEmail();
        assertThat(email.getEmbeddedImages()).isEmpty();
        assertThat(email.getAttachments()).hasSize(2).anySatisfy(resource -> {
            assertThat(resource.getName()).isEqualTo("empty.png");
            assertThat(resource.readAllBytes()).isEmpty();
        });
    }

    @Test
    void collidingNativeCidOccurrencesRemainAvailableWithoutChoosingAnImageByOrder() throws Exception {
        final byte[] msg = source("first.png", CID, "<img src=\"cid:" + CID + "\">", "second.png", CID);
        final Email email = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).toEmail();
        assertThat(email.getEmbeddedImages()).isEmpty();
        assertThat(email.getAttachments()).extracting(AttachmentResource::getName).containsExactly("first.png", "second.png");
        assertThat(email.getAttachments().get(0).readAllBytes()).isEqualTo(IMAGE);
        assertThat(email.getAttachments().get(1).readAllBytes()).isEqualTo(DOCUMENT);
        assertThat(email.getProblems()).anySatisfy(problem -> {
            assertThat(problem.getSourceValue()).isEqualTo(CID);
            assertThat(problem.getSources()).isNotEmpty();
        });
        final Email reconstructed = MAIL.converter().fromEml(MAIL.converter().fromEmail(email).toEmlBytes()).toEmail();
        assertThat(reconstructed.getEmbeddedImages()).isEmpty();
        assertThat(reconstructed.getAttachments()).hasSize(2).allSatisfy(resource -> assertThat(resource.getContentId()).isNull());
        assertThat(email.getInspection().getOriginalMessage().readAllBytes()).isEqualTo(msg);
    }

    private static void assertSelectedResource(final Email email, final String html, final String filename,
            final String contentId, final String otherFilename) {
        assertThat(email.getHTMLText()).isEqualTo(html);
        assertThat(email.getEmbeddedImages()).hasSize(1);
        assertResource(email.getEmbeddedImages().get(0), filename, contentId, IMAGE);
        assertThat(email.getAttachments()).hasSize(1);
        assertResource(email.getAttachments().get(0), otherFilename, null, DOCUMENT);
    }

    private static void assertResources(final Email email, final String filename) {
        assertThat(email.getHTMLText()).contains("<img src=\"cid:" + CID + "\">");
        assertThat(email.getEmbeddedImages()).hasSize(1);
        assertResource(email.getEmbeddedImages().get(0), filename, CID, IMAGE);
        assertThat(email.getAttachments()).hasSize(1);
        assertResource(email.getAttachments().get(0), filename, null, DOCUMENT);
    }

    private static void assertResource(final AttachmentResource resource, final String name, final String cid, final byte[] bytes) {
        assertThat(resource.getName()).isEqualTo(name);
        assertThat(resource.getContentId()).isEqualTo(cid);
        assertThat(resource.readAllBytes()).containsExactly(bytes);
    }

    private static byte[] source(final String filename) throws Exception {
        return source(filename, CID, "<p>Resource names</p><img src=\"cid:" + CID + "\">", filename, null);
    }

    private static byte[] source(final String filename, final String contentId, final String html,
            final String otherFilename, final String otherContentId) throws Exception {
        return source(filename, contentId, html, otherFilename, otherContentId, IMAGE);
    }

    private static byte[] source(final String filename, final String contentId, final String html,
            final String otherFilename, final String otherContentId, final byte[] imageBytes) throws Exception {
        try (InputStream input = OutlookResourceRepresentationTest.class.getResourceAsStream("/test-messages/simple email with TO and CC.msg");
                POIFSFileSystem file = new POIFSFileSystem(input)) {
            final DirectoryEntry root = file.getRoot();
            for (final String name : Arrays.asList("__substg1.0_1013001F", "__substg1.0_1013001E", "__substg1.0_10130102")) {
                if (root.hasEntry(name)) root.getEntry(name).delete();
            }
            stringProperty(root, "1013", html);
            final DirectoryEntry image = root.createDirectory("__attach_version1.0_#00000000");
            image.createDocument("__substg1.0_37010102", new ByteArrayInputStream(imageBytes));
            stringProperty(image, "370E", "image/png");
            stringProperty(image, "3704", "short-name.png");
            stringProperty(image, "3707", filename);
            stringProperty(image, "3712", contentId);
            final DirectoryEntry attachment = root.createDirectory("__attach_version1.0_#00000001");
            attachment.createDocument("__substg1.0_37010102", new ByteArrayInputStream(DOCUMENT));
            stringProperty(attachment, "370E", "application/octet-stream");
            stringProperty(attachment, "3704", otherFilename);
            if (otherContentId != null) stringProperty(attachment, "3712", otherContentId);
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            file.writeFilesystem(output);
            return output.toByteArray();
        }
    }

    private static void stringProperty(final DirectoryEntry entry, final String property, final String value) throws Exception {
        entry.createDocument("__substg1.0_" + property + "001F",
                new ByteArrayInputStream((value + '\0').getBytes(StandardCharsets.UTF_16LE)));
    }
}
