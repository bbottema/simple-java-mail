package org.simplejavamail.email.internal;

import com.sun.net.httpserver.HttpServer;
import jakarta.activation.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.config.ImageResolutionConfig;
import testutil.ConfigLoaderTestHelper;
import testutil.ResourceRepresentationCase;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamicImageHtmlInterpretationTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    private static final byte[] IMAGE = {1, 2, 3};
    private static final String CLASSPATH_BASE = "/test-html-resource-boundaries";

    @TempDir
    Path images;

    @ParameterizedTest
    @MethodSource("portableRepresentations")
    void fileLookupUsesTheAttributeValueOnceAndRetainsItsSourceMarkup(final ResourceRepresentationCase representation) throws Exception {
        Files.write(images.resolve(representation.getFilename()), IMAGE);
        if (!representation.getFilename().equals(representation.getHtmlLocation())) {
            Files.write(images.resolve(representation.getHtmlLocation()), new byte[] {9});
        }
        final String before = "<IMG alt='src=\"decoy.png\" > example' SRC = \"";
        final String after = "\" data-label='a&amp;b'>";
        final Email email = MAIL.emailBuilder().startingBlank().withImageResolutionConfig(fileResolution(true))
                .withHTMLText(before + representation.getHtmlLocation() + after).buildEmail();

        final AttachmentResource image = onlyImage(email);
        assertThat(image.readAllBytes()).containsExactly(IMAGE);
        assertThat(email.getHTMLText()).isEqualTo(before + "cid:" + image.getName() + after);
    }

    @ParameterizedTest
    @EnumSource(value = ResourceRepresentationCase.class, names = {"AMPERSAND", "NUMERIC_AMPERSAND", "LITERAL_ENTITY"})
    void classpathLookupUsesTheSameDecodedAttributeMeaning(final ResourceRepresentationCase representation) {
        final Email email = MAIL.emailBuilder().startingBlank().withImageResolutionConfig(ImageResolutionConfig.builder()
                        .withEmbeddedImageAutoResolutionForClassPathResources(true).withEmbeddedImageBaseClassPath(CLASSPATH_BASE)
                        .embeddedImageAutoResolutionMustBeSuccesful(true).build())
                .withHTMLText("<img src='" + representation.getHtmlLocation() + "'>").buildEmail();

        assertThat(new String(onlyImage(email).readAllBytes(), StandardCharsets.UTF_8).trim()).isEqualTo(representation.getFilename());
    }

    @ParameterizedTest
    @EnumSource(value = ResourceRepresentationCase.class, names = {"AMPERSAND", "NUMERIC_AMPERSAND", "LITERAL_ENTITY"})
    void urlLookupUsesTheSameDecodedAttributeMeaning(final ResourceRepresentationCase representation) throws Exception {
        final List<String> requests = new ArrayList<>();
        final HttpServer server = imageServer(requests);
        try {
            final Email email = MAIL.emailBuilder().startingBlank().withImageResolutionConfig(urlResolution(server, true))
                    .withHTMLText("<img src='" + representation.getHtmlLocation() + "'>").buildEmail();

            assertThat(onlyImage(email).readAllBytes()).containsExactly(IMAGE);
            assertThat(requests).isNotEmpty().allSatisfy(request -> assertThat(request).isEqualTo("/images/" + representation.getFilename()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void querySeparatorsAreHtmlDecodedWithoutChangingUrlPercentEscapesOrPlusSigns() throws Exception {
        final List<String> requests = new ArrayList<>();
        final HttpServer server = imageServer(requests);
        try {
            final Email email = MAIL.emailBuilder().startingBlank().withImageResolutionConfig(urlResolution(server, true))
                    .withHTMLText("<img src='logo.png?x=a%2Fb+c&amp;y=2'>").buildEmail();
            assertThat(onlyImage(email).readAllBytes()).containsExactly(IMAGE);
            assertThat(requests).isNotEmpty().allSatisfy(request -> assertThat(request).isEqualTo("/images/logo.png?x=a%2Fb+c&y=2"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void decodedEquivalentSourcesShareOneGeneratedResourceAndAnOwnedPassRemainsAtomic() throws Exception {
        Files.write(images.resolve("a&b.png"), IMAGE);
        final String first = "<img src='a&amp;b.png'><img src=\"a&#38;b.png\">";
        final String failing = first + "<img src='missing&amp;image.png'>";
        final EmailPopulatingBuilder draft = MAIL.emailBuilder().startingBlank().withImageResolutionConfig(fileResolution(true))
                .withHTMLText(failing);
        assertThatThrownBy(draft::buildEmail).isInstanceOf(MailException.class).hasMessageContaining("missing&image.png");
        assertThat(draft.getHTMLText()).isEqualTo(failing);
        assertThat(draft.getEmbeddedImages()).isEmpty();

        final Email email = draft.withHTMLText(first).buildEmail();
        final AttachmentResource image = onlyImage(email);
        assertThat(image.readAllBytes()).containsExactly(IMAGE);
        assertThat(email.getHTMLText()).isEqualTo("<img src='cid:" + image.getName() + "'><img src=\"cid:" + image.getName() + "\">");
        assertThat(draft.buildEmail().getHTMLText()).isEqualTo(email.getHTMLText());
    }

    @Test
    void anHtmlEncodedCidSchemeIsNotFetchedAndDoesNotReadBorrowedContent() throws Exception {
        final DataSource data = mock(DataSource.class);
        when(data.getName()).thenReturn("a&amp;b.png");
        when(data.getContentType()).thenReturn("image/png");
        final AttachmentResource image = AttachmentResource.builder().withName("a&amp;b.png").withContentId("Logo+1@example.org")
                .withDataSource(data).build();
        final String html = "<img src='c&#105;d:Logo+1@example.org'>";
        final Email email = MAIL.emailBuilder().startingBlank().withImageResolutionConfig(fileResolution(true))
                .withEmbeddedImage(image).withHTMLText(html).buildEmail();

        assertThat(email.getHTMLText()).isEqualTo(html);
        assertThat(email.getEmbeddedImages()).containsExactly(image);
        verify(data, never()).getInputStream();
    }

    @Test
    void optionalUnresolvedSourcesKeepTheirOriginalEncodedSpelling() throws Exception {
        Files.write(images.resolve("a&b.png"), IMAGE);
        final Email email = MAIL.emailBuilder().startingBlank().withImageResolutionConfig(fileResolution(false))
                .withHTMLText("<img src='a&amp;b.png'><img src='missing&amp;image.png'>").buildEmail();
        final AttachmentResource image = onlyImage(email);
        assertThat(email.getHTMLText()).isEqualTo("<img src='cid:" + image.getName() + "'><img src='missing&amp;image.png'>");
    }

    @Test
    void decodedTraversalStillUsesTheFileContainmentGuard() throws Exception {
        final Path base = Files.createDirectory(images.resolve("base"));
        Files.write(images.resolve("outside.png"), IMAGE);
        final String html = "<img src='&#46;&#46;&#47;outside.png'>";
        final EmailPopulatingBuilder draft = MAIL.emailBuilder().startingBlank().withImageResolutionConfig(ImageResolutionConfig.builder()
                        .withEmbeddedImageAutoResolutionForFiles(true).withEmbeddedImageBaseDir(base.toString())
                        .embeddedImageAutoResolutionMustBeSuccesful(true).build()).withHTMLText(html);
        assertThatThrownBy(draft::buildEmail).isInstanceOf(MailException.class).hasMessageContaining("../outside.png");
        assertThat(draft.getHTMLText()).isEqualTo(html);
        assertThat(draft.getEmbeddedImages()).isEmpty();

        final Email allowed = draft.withImageResolutionConfig(ImageResolutionConfig.builder().withEmbeddedImageAutoResolutionForFiles(true)
                .withEmbeddedImageBaseDir(base.toString()).allowingEmbeddedImageOutsideBaseDir(true)
                .embeddedImageAutoResolutionMustBeSuccesful(true).build()).buildEmail();
        assertThat(onlyImage(allowed).readAllBytes()).containsExactly(IMAGE);
    }

    @Test
    void decodedTraversalStillUsesTheClasspathContainmentGuard() {
        final EmailPopulatingBuilder draft = MAIL.emailBuilder().startingBlank().withImageResolutionConfig(ImageResolutionConfig.builder()
                        .withEmbeddedImageAutoResolutionForClassPathResources(true).withEmbeddedImageBaseClassPath(CLASSPATH_BASE)
                        .embeddedImageAutoResolutionMustBeSuccesful(true).build())
                .withHTMLText("<img src='&#46;&#46;&#47;log4j2.xml'>");
        assertThatThrownBy(draft::buildEmail).isInstanceOf(MailException.class).hasMessageContaining("../log4j2.xml");
        assertThat(draft.getEmbeddedImages()).isEmpty();
    }

    @Test
    void decodedTraversalStillUsesTheUrlContainmentGuardWithoutMakingARequest() throws Exception {
        final List<String> requests = new ArrayList<>();
        final HttpServer server = imageServer(requests);
        try {
            final EmailPopulatingBuilder draft = MAIL.emailBuilder().startingBlank().withImageResolutionConfig(urlResolution(server, true))
                    .withHTMLText("<img src='&#46;&#46;&#47;outside.png'>");
            assertThatThrownBy(draft::buildEmail).isInstanceOf(MailException.class).hasMessageContaining("../outside.png");
            assertThat(requests).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void disabledResolutionPreservesEncodedHtmlAndBorrowedResourcesAcrossBuildAndCopy() throws Exception {
        final DataSource data = mock(DataSource.class);
        when(data.getName()).thenReturn("literal&amp;name.png");
        when(data.getContentType()).thenReturn("image/png");
        final AttachmentResource image = AttachmentResource.builder().withName("literal&amp;name.png").withDataSource(data).build();
        final String html = "<table><tr><td><img src='missing&amp;file.png'></td></tr></table><img src='cid:literal&amp;amp;name.png'>";
        final Email email = MAIL.emailBuilder().startingBlank().withHTMLText(html).withEmbeddedImage(image)
                .withImageResolutionConfig(ImageResolutionConfig.builder().embeddedImageAutoResolutionMustBeSuccesful(true).build()).buildEmail();
        final Email copy = MAIL.emailBuilder().copying(email).buildEmail();

        assertThat(email.getHTMLText()).isEqualTo(html);
        assertThat(copy.getHTMLText()).isEqualTo(html);
        assertThat(copy.getEmbeddedImages()).containsExactly(image);
        assertThat(copy.getEmbeddedImages().get(0).getDataSource()).isSameAs(data);
        verify(data, never()).getInputStream();
    }

    @Test
    void imagesInTableTemplateAndSvgContextsAreRewrittenInOriginalSourceOrder() throws Exception {
        Files.write(images.resolve("a&b.png"), new byte[] {1});
        Files.write(images.resolve("second.png"), new byte[] {2});
        Files.write(images.resolve("third.png"), new byte[] {3});
        final String html = "<table><tr><td><img src='a&amp;b.png'></td></tr></table>"
                + "<template><img src=\"second.png\"></template><svg><foreignObject><img src='third.png'></foreignObject></svg>";
        final Email email = MAIL.emailBuilder().startingBlank().withHTMLText(html).withImageResolutionConfig(fileResolution(true)).buildEmail();

        assertThat(email.getEmbeddedImages()).hasSize(3);
        for (int index = 0; index < 3; index++) {
            assertThat(email.getEmbeddedImages().get(index).readAllBytes()).containsExactly((byte) (index + 1));
        }
        final List<AttachmentResource> resources = email.getEmbeddedImages();
        assertThat(email.getHTMLText()).isEqualTo("<table><tr><td><img src='cid:" + resources.get(0).getName() + "'></td></tr></table>"
                + "<template><img src=\"cid:" + resources.get(1).getName() + "\"></template>"
                + "<svg><foreignObject><img src='cid:" + resources.get(2).getName() + "'></foreignObject></svg>");
    }

    @Test
    void unquotedSourcesAndSourcesOnOtherElementsKeepTheirExistingConsumerPolicy() throws Exception {
        Files.write(images.resolve("a&b.png"), IMAGE);
        final String html = "<img src=a&amp;b.png><source src='a&amp;b.png'><input src='a&amp;b.png'>";
        final Email email = MAIL.emailBuilder().startingBlank().withImageResolutionConfig(fileResolution(true)).withHTMLText(html).buildEmail();
        assertThat(email.getHTMLText()).isEqualTo(html);
        assertThat(email.getEmbeddedImages()).isEmpty();
    }

    private static Stream<ResourceRepresentationCase> portableRepresentations() {
        return Arrays.stream(ResourceRepresentationCase.values()).filter(ResourceRepresentationCase::isPortableFilename);
    }

    private ImageResolutionConfig fileResolution(final boolean required) {
        return ImageResolutionConfig.builder().withEmbeddedImageAutoResolutionForFiles(true).withEmbeddedImageBaseDir(images.toString())
                .embeddedImageAutoResolutionMustBeSuccesful(required).build();
    }

    private static ImageResolutionConfig urlResolution(final HttpServer server, final boolean required) {
        return ImageResolutionConfig.builder().withEmbeddedImageAutoResolutionForURLs(true)
                .withEmbeddedImageBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/images/")
                .embeddedImageAutoResolutionMustBeSuccesful(required).build();
    }

    private static AttachmentResource onlyImage(final Email email) {
        assertThat(email.getEmbeddedImages()).hasSize(1);
        return email.getEmbeddedImages().get(0);
    }

    private static HttpServer imageServer(final List<String> requests) throws Exception {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, IMAGE.length);
            try (var output = exchange.getResponseBody()) {
                output.write(IMAGE);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }
}
