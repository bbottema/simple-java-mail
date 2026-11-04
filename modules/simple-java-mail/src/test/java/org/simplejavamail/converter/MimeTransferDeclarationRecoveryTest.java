package org.simplejavamail.converter;

import jakarta.activation.DataHandler;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.*;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.*;

class MimeTransferDeclarationRecoveryTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    private static final String HEADERS = "Message-ID: <cte@example.org>\r\nFrom: sender@example.org\r\nTo: reader@example.org\r\n";
    enum Component { PLAIN, HTML, CALENDAR, RESOURCE, CONTAINER }

    private static Stream<Arguments> repeatedDeclarations() {
        return Stream.of(Component.values()).flatMap(component -> Stream.of(false, true).map(reverse -> Arguments.of(component, reverse)));
    }

    @ParameterizedTest @MethodSource("repeatedDeclarations")
    void repeatedDeclarationsNeverCommitTheFirstReadersGuess(final Component component, final boolean reverse) {
        final String raw = component == Component.CONTAINER
                ? "--inside\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nGuessed inner contribution\r\n--inside--" : "SGVsbG8h";
        final String type = component == Component.PLAIN ? "text/plain; charset=UTF-8"
                : component == Component.HTML ? "text/html; charset=UTF-8"
                : component == Component.CALENDAR ? "text/calendar; charset=UTF-8; method=REQUEST"
                : component == Component.CONTAINER ? "multipart/mixed; boundary=inside" : "application/octet-stream";
        final String first = reverse ? "base64" : "7bit";
        final String second = reverse ? "7bit" : "base64";
        final String neighborType = component == Component.PLAIN ? "text/html; charset=UTF-8" : "text/plain; charset=UTF-8";
        final String neighbor = component == Component.PLAIN ? "<p>Readable neighbor</p>" : "Readable neighbor";
        final String input = HEADERS + "Content-Type: multipart/mixed; boundary=parts\r\n\r\n"
                + "--parts\r\nContent-Type: " + type + "\r\n"
                + (component == Component.RESOURCE ? "Content-Disposition: attachment; filename=ambiguous.bin\r\n" : "")
                + "Content-Transfer-Encoding: " + first + "\r\nContent-Transfer-Encoding: " + second + "\r\n\r\n" + raw
                + "\r\n--parts\r\nContent-Type: " + neighborType + "\r\n\r\n" + neighbor + "\r\n--parts--\r\n";

        final Email email = MAIL.converter().fromEml(input).toEmail();

        assertThat(email.getPlainText()).isEqualTo(component == Component.PLAIN ? null : neighbor);
        assertThat(email.getHTMLText()).isEqualTo(component == Component.PLAIN ? neighbor : null);
        assertThat(email.getCalendarText()).isNull();
        assertThat(email.getCalendarMethod()).isNull();
        assertThat(email.getAttachments()).isEmpty();
        final List<EmailSourcePart> retained = retained(email.getSourceData());
        assertThat(retained).hasSize(1);
        final EmailSourcePart original = retained.get(0);
        assertThat(original.getContent().getRepresentation()).isEqualTo(EmailSourceContent.Representation.SOURCE_BYTES);
        assertThat(original.getContent().isFullyCaptured()).isTrue();
        assertThat(original.getContent().readAllBytes()).isEqualTo(raw.getBytes(UTF_8));
        assertThat(original.getHeaders().stream().filter(header -> header.getName().equalsIgnoreCase("Content-Transfer-Encoding"))
                .map(EmailSourceHeader::getRawValue)).containsExactly(first, second);
        assertThat(original.getChildren()).isEmpty();
        assertThat(email.getProblems()).anySatisfy(problem -> assertThat(problem.getSources()).contains(original));
        if (component == Component.RESOURCE) {
            final Email lazy = MAIL.converter().fromEml(input).withFetchAttachmentData(false).toEmail();
            assertThat(lazy.getAttachments()).isEmpty();
            assertThat(retained(lazy.getSourceData())).singleElement().satisfies(part ->
                    assertThat(part.getContent().readAllBytes()).isEqualTo(raw.getBytes(UTF_8)));
        }
    }

    @Test
    void aProviderSupportedBodyCodecDoesNotNeedAnSjmEnumHint() throws Exception {
        final MimeMessage input = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(
                (HEADERS + "Content-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: x-reader-codec\r\n\r\nopaque").getBytes(UTF_8))) {
            @Override public Object getContent() { return "Reader interpreted content"; }
        };
        final Email email;
        try (MockedStatic<ModuleLoader> ignored = Mockito.mockStatic(ModuleLoader.class)) {
            email = MAIL.converter().fromMimeMessage(input).toEmail();
        }
        assertThat(email.getPlainText()).isEqualTo("Reader interpreted content");
        assertThat(email.getContentTransferEncoding()).isNull();
        assertThat(email.getProblems()).isEmpty();
        assertThat(retained(email.getSourceData())).isEmpty();
    }

    @Test
    void aProviderSupportedResourceCodecDoesNotNeedAnSjmEnumHint() throws Exception {
        final ByteArrayDataSource decoded = new ByteArrayDataSource("Decoded bytes".getBytes(UTF_8), "application/octet-stream");
        decoded.setName("decoded.bin");
        final MimeBodyPart part = new MimeBodyPart();
        part.setDataHandler(new DataHandler(decoded));
        part.setHeader("Content-Type", "application/octet-stream");
        part.setHeader("Content-Transfer-Encoding", "x-reader-codec");
        part.setDisposition(jakarta.mail.Part.ATTACHMENT);
        part.setFileName("decoded.bin");
        final MimeMultipart mixed = new MimeMultipart();
        mixed.addBodyPart(part);
        final MimeMessage input = new MimeMessage(Session.getInstance(new Properties()));
        input.setContent(mixed);
        input.setHeader("Content-Type", mixed.getContentType());
        final Email email;
        try (MockedStatic<ModuleLoader> ignored = Mockito.mockStatic(ModuleLoader.class)) {
            email = MAIL.converter().fromMimeMessage(input).toEmail();
        }
        assertThat(email.getAttachments()).hasSize(1);
        assertThat(email.getAttachments().get(0).readAllBytes()).isEqualTo("Decoded bytes".getBytes(UTF_8));
        assertThat(email.getAttachments().get(0).getContentTransferEncoding()).isNull();
        assertThat(email.getProblems()).isEmpty();
        assertThat(retained(email.getSourceData())).isEmpty();
    }

    @Test
    void invalidLazyResourceDeclarationsDoNotFetchAnExternalPayloadForDiagnostics() throws Exception {
        final AtomicInteger decodedReads = new AtomicInteger();
        final AtomicInteger rawReads = new AtomicInteger();
        final MimeBodyPart part = new MimeBodyPart() {
            @Override public InputStream getRawInputStream() {
                rawReads.incrementAndGet();
                throw new AssertionError("diagnostics must not fetch this external payload");
            }
        };
        part.setDataHandler(new DataHandler(new ByteArrayDataSource("data".getBytes(UTF_8), "application/octet-stream") {
            @Override public InputStream getInputStream() {
                decodedReads.incrementAndGet();
                throw new AssertionError("ambiguous content must not be decoded");
            }
        }));
        part.setHeader("Content-Type", "application/octet-stream");
        part.setDisposition(jakarta.mail.Part.ATTACHMENT);
        part.setHeader("Content-Transfer-Encoding", "7bit");
        part.addHeader("Content-Transfer-Encoding", "base64");
        final MimeMultipart mixed = new MimeMultipart();
        mixed.addBodyPart(part);
        final MimeMessage input = new MimeMessage(Session.getInstance(new Properties()));
        input.setContent(mixed);
        input.setHeader("Content-Type", mixed.getContentType());
        final Email email;
        try (MockedStatic<ModuleLoader> ignored = Mockito.mockStatic(ModuleLoader.class)) {
            email = MAIL.converter().fromMimeMessage(input).withFetchAttachmentData(false).toEmail();
        }
        assertThat(email.getAttachments()).isEmpty();
        assertThat(decodedReads).hasValue(0);
        assertThat(rawReads).hasValue(0);
        assertThat(email.getProblems()).singleElement().satisfies(problem -> {
            assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.CONTENT_NOT_REPRESENTED);
            assertThat(((EmailSourcePart) problem.getSources().get(0)).getContent()).isNull();
        });
    }

    @Test
    void validDeclarationsDoNotHideAnUnrelatedGetterRuntimeFailure() throws Exception {
        final IllegalStateException failure = new IllegalStateException("application content failure");
        final MimeMessage input = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(
                (HEADERS + "Content-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: 7bit\r\n\r\nbody").getBytes(UTF_8))) {
            @Override public Object getContent() { throw failure; }
        };
        try (MockedStatic<ModuleLoader> ignored = Mockito.mockStatic(ModuleLoader.class)) {
            assertThatThrownBy(() -> MAIL.converter().fromMimeMessage(input).toEmail()).isSameAs(failure);
        }
    }

    private static List<EmailSourcePart> retained(final EmailSourceData source) {
        final List<EmailSourcePart> result = new ArrayList<>();
        for (final EmailSourcePart part : source.getParts()) collect(part, result);
        return result;
    }

    private static void collect(final EmailSourcePart part, final List<EmailSourcePart> result) {
        if (part.getContent() != null || part.getText() != null) result.add(part);
        for (final EmailSourcePart child : part.getChildren()) collect(child, result);
    }
}
