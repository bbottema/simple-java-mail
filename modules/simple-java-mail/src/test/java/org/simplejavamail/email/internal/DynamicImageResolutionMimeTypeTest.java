package org.simplejavamail.email.internal;

import com.sun.net.httpserver.HttpServer;
import jakarta.mail.BodyPart;
import jakarta.mail.Part;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.config.ImageResolutionConfig;
import testutil.ConfigLoaderTestHelper;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.parallel.Resources.LOCALE;

@ResourceLock(LOCALE)
class DynamicImageResolutionMimeTypeTest {

    private static final String FILENAME = "LOCALE.GIF";
    private static final String CLASSPATH_BASE = "/test-dynamicembedded-image";
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @TempDir
    Path images;

    enum Source { CLASSPATH, FILE, URL_WITHOUT_TYPE, URL_WITH_REPORTED_TYPE }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("sourcesAndLocales")
    void automaticResolutionRetainsImageBytesAndEmitsTheSameMimeTypeRegardlessOfLocale(
            final Source source, final String languageTag) throws Exception {
        final byte[] content;
        try (InputStream stream = getClass().getResourceAsStream(CLASSPATH_BASE + "/" + FILENAME)) {
            assertThat(stream).isNotNull();
            content = stream.readAllBytes();
        }
        Files.write(images.resolve(FILENAME), content);
        final AtomicInteger requests = new AtomicInteger();
        final HttpServer server = source == Source.URL_WITHOUT_TYPE || source == Source.URL_WITH_REPORTED_TYPE
                ? imageServer(source, content, requests) : null;
        final Locale previous = Locale.getDefault();
        final Locale previousDisplay = Locale.getDefault(Locale.Category.DISPLAY);
        final Locale previousFormat = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.forLanguageTag(languageTag));
            final ImageResolutionConfig.Builder resolution = ImageResolutionConfig.builder()
                    .embeddedImageAutoResolutionMustBeSuccesful(true);
            switch (source) {
                case CLASSPATH:
                    resolution.withEmbeddedImageAutoResolutionForClassPathResources(true)
                            .withEmbeddedImageBaseClassPath(CLASSPATH_BASE);
                    break;
                case FILE:
                    resolution.withEmbeddedImageAutoResolutionForFiles(true).withEmbeddedImageBaseDir(images.toString());
                    break;
                case URL_WITHOUT_TYPE:
                case URL_WITH_REPORTED_TYPE:
                    resolution.withEmbeddedImageAutoResolutionForURLs(true)
                            .withEmbeddedImageBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/images/");
                    break;
                default:
                    throw new AssertionError(source);
            }
            final Email email = mail.emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org")
                    .withImageResolutionConfig(resolution.build()).withHTMLText("<img src='" + FILENAME + "'>").buildEmail();

            assertThat(email.getEmbeddedImages()).hasSize(1);
            final AttachmentResource image = email.getEmbeddedImages().get(0);
            final String expectedType = source == Source.URL_WITH_REPORTED_TYPE ? "image/png" : "image/gif";
            assertThat(image.getDataSource().getContentType()).isEqualTo(expectedType);
            assertThat(image.readAllBytes()).isEqualTo(content);
            assertThat(email.getHTMLText()).isEqualTo("<img src='cid:" + image.getName() + "'>");

            final MimeMessage mime = mail.converter().fromEmail(email).toMimeMessage();
            assertThat(mime.isMimeType("multipart/related")).isTrue();
            final MimeMultipart parts = (MimeMultipart) mime.getContent();
            assertThat(parts.getCount()).isEqualTo(2);
            final BodyPart renderedImage = parts.getBodyPart(1);
            assertThat(new ContentType(renderedImage.getContentType()).getBaseType()).isEqualTo(expectedType);
            assertThat(renderedImage.getDisposition()).isEqualTo(Part.INLINE);
            assertThat(renderedImage.getHeader("Content-ID")).containsExactly("<" + image.getName() + ">");
            try (InputStream stream = renderedImage.getInputStream()) {
                assertThat(stream.readAllBytes()).isEqualTo(content);
            }
            assertThat(requests.get()).isEqualTo(server == null ? 0 : 1);
        } finally {
            if (server != null) {
                server.stop(0);
            }
            Locale.setDefault(previous);
            Locale.setDefault(Locale.Category.DISPLAY, previousDisplay);
            Locale.setDefault(Locale.Category.FORMAT, previousFormat);
        }
    }

    private static Stream<Arguments> sourcesAndLocales() {
        return Arrays.stream(Source.values()).flatMap(source -> Stream.of("en-US", "tr-TR")
                .map(languageTag -> Arguments.of(source, languageTag)));
    }

    private static HttpServer imageServer(final Source source, final byte[] content, final AtomicInteger requests) throws Exception {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/images/" + FILENAME, exchange -> {
            requests.incrementAndGet();
            if (source == Source.URL_WITH_REPORTED_TYPE) {
                exchange.getResponseHeaders().set("Content-Type", "image/png");
            }
            exchange.sendResponseHeaders(200, content.length);
            try (var output = exchange.getResponseBody()) {
                output.write(content);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }
}
