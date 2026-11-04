package org.simplejavamail.converter;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailSourceContent;
import org.simplejavamail.api.email.EmailSourceData;
import org.simplejavamail.api.email.EmailSourcePart;
import org.simplejavamail.api.email.config.ImageResolutionConfig;
import org.simplejavamail.config.ConfigLoader;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlSourceRetentionTest {
    private static final String RECEIVED_HTML = "<html><body><p>Received é</p><img src=\"picture.png\" alt=\"source\"></body></html>";
    private static final byte[] IMAGE = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jV9sAAAAASUVORK5CYII=");
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    enum Route { EML, READER_TEXT, READER_STREAM }
    enum Edit { REPLACE, APPEND, PREPEND, CLEAR }

    @TempDir Path directory;

    @ParameterizedTest
    @EnumSource(Route.class)
    void firstImportBuildRetainsTheReceivedHtmlOnlyWhenFileResolutionRewritesIt(final Route route) throws Exception {
        final SimpleJavaMail mail = fileResolvingFactory();
        final AtomicInteger opens = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();

        // The image does not exist while the import builder is produced. Capturing source context must not resolve it early.
        final EmailPopulatingBuilder draft = draft(route, mail, RECEIVED_HTML, opens, closes);
        assertThat(draft.getHTMLText()).isEqualTo(RECEIVED_HTML);
        assertThat(draft.getEmbeddedImages()).isEmpty();
        assertThat(Files.exists(directory.resolve("picture.png"))).isFalse();
        Files.write(directory.resolve("picture.png"), IMAGE);

        final Email email = draft.buildEmail();
        assertThat(email.getHTMLText()).contains("src=\"cid:").doesNotContain("src=\"picture.png\"");
        assertThat(email.getEmbeddedImages()).singleElement().satisfies(image -> assertThat(image.readAllBytes()).containsExactly(IMAGE));
        assertThat(email.getProblems()).isEmpty();
        final EmailSourcePart original = retainedHtml(email.getSourceData());
        if (route == Route.READER_STREAM) {
            assertThat(original.getText()).isNull();
            assertThat(original.getContent()).isNotNull();
            assertThat(original.getContent().getRepresentation()).isEqualTo(EmailSourceContent.Representation.READER_BYTES);
            assertThat(original.getContent().isFullyCaptured()).isTrue();
            assertThat(original.getContent().readAllBytes()).containsExactly(RECEIVED_HTML.getBytes(StandardCharsets.UTF_8));
            assertThat(opens.get()).isEqualTo(1);
            assertThat(closes.get()).isEqualTo(1);
        } else {
            assertRetainedHtml(original, RECEIVED_HTML);
        }
        assertThat(original.getOrigin()).isSameAs(email.getSourceData().getOrigin());
    }

    @ParameterizedTest
    @EnumSource(Edit.class)
    void explicitPreBuildEditsAreNotRecordedAsReceivedHtml(final Edit edit) throws Exception {
        final EmailPopulatingBuilder draft = fileResolvingFactory().converter().fromEml(eml(RECEIVED_HTML)).toEmailBuilder();
        switch (edit) {
            case REPLACE: draft.withHTMLText("<p>Application replacement</p><img src=\"picture.png\">"); break;
            case APPEND: draft.appendHTMLText("<p>Application suffix</p>"); break;
            case PREPEND: draft.prependHTMLText("<p>Application prefix</p>"); break;
            case CLEAR: draft.clearHTMLText(); break;
            default: throw new AssertionError(edit);
        }
        Files.write(directory.resolve("picture.png"), IMAGE);

        final Email edited = draft.buildEmail();
        assertThat(parts(edited.getSourceData())).allSatisfy(part -> {
            assertThat(part.getText()).isNull();
            assertThat(part.getContent()).isNull();
        });
        assertThat(edited.getProblems()).isEmpty();
        if (edit == Edit.CLEAR) {
            assertThat(edited.getHTMLText()).isNull();
            assertThat(edited.getEmbeddedImages()).isEmpty();
        } else {
            assertThat(edited.getHTMLText()).contains("Application", "src=\"cid:");
        }
    }

    @Test
    void copyingAndLaterSettersLeaveAnAlreadyCapturedOriginalUnchanged() throws Exception {
        final SimpleJavaMail mail = fileResolvingFactory();
        Files.write(directory.resolve("picture.png"), IMAGE);
        final Email received = mail.converter().fromEml(eml(RECEIVED_HTML)).toEmail();
        final EmailSourcePart original = retainedHtml(received.getSourceData());

        final Email edited = mail.emailBuilder().copying(received).withHTMLText("<p>Application replacement</p>")
                .clearEmbeddedImages().buildEmail();
        assertThat(edited.getHTMLText()).isEqualTo("<p>Application replacement</p>");
        assertThat(edited.getSourceData()).isSameAs(received.getSourceData());
        assertThat(retainedHtml(edited.getSourceData())).isSameAs(original);
        assertRetainedHtml(original, RECEIVED_HTML);
        assertThat(edited.getProblems()).isEmpty();
    }

    @Test
    void healthyUnchangedHtmlDoesNotRetainAnAdditionalReaderPayload() {
        final String original = "<html><body><p>Unchanged received HTML é</p></body></html>";
        final Email email = fileResolvingFactory().converter().fromEml(eml(original)).toEmail();
        assertThat(email.getHTMLText()).isEqualTo(original);
        assertThat(email.getProblems()).isEmpty();
        assertThat(parts(email.getSourceData())).isNotEmpty().allSatisfy(part -> {
            assertThat(part.getText()).isNull();
            assertThat(part.getContent()).isNull();
        });
    }

    @Test
    void anImportCandidateIsReleasedAfterItsFirstBuildRatherThanBecomingALaterAuditCapture() throws Exception {
        final EmailPopulatingBuilder draft = MAIL.converter().fromEml(eml(RECEIVED_HTML)).toEmailBuilder();
        final Email first = draft.buildEmail();
        assertThat(first.getHTMLText()).isEqualTo(RECEIVED_HTML);
        assertThat(parts(first.getSourceData())).allSatisfy(part -> {
            assertThat(part.getText()).isNull();
            assertThat(part.getContent()).isNull();
        });

        Files.write(directory.resolve("picture.png"), IMAGE);
        final Email later = draft.withImageResolutionConfig(ImageResolutionConfig.builder()
                .withEmbeddedImageAutoResolutionForFiles(true).withEmbeddedImageBaseDir(directory.toString())
                .embeddedImageAutoResolutionMustBeSuccesful(true).build()).buildEmail();
        assertThat(later.getHTMLText()).contains("src=\"cid:");
        assertThat(later.getSourceData()).isSameAs(first.getSourceData());
        assertThat(parts(later.getSourceData())).allSatisfy(part -> {
            assertThat(part.getText()).isNull();
            assertThat(part.getContent()).isNull();
        });
    }

    private SimpleJavaMail fileResolvingFactory() {
        return SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.embeddedimages.dynamicresolution.enable.dir", "true",
                "simplejavamail.embeddedimages.dynamicresolution.enable.classpath", "false",
                "simplejavamail.embeddedimages.dynamicresolution.enable.url", "false",
                "simplejavamail.embeddedimages.dynamicresolution.base.dir", directory.toString(),
                "simplejavamail.embeddedimages.dynamicresolution.mustbesuccesful", "true")).load());
    }

    private static EmailPopulatingBuilder draft(final Route route, final SimpleJavaMail mail, final String html,
            final AtomicInteger opens, final AtomicInteger closes) throws Exception {
        final byte[] eml = eml(html);
        if (route == Route.EML) return mail.converter().fromEml(eml).toEmailBuilder();
        final MimeMessage reader = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(eml)) {
            @Override public Object getContent() {
                if (route == Route.READER_TEXT) return html;
                opens.incrementAndGet();
                return new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)) {
                    @Override public void close() throws IOException {
                        closes.incrementAndGet();
                        super.close();
                    }
                };
            }
        };
        return mail.converter().fromMimeMessage(reader).toEmailBuilder();
    }

    private static byte[] eml(final String html) {
        return ("Message-ID: <html-source@example.org>\r\nFrom: sender@example.org\r\nTo: reader@example.org\r\n"
                + "Subject: Original HTML\r\nMIME-Version: 1.0\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n" + html)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void assertRetainedHtml(final EmailSourcePart original, final String expected) {
        if (original.getText() != null) {
            assertThat(original.getText()).isEqualTo(expected);
        } else {
            assertThat(original.getContent()).isNotNull();
            assertThat(original.getContent().readAllBytes()).containsExactly(expected.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static EmailSourcePart retainedHtml(final EmailSourceData source) {
        final List<EmailSourcePart> retained = parts(source).stream()
                .filter(part -> part.getText() != null || part.getContent() != null)
                .collect(java.util.stream.Collectors.toList());
        assertThat(retained).as("One received HTML body, independent of Problems").hasSize(1);
        return retained.get(0);
    }

    private static List<EmailSourcePart> parts(final EmailSourceData source) {
        final List<EmailSourcePart> result = new ArrayList<>();
        for (EmailSourcePart part : source.getParts()) collect(part, result);
        for (EmailSourceData derived : source.getDerivedSources()) result.addAll(parts(derived));
        return result;
    }

    private static void collect(final EmailSourcePart part, final List<EmailSourcePart> result) {
        result.add(part);
        for (EmailSourcePart child : part.getChildren()) collect(child, result);
    }
}
