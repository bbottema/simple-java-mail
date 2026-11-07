package org.simplejavamail.email.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.config.ImageResolutionConfig;
import org.simplejavamail.config.ConfigLoader;
import testutil.ConfigLoaderTestHelper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicImageMalformedUrlTest {
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @TempDir
    Path images;

    @ParameterizedTest
    @ValueSource(strings = {"http://[", "http://example.invalid:bad-port/logo.png"})
    void optionalMalformedSourcesRemainUnresolvedWithOrWithoutAValidBase(final String source) throws Exception {
        for (final String base : new String[] {null, "https://example.invalid/images/", images.toUri().toString()}) {
            final String html = image(source);
            final EmailPopulatingBuilder draft = mail.emailBuilder().startingBlank()
                    .withImageResolutionConfig(resolution(base, false)).withHTMLText(html);

            final Email first = draft.buildEmail();
            assertThat(first.getHTMLText()).as("base %s", base).isEqualTo(html);
            assertThat(first.getEmbeddedImages()).isEmpty();
            assertThat(draft.buildEmail().getHTMLText()).isEqualTo(html);
            assertThat(draft.getEmbeddedImages()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://[", "http://example.invalid:bad-port/logo.png"})
    void requiredMalformedSourcesStillRejectThePassAndPreserveTheDraft(final String source) throws Exception {
        for (final String base : new String[] {null, "https://example.invalid/images/", images.toUri().toString()}) {
            final String html = image(source);
            final EmailPopulatingBuilder draft = mail.emailBuilder().startingBlank()
                    .withImageResolutionConfig(resolution(base, true)).withHTMLText(html);

            for (int attempt = 0; attempt < 2; attempt++) {
                assertThatThrownBy(draft::buildEmail).as("base %s", base).isInstanceOf(MailException.class)
                        .hasMessageContaining(source);
                assertThat(draft.getHTMLText()).isEqualTo(html);
                assertThat(draft.getEmbeddedImages()).isEmpty();
            }
        }
    }

    @Test
    void optionalMalformedSourceDoesNotDiscardAnEarlierResolvedImage() throws Exception {
        Files.write(images.resolve("logo.png"), new byte[] {1, 2, 3});
        final EmailPopulatingBuilder draft = mail.emailBuilder().startingBlank()
                .withImageResolutionConfig(resolution(images.toUri().toString(), false))
                .withHTMLText(image("logo.png") + image("http://["));

        final Email first = draft.buildEmail();
        assertThat(first.getEmbeddedImages()).hasSize(1);
        assertThat(first.getEmbeddedImages().get(0).readAllBytes()).containsExactly((byte) 1, (byte) 2, (byte) 3);
        assertThat(first.getHTMLText()).isEqualTo(image("cid:" + first.getEmbeddedImages().get(0).getName()) + image("http://["));
        assertThat(draft.buildEmail().getEmbeddedImages()).containsExactlyElementsOf(first.getEmbeddedImages());
        assertThat(draft.getHTMLText()).isEqualTo(first.getHTMLText());
    }

    @Test
    void configuredEmlImportKeepsAnOptionalMalformedImageSource() {
        final SimpleJavaMail configured = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.embeddedimages.dynamicresolution.enable.url", "true",
                "simplejavamail.embeddedimages.dynamicresolution.base.url", "https://example.invalid/images/",
                "simplejavamail.embeddedimages.dynamicresolution.mustbesuccesful", "false")).load());
        final String html = image("http://[");

        final Email received = configured.converter().fromEml("Content-Type: text/html\r\n\r\n" + html).toEmail();

        assertThat(received.getHTMLText()).isEqualTo(html);
        assertThat(received.getEmbeddedImages()).isEmpty();
    }

    private static ImageResolutionConfig resolution(final String base, final boolean required) {
        final ImageResolutionConfig.Builder choices = ImageResolutionConfig.builder()
                .withEmbeddedImageAutoResolutionForURLs(true).embeddedImageAutoResolutionMustBeSuccesful(required);
        if (base != null) {
            choices.withEmbeddedImageBaseUrl(base);
        }
        return choices.build();
    }

    private static String image(final String source) {
        return "<img src='" + source + "'>";
    }
}
