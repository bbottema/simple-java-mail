package org.simplejavamail.email.internal;

import jakarta.activation.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.config.ImageResolutionConfig;
import org.simplejavamail.config.ConfigLoader;
import testutil.ConfigLoaderTestHelper;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DynamicImageResolutionAtomicityTest {
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @TempDir
    Path images;

    enum Source { FILE, CLASSPATH, LOCAL_URL }

    @ParameterizedTest
    @EnumSource(Source.class)
    void failedPassAndUnchangedRetryKeepTheDraftThenCorrectedHtmlRecovers(final Source source) throws Exception {
        final String location = source == Source.CLASSPATH ? "/log4j2.xml" : "logo.png";
        Files.write(images.resolve("logo.png"), new byte[] {1, 2, 3});
        final String originalHtml = image(location) + image("missing-image-858.png");
        final EmailPopulatingBuilder draft = mail.emailBuilder().startingBlank()
                .withImageResolutionConfig(resolution(source, true))
                .withHTMLText(originalHtml);

        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(draft::buildEmail).isInstanceOf(MailException.class)
                    .hasMessageContaining("missing-image-858.png");
            assertThat(draft.getHTMLText()).isEqualTo(originalHtml);
            assertThat(draft.getEmbeddedImages()).isEmpty();
        }

        final Email recovered = draft.withHTMLText(image(location)).buildEmail();
        assertThat(recovered.getEmbeddedImages()).hasSize(1);
        assertThat(recovered.getHTMLText()).isEqualTo(image("cid:" + recovered.getEmbeddedImages().get(0).getName()));
        assertThat(draft.buildEmail().getHTMLText()).isEqualTo(recovered.getHTMLText());
        assertThat(draft.getEmbeddedImages()).containsExactlyElementsOf(recovered.getEmbeddedImages());
    }

    @Test
    void supplyingTheMissingFileRecoversInOrderWithoutChangingAnEarlierEmail() throws Exception {
        Files.write(images.resolve("logo.png"), new byte[] {1, 2, 3});
        final DataSource borrowed = mock(DataSource.class);
        final AttachmentResource explicit = AttachmentResource.builder().withName("manual.png").withContentId("manual")
                .withDescription("Supplied logo").withContentTransferEncoding(ContentTransferEncoding.BASE_64)
                .withDataSource(borrowed).build();
        final EmailPopulatingBuilder draft = mail.emailBuilder().startingBlank()
                .withImageResolutionConfig(resolution(Source.FILE, true))
                .withEmbeddedImage(explicit).withEmbeddedImage(explicit)
                .withHTMLText(image("cid:manual"));
        final Email earlier = draft.buildEmail();
        final String originalHtml = image("cid:manual") + image("logo.png") + image("missing-image-858.png");
        draft.withHTMLText(originalHtml);

        assertThatThrownBy(draft::buildEmail).isInstanceOf(MailException.class);
        assertThat(draft.getEmbeddedImages()).containsExactly(explicit, explicit);
        assertThat(draft.getHTMLText()).isEqualTo(originalHtml);

        Files.write(images.resolve("missing-image-858.png"), new byte[] {4, 5, 6});
        final Email recovered = draft.buildEmail();
        assertThat(recovered.getEmbeddedImages()).hasSize(4);
        assertThat(recovered.getEmbeddedImages().subList(0, 2)).containsExactly(explicit, explicit);
        assertThat(recovered.getEmbeddedImages().get(0).getDataSource()).isSameAs(borrowed);
        assertThat(recovered.getEmbeddedImages().get(0).getDescription()).isEqualTo("Supplied logo");
        assertThat(recovered.getEmbeddedImages().get(0).getContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BASE_64);
        assertThat(recovered.getEmbeddedImages().get(2).readAllBytes()).containsExactly((byte) 1, (byte) 2, (byte) 3);
        assertThat(recovered.getEmbeddedImages().get(3).readAllBytes()).containsExactly((byte) 4, (byte) 5, (byte) 6);
        assertThat(recovered.getHTMLText()).isEqualTo(image("cid:manual")
                + image("cid:" + recovered.getEmbeddedImages().get(2).getName())
                + image("cid:" + recovered.getEmbeddedImages().get(3).getName()));
        assertThat(earlier.getEmbeddedImages()).containsExactly(explicit, explicit);
        assertThat(earlier.getHTMLText()).isEqualTo(image("cid:manual"));
        verify(borrowed, never()).getInputStream();
    }

    @Test
    void repeatedLocationsShareOneGeneratedImageAndRepeatedSuccessfulBuildsKeepItsCid() throws Exception {
        Files.write(images.resolve("logo.png"), new byte[] {1, 2, 3});
        final EmailPopulatingBuilder draft = mail.emailBuilder().startingBlank()
                .withImageResolutionConfig(resolution(Source.FILE, true))
                .withHTMLText(image("logo.png") + image("logo.png"));

        final Email first = draft.buildEmail();
        final Email second = draft.buildEmail();
        assertThat(first.getEmbeddedImages()).hasSize(1);
        assertThat(first.getHTMLText()).isEqualTo(image("cid:" + first.getEmbeddedImages().get(0).getName())
                + image("cid:" + first.getEmbeddedImages().get(0).getName()));
        assertThat(second.getEmbeddedImages()).containsExactlyElementsOf(first.getEmbeddedImages());
        assertThat(second.getHTMLText()).isEqualTo(first.getHTMLText());
        Files.write(images.resolve("logo.png"), new byte[] {7, 8, 9});
        assertThat(first.getEmbeddedImages().get(0).readAllBytes()).containsExactly((byte) 7, (byte) 8, (byte) 9);
    }

    @Test
    void optionalResolutionStillKeepsResolvedImagesAndUnresolvedLocations() throws Exception {
        Files.write(images.resolve("logo.png"), new byte[] {1, 2, 3});
        final EmailPopulatingBuilder draft = mail.emailBuilder().startingBlank()
                .withImageResolutionConfig(resolution(Source.FILE, false))
                .withHTMLText(image("logo.png") + image("missing-image-858.png"));

        final Email first = draft.buildEmail();
        assertThat(first.getEmbeddedImages()).hasSize(1);
        assertThat(first.getHTMLText()).isEqualTo(image("cid:" + first.getEmbeddedImages().get(0).getName())
                + image("missing-image-858.png"));
        assertThat(draft.buildEmail().getEmbeddedImages()).containsExactlyElementsOf(first.getEmbeddedImages());
        assertThat(draft.getHTMLText()).isEqualTo(first.getHTMLText());
    }

    @Test
    void fileResolutionStillWinsBeforeClasspathAndUrlResolution() throws Exception {
        Files.write(images.resolve("log4j2.xml"), new byte[] {1, 2, 3});
        final Email email = mail.emailBuilder().startingBlank()
                .withImageResolutionConfig(resolution(Source.FILE, true).toBuilder()
                        .withEmbeddedImageAutoResolutionForClassPathResources(true)
                        .withEmbeddedImageAutoResolutionForURLs(true).withEmbeddedImageBaseUrl(unreachableUrl()).build())
                .withHTMLText(image("log4j2.xml")).buildEmail();

        assertThat(email.getEmbeddedImages()).hasSize(1);
        assertThat(email.getEmbeddedImages().get(0).readAllBytes()).containsExactly((byte) 1, (byte) 2, (byte) 3);
    }

    @Test
    void lockedChoicesStillFailBeforeAnyResolution() throws Exception {
        final SimpleJavaMail locked = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.locked.embeddedimages.dynamicresolution.enable.url", "false")).load());
        final String originalHtml = image("logo.png");
        final EmailPopulatingBuilder draft = locked.emailBuilder().startingBlank()
                .withImageResolutionConfig(ImageResolutionConfig.builder().withEmbeddedImageAutoResolutionForURLs(true)
                        .withEmbeddedImageBaseUrl(unreachableUrl()).embeddedImageAutoResolutionMustBeSuccesful(true).build())
                .withHTMLText(originalHtml);

        assertThatThrownBy(draft::buildEmail).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("locked");
        assertThat(draft.getHTMLText()).isEqualTo(originalHtml);
        assertThat(draft.getEmbeddedImages()).isEmpty();
    }

    private ImageResolutionConfig resolution(final Source source, final boolean required) throws IOException {
        final ImageResolutionConfig.Builder choices = ImageResolutionConfig.builder().embeddedImageAutoResolutionMustBeSuccesful(required);
        switch (source) {
            case FILE:
                return choices.withEmbeddedImageAutoResolutionForFiles(true).withEmbeddedImageBaseDir(images.toString()).build();
            case CLASSPATH:
                return choices.withEmbeddedImageAutoResolutionForClassPathResources(true).build();
            case LOCAL_URL:
                return choices.withEmbeddedImageAutoResolutionForURLs(true).withEmbeddedImageBaseUrl(images.toUri().toURL()).build();
            default:
                throw new AssertionError(source);
        }
    }

    private static String image(final String source) {
        return "<img src='" + source + "'>";
    }

    private static URL unreachableUrl() throws IOException {
        return new URL(null, "https://example.invalid/", new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(final URL url) {
                throw new AssertionError("URL resolution must not be reached");
            }
        });
    }
}
