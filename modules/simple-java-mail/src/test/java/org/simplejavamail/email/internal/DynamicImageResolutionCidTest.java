package org.simplejavamail.email.internal;

import jakarta.activation.DataSource;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.config.ImageResolutionConfig;
import testutil.ConfigLoaderTestHelper;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamicImageResolutionCidTest {

    private static final String CONTENT_ID = "Logo/1@Example.org";
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @TempDir
    Path images;

    enum Source { FILE, CLASSPATH, LOCAL_URL, ALL }

    @ParameterizedTest
    @MethodSource("schemesAndSources")
    void requiredResolutionPreservesExistingCidReferencesWithoutReadingTheirContent(final String scheme, final Source source) throws Exception {
        final DataSource borrowed = mock(DataSource.class);
        when(borrowed.getName()).thenReturn("logo.png");
        when(borrowed.getContentType()).thenReturn("image/png");
        final AttachmentResource resource = AttachmentResource.builder().withName("logo.png")
                .withContentId(CONTENT_ID).withDataSource(borrowed).build();
        final String html = "<img src='" + scheme + CONTENT_ID + "'><img src=\"" + scheme + CONTENT_ID + "\">";
        final EmailPopulatingBuilder draft = mail.emailBuilder().startingBlank()
                .withImageResolutionConfig(resolution(source)).withHTMLText(html)
                .withEmbeddedImage(resource).withEmbeddedImage(resource);

        final Email email = draft.buildEmail();

        assertThat(email.getHTMLText()).isEqualTo(html);
        assertThat(email.getEmbeddedImages()).hasSize(2).allSatisfy(image -> {
            assertThat(image.getContentId()).isEqualTo(CONTENT_ID);
            assertThat(image.getDataSource()).isSameAs(borrowed);
        });
        assertThat(draft.buildEmail().getHTMLText()).isEqualTo(html);
        assertThat(draft.getEmbeddedImages()).containsExactly(resource, resource);
        verify(borrowed, never()).getInputStream();
    }

    @ParameterizedTest
    @EnumSource(Source.class)
    void anExistingCidDoesNotMakeAMissingExternalImageOptional(final Source source) throws Exception {
        final String html = "<img src='CID:" + CONTENT_ID + "'><img src='missing-issue-887.png'>";
        final EmailPopulatingBuilder draft = mail.emailBuilder().startingBlank()
                .withImageResolutionConfig(resolution(source)).withHTMLText(html)
                .withEmbeddedImage(AttachmentResource.builder().withName("logo.png").withContentId(CONTENT_ID)
                        .withData(new byte[] {1}, "image/png").build());

        assertThatThrownBy(draft::buildEmail).isInstanceOf(MailException.class)
                .hasMessageContaining("missing-issue-887.png");
        assertThat(draft.getHTMLText()).isEqualTo(html);
        assertThat(draft.getEmbeddedImages()).hasSize(1);
    }

    private ImageResolutionConfig resolution(final Source source) throws Exception {
        return ImageResolutionConfig.builder()
                .withEmbeddedImageAutoResolutionForFiles(source == Source.FILE || source == Source.ALL)
                .withEmbeddedImageAutoResolutionForClassPathResources(source == Source.CLASSPATH || source == Source.ALL)
                .withEmbeddedImageAutoResolutionForURLs(source == Source.LOCAL_URL || source == Source.ALL)
                .withEmbeddedImageBaseDir(images.toString())
                .withEmbeddedImageBaseClassPath("/test-dynamicembedded-image")
                .withEmbeddedImageBaseUrl(images.toUri().toURL())
                .embeddedImageAutoResolutionMustBeSuccesful(true).build();
    }

    private static Stream<Arguments> schemesAndSources() {
        return Arrays.stream(Source.values()).flatMap(source -> Stream.of("cid:", "CID:", "cId:")
                .map(scheme -> Arguments.of(scheme, source)));
    }
}
