package org.simplejavamail.email.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicImageResolutionQuotesTest {

    private static final String FILENAME = "it's.png";
    private static final String CLASSPATH_BASE = "/test-dynamicembedded-image";
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @TempDir
    Path images;

    enum Source { FILE, CLASSPATH, LOCAL_URL }

    @ParameterizedTest
    @EnumSource(Source.class)
    void anApostropheInsideDoubleQuotesResolvesTheCompleteImageLocation(final Source source) throws Exception {
        final byte[] content;
        try (InputStream input = getClass().getResourceAsStream(CLASSPATH_BASE + "/" + FILENAME)) {
            assertThat(input).isNotNull();
            content = input.readAllBytes();
        }
        Files.write(images.resolve(FILENAME), content);
        final Email email = mail.emailBuilder().startingBlank().withImageResolutionConfig(resolution(source, true))
                .withHTMLText(image(FILENAME)).buildEmail();

        assertThat(email.getEmbeddedImages()).hasSize(1);
        final AttachmentResource resolved = email.getEmbeddedImages().get(0);
        assertThat(resolved.readAllBytes()).containsExactly(content);
        assertThat(email.getHTMLText()).isEqualTo(image("cid:" + resolved.getName()));
    }

    @Test
    void aFileMatchingThePrefixCannotReplaceTheRequestedImage() throws Exception {
        Files.write(images.resolve("it"), new byte[] {9});
        Files.write(images.resolve(FILENAME), new byte[] {1, 2, 3});
        final Email email = mail.emailBuilder().startingBlank().withImageResolutionConfig(resolution(Source.FILE, true))
                .withHTMLText(image(FILENAME)).buildEmail();

        assertThat(email.getEmbeddedImages()).hasSize(1);
        final AttachmentResource resolved = email.getEmbeddedImages().get(0);
        assertThat(resolved.readAllBytes()).containsExactly((byte) 1, (byte) 2, (byte) 3);
        assertThat(email.getHTMLText()).isEqualTo(image("cid:" + resolved.getName()));
    }

    @Test
    void repeatedFullLocationsShareOneImageAndExistingCidReferencesStayIntact() throws Exception {
        Files.write(images.resolve(FILENAME), new byte[] {1, 2, 3});
        final String untouched = "<img src='cid:manual'><img src='missing.png'>";
        final EmailPopulatingBuilder draft = mail.emailBuilder().startingBlank()
                .withImageResolutionConfig(resolution(Source.FILE, false))
                .withEmbeddedImage("manual", new byte[] {4}, "image/png")
                .withHTMLText(image(FILENAME) + untouched + image(FILENAME));
        final Email email = draft.buildEmail();

        assertThat(email.getEmbeddedImages()).hasSize(2);
        final String reference = image("cid:" + email.getEmbeddedImages().get(1).getName());
        assertThat(email.getHTMLText()).isEqualTo(reference + untouched + reference);
        assertThat(draft.buildEmail().getHTMLText()).isEqualTo(email.getHTMLText());
        assertThat(draft.getEmbeddedImages()).containsExactlyElementsOf(email.getEmbeddedImages());
    }

    @Test
    void requiredMissingLocationsKeepTheWholeNameAndLeaveTheDraftUnchanged() throws Exception {
        Files.write(images.resolve(FILENAME), new byte[] {1, 2, 3});
        final String html = image(FILENAME) + image("missing's.png");
        final EmailPopulatingBuilder draft = mail.emailBuilder().startingBlank()
                .withImageResolutionConfig(resolution(Source.FILE, true)).withHTMLText(html);

        assertThatThrownBy(draft::buildEmail).isInstanceOf(MailException.class).hasMessageContaining("missing's.png");
        assertThat(draft.getHTMLText()).isEqualTo(html);
        assertThat(draft.getEmbeddedImages()).isEmpty();

        final Email recovered = draft.withHTMLText(image(FILENAME)).buildEmail();
        assertThat(recovered.getEmbeddedImages()).hasSize(1);
        assertThat(recovered.getHTMLText()).isEqualTo(image("cid:" + recovered.getEmbeddedImages().get(0).getName()));
    }

    @Test
    void anOptionalMissingLocationRetainsItsCompleteOriginalHtml() throws Exception {
        final String html = image("missing's.png");
        final Email email = mail.emailBuilder().startingBlank().withImageResolutionConfig(resolution(Source.FILE, false))
                .withHTMLText(html).buildEmail();

        assertThat(email.getHTMLText()).isEqualTo(html);
        assertThat(email.getEmbeddedImages()).isEmpty();
    }

    @Test
    void oppositeQuotesDoNotBypassTheConfiguredBase() throws Exception {
        final Path containedBase = Files.createDirectory(images.resolve("base"));
        Files.write(images.resolve(FILENAME), new byte[] {1, 2, 3});
        final String html = image("../" + FILENAME);
        final EmailPopulatingBuilder draft = mail.emailBuilder().startingBlank()
                .withImageResolutionConfig(ImageResolutionConfig.builder().withEmbeddedImageAutoResolutionForFiles(true)
                        .withEmbeddedImageBaseDir(containedBase.toString()).embeddedImageAutoResolutionMustBeSuccesful(true).build())
                .withHTMLText(html);

        assertThatThrownBy(draft::buildEmail).isInstanceOf(MailException.class).hasMessageContaining("../" + FILENAME);
        assertThat(draft.getHTMLText()).isEqualTo(html);
        assertThat(draft.getEmbeddedImages()).isEmpty();
    }

    private ImageResolutionConfig resolution(final Source source, final boolean required) throws Exception {
        final ImageResolutionConfig.Builder choices = ImageResolutionConfig.builder().embeddedImageAutoResolutionMustBeSuccesful(required);
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

    private static String image(final String source) {
        return "<img src=\"" + source + "\">";
    }
}
