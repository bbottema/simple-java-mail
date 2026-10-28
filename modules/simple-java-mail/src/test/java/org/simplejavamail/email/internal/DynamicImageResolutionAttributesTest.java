package org.simplejavamail.email.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.config.ImageResolutionConfig;
import testutil.ConfigLoaderTestHelper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicImageResolutionAttributesTest {

    private static final String REAL_IMAGE = "excellent.png";
    private static final String CLASSPATH_BASE = "/test-dynamicembedded-image";
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @TempDir
    Path images;

    enum Source { FILE, CLASSPATH, LOCAL_URL }

    @ParameterizedTest
    @MethodSource("attributesAndSources")
    void sourceTextInsideAnEarlierAttributeCannotSelectAMissingDecoy(final Source source, final String before,
            final String after) throws Exception {
        final byte[] content;
        try (InputStream input = getClass().getResourceAsStream(CLASSPATH_BASE + "/" + REAL_IMAGE)) {
            assertThat(input).isNotNull();
            content = input.readAllBytes();
        }
        Files.write(images.resolve(REAL_IMAGE), content);

        final Email email = mail.emailBuilder().startingBlank().withImageResolutionConfig(resolution(source))
                .withHTMLText(before + REAL_IMAGE + after).buildEmail();

        assertThat(email.getEmbeddedImages()).hasSize(1);
        final AttachmentResource resolved = email.getEmbeddedImages().get(0);
        assertThat(resolved.readAllBytes()).containsExactly(content);
        assertThat(email.getHTMLText()).isEqualTo(before + "cid:" + resolved.getName() + after);
    }

    @ParameterizedTest
    @MethodSource("quotedAttributes")
    void anExistingDecoyCannotReplaceTheRealImageOrChangeOtherAttributes(final String before,
            final String after) throws Exception {
        Files.write(images.resolve(REAL_IMAGE), new byte[] {1, 2, 3});
        Files.write(images.resolve("decoy.png"), new byte[] {9});

        final Email email = mail.emailBuilder().startingBlank().withImageResolutionConfig(resolution(Source.FILE))
                .withHTMLText(before + REAL_IMAGE + after).buildEmail();

        assertThat(email.getEmbeddedImages()).hasSize(1);
        final AttachmentResource resolved = email.getEmbeddedImages().get(0);
        assertThat(resolved.readAllBytes()).containsExactly((byte) 1, (byte) 2, (byte) 3);
        assertThat(email.getHTMLText()).isEqualTo(before + "cid:" + resolved.getName() + after);
    }

    @Test
    void aSourceBeforeOtherAttributesKeepsTheirContent() throws Exception {
        Files.write(images.resolve(REAL_IMAGE), new byte[] {1, 2, 3});
        final String after = "\" alt=\"example src='decoy.png' > caption\" data-preview='other.png'>";

        final Email email = mail.emailBuilder().startingBlank().withImageResolutionConfig(resolution(Source.FILE))
                .withHTMLText("<img src=\"" + REAL_IMAGE + after).buildEmail();

        assertThat(email.getEmbeddedImages()).hasSize(1);
        assertThat(email.getHTMLText()).isEqualTo("<img src=\"cid:" + email.getEmbeddedImages().get(0).getName() + after);
    }

    @Test
    void aMissingRealSourceLeavesTheDraftUnchangedDespiteAnExistingDecoy() throws Exception {
        Files.write(images.resolve("decoy.png"), new byte[] {9});
        final String html = "<img alt=\"example src='decoy.png'\" src=\"missing.png\">";
        final EmailPopulatingBuilder draft = mail.emailBuilder().startingBlank()
                .withImageResolutionConfig(resolution(Source.FILE))
                .withEmbeddedImage("manual", new byte[] {4}, "image/png").withHTMLText(html);
        final AttachmentResource supplied = draft.getEmbeddedImages().get(0);

        assertThatThrownBy(draft::buildEmail).isInstanceOf(MailException.class).hasMessageContaining("missing.png");
        assertThat(draft.getHTMLText()).isEqualTo(html);
        assertThat(draft.getEmbeddedImages()).containsExactly(supplied);
    }

    @Test
    void sourceTextWithoutAnActualSourceLeavesTheHtmlAndImagesAlone() throws Exception {
        Files.write(images.resolve("decoy.png"), new byte[] {9});
        final String html = "<img alt=\"example src='decoy.png'\" title='caption'>";

        final Email email = mail.emailBuilder().startingBlank().withImageResolutionConfig(resolution(Source.FILE))
                .withHTMLText(html).buildEmail();

        assertThat(email.getHTMLText()).isEqualTo(html);
        assertThat(email.getEmbeddedImages()).isEmpty();
    }

    private ImageResolutionConfig resolution(final Source source) throws Exception {
        final ImageResolutionConfig.Builder choices = ImageResolutionConfig.builder().embeddedImageAutoResolutionMustBeSuccesful(true);
        switch (source) {
            case FILE:
                return choices.withEmbeddedImageAutoResolutionForFiles(true).withEmbeddedImageBaseDir(images.toString()).build();
            case CLASSPATH:
                return choices.withEmbeddedImageAutoResolutionForClassPathResources(true).withEmbeddedImageBaseClassPath(CLASSPATH_BASE).build();
            case LOCAL_URL:
                return choices.withEmbeddedImageAutoResolutionForURLs(true).withEmbeddedImageBaseUrl(images.toUri().toURL()).build();
            default:
                throw new AssertionError(source);
        }
    }

    private static Stream<Arguments> attributesAndSources() {
        return Stream.of(Source.values()).flatMap(source -> Stream.of(
                Arguments.of(source, "<img alt=\"example src='decoy.png'\" src=\"", "\" title='caption'>"),
                Arguments.of(source, "<img alt='example src=\"decoy.png\"' src='", "' title=\"caption\">")));
    }

    private static Stream<Arguments> quotedAttributes() {
        return Stream.of(
                Arguments.of("<img alt=\"example src='decoy.png'\" src=\"", "\" title='caption'>"),
                Arguments.of("<img alt='example src=\"decoy.png\"' src='", "' title=\"caption\">"),
                Arguments.of("<img title=\"example > src='decoy.png'\" src=\"", "\">"),
                Arguments.of("<img title='example > src=\"decoy.png\"' src='", "'>"));
    }
}
