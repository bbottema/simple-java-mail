package org.simplejavamail.converter;

import jakarta.activation.DataContentHandler;
import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.activation.MailcapCommandMap;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.moduleloader.ModuleLoader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.Properties;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.mailer.MailFailureKind.CONVERSION;

class TextBodyStreamImportTest {
    private static final String BODY = "R\u00e9union au caf\u00e9";
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

    @ParameterizedTest
    @MethodSource("bodySources")
    void decodesProviderStreamsThroughPublicImportRoutes(final String type, final boolean fluent,
            final boolean nested, final Charset charset) throws Exception {
        final BodySource source = new BodySource(BODY.getBytes(charset), type, charset.name());
        try (MockedStatic<ModuleLoader> ignored = Mockito.mockStatic(ModuleLoader.class)) {
            final Email imported = convert(message(source, nested), fluent);
            assertThat(body(imported, type)).isEqualTo(BODY);
        }
        assertThat(source.opened).isEqualTo(1);
        assertThat(source.closed).isEqualTo(1);
    }

    private static Stream<Arguments> bodySources() {
        return Stream.of("text/plain", "text/html").flatMap(type -> Stream.of(false, true)
                .flatMap(fluent -> Stream.of(false, true).flatMap(nested -> Stream.of(UTF_8, ISO_8859_1)
                        .map(charset -> Arguments.of(type, fluent, nested, charset)))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "text/html"})
    void absentCharsetMatchesTheOrdinaryTextHandlersEffectiveDefault(final String type) throws Exception {
        final byte[] bytes = BODY.getBytes(ISO_8859_1);
        final BodySource source = new BodySource(bytes, type, null);
        try (MockedStatic<ModuleLoader> ignored = Mockito.mockStatic(ModuleLoader.class)) {
            assertThat(body(convert(message(source, false), true), type)).isEqualTo(BODY);
        }
        assertThat(source.closed).isEqualTo(source.opened).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("mimeAliases")
    void declaredMimeCharsetAliasesRetainOrdinaryHandlerInterpretation(final String type, final String charset) throws Exception {
        final BodySource source = new BodySource(BODY.getBytes(ISO_8859_1), type, charset);
        try (MockedStatic<ModuleLoader> ignored = Mockito.mockStatic(ModuleLoader.class)) {
            assertThat(body(convert(message(source, false), true), type)).isEqualTo(BODY);
        }
        assertThat(source.closed).isEqualTo(source.opened).isEqualTo(1);
    }

    private static Stream<Arguments> mimeAliases() {
        return Stream.of("text/plain", "text/html").flatMap(type -> Stream.of("us-ascii", "x-us-ascii")
                .map(charset -> Arguments.of(type, charset)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "text/html"})
    void emptyBodyStreamsRemainPresentAsEmptyText(final String type) throws Exception {
        final BodySource source = new BodySource(new byte[0], type, "UTF-8");
        try (MockedStatic<ModuleLoader> ignored = Mockito.mockStatic(ModuleLoader.class)) {
            assertThat(body(convert(message(source, false), true), type)).isEmpty();
        }
        assertThat(source.closed).isEqualTo(source.opened).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("invalidCharsets")
    void invalidCharsetFailsAndClosesTheAlreadyAcquiredBodyStream(final String type, final String charset) throws Exception {
        final BodySource source = new BodySource(BODY.getBytes(UTF_8), type, charset);
        try (MockedStatic<ModuleLoader> ignored = Mockito.mockStatic(ModuleLoader.class)) {
            final MimeMessage incoming = message(source, false);
            assertThatThrownBy(() -> convert(incoming, true)).isInstanceOfSatisfying(MailException.class, failure -> {
                assertThat(failure.getKind()).isEqualTo(CONVERSION);
                assertThat(failure.getCause()).isInstanceOf(UnsupportedEncodingException.class);
                assertThat(failure.getCause().getCause()).isInstanceOf(IllegalArgumentException.class);
            });
        }
        assertThat(source.opened).isEqualTo(1);
        assertThat(source.closed).isEqualTo(1);
    }

    private static Stream<Arguments> invalidCharsets() {
        return Stream.of("text/plain", "text/html").flatMap(type -> Stream.of("X-NO-SUCH-CHARSET", "invalid charset", "")
                .map(charset -> Arguments.of(type, charset)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "text/html"})
    void readFailureKeepsItsCauseAndSuppressedCloseFailure(final String type) throws Exception {
        final BodySource source = new BodySource(BODY.getBytes(UTF_8), type, "UTF-8");
        source.readFailure = new IOException("body read failed");
        source.closeFailure = new IOException("body close failed");
        try (MockedStatic<ModuleLoader> ignored = Mockito.mockStatic(ModuleLoader.class)) {
            final MimeMessage incoming = message(source, false);
            assertThatThrownBy(() -> convert(incoming, false)).isInstanceOfSatisfying(MailException.class, failure -> {
                assertThat(failure.getKind()).isEqualTo(CONVERSION);
                assertThat(failure.getCause()).isSameAs(source.readFailure);
                assertThat(failure.getCause().getSuppressed()).containsExactly(source.closeFailure);
            });
        }
        assertThat(source.closed).isEqualTo(1);
    }

    @Test
    void successfulReadStillReportsAClosingFailure() throws Exception {
        final BodySource source = new BodySource(BODY.getBytes(UTF_8), "text/plain", "UTF-8");
        source.closeFailure = new IOException("cannot close body");
        try (MockedStatic<ModuleLoader> ignored = Mockito.mockStatic(ModuleLoader.class)) {
            final MimeMessage incoming = message(source, false);
            assertThatThrownBy(() -> convert(incoming, true)).isInstanceOfSatisfying(MailException.class,
                    failure -> assertThat(failure.getCause()).isSameAs(source.closeFailure));
        }
        assertThat(source.closed).isEqualTo(1);
    }

    @Test
    void invalidCharsetKeepsAClosingFailureSuppressed() throws Exception {
        final BodySource source = new BodySource(BODY.getBytes(UTF_8), "text/html", "X-NO-SUCH-CHARSET");
        source.closeFailure = new IOException("cannot close rejected body");
        try (MockedStatic<ModuleLoader> ignored = Mockito.mockStatic(ModuleLoader.class)) {
            final MimeMessage incoming = message(source, false);
            assertThatThrownBy(() -> convert(incoming, true)).isInstanceOfSatisfying(MailException.class, failure -> {
                assertThat(failure.getCause()).isInstanceOf(UnsupportedEncodingException.class);
                assertThat(failure.getCause().getSuppressed()).containsExactly(source.closeFailure);
            });
        }
        assertThat(source.closed).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "text/html"})
    void alreadyDecodedStringsDoNotAcquireNewCharsetValidation(final String type) throws Exception {
        final MimeMessage incoming = new MimeMessage(Session.getInstance(new Properties()));
        incoming.setDataHandler(new DataHandler(BODY, type));
        incoming.setHeader("Content-Type", type + "; charset=X-NO-SUCH-CHARSET");
        try (MockedStatic<ModuleLoader> ignored = Mockito.mockStatic(ModuleLoader.class)) {
            assertThat(body(convert(incoming, true), type)).isEqualTo(BODY);
        }
    }

    @Test
    void normalHandlerAndOptionalProtectionInspectionRetainReadableText() throws Exception {
        final BodySource normal = new BodySource(BODY.getBytes(UTF_8), "text/plain", "UTF-8");
        final MimeMessage incoming = message(normal, false);
        incoming.getDataHandler().setCommandMap(null);
        assertThat(convert(incoming, true).getPlainText()).isEqualTo(BODY);
        assertThat(normal.closed).isEqualTo(normal.opened);

        final BodySource fallback = new BodySource(BODY.getBytes(UTF_8), "text/plain", "UTF-8");
        assertThat(convert(message(fallback, false), true).getPlainText()).isEqualTo(BODY);
        assertThat(fallback.closed).isEqualTo(fallback.opened);
    }

    @Test
    void lazyAttachmentsRemainUnreadWhileTheirBodyStreamIsConsumed() throws Exception {
        final BodySource source = new BodySource(BODY.getBytes(UTF_8), "text/plain", "UTF-8");
        final BodySource attachment = new BodySource(new byte[] {1, 2, 3}, "application/octet-stream", null);
        final MimeMessage incoming = message(source, true);
        final MimeBodyPart part = new MimeBodyPart();
        part.setDataHandler(new DataHandler(attachment));
        part.setHeader("Content-Type", attachment.getContentType());
        part.setDisposition("attachment");
        part.setFileName("data.bin");
        ((MimeMultipart) incoming.getContent()).addBodyPart(part);
        try (MockedStatic<ModuleLoader> ignored = Mockito.mockStatic(ModuleLoader.class)) {
            final Email imported = mail.converter().fromMimeMessage(incoming).withFetchAttachmentData(false).toEmail();
            assertThat(imported.getPlainText()).isEqualTo(BODY);
            assertThat(attachment.opened).isZero();
            assertThat(imported.getAttachments().get(0).readAllBytes()).containsExactly(1, 2, 3);
        }
        assertThat(source.closed).isEqualTo(1);
        assertThat(attachment.closed).isEqualTo(attachment.opened).isEqualTo(1);
    }

    @Test
    void fluentEmlImportLeavesTheApplicationsTopLevelStreamOpen() {
        final byte[] eml = ("From: sender@example.org\r\nTo: reader@example.org\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nReadable body").getBytes(UTF_8);
        final int[] closed = {0};
        final InputStream input = new ByteArrayInputStream(eml) {
            @Override public void close() { closed[0]++; }
        };
        assertThat(mail.converter().fromEml(input).toEmail().getPlainText()).isEqualTo("Readable body");
        assertThat(closed[0]).isZero();
    }

    private Email convert(final MimeMessage incoming, final boolean fluent) {
        return fluent ? mail.converter().fromMimeMessage(incoming).toEmail() : EmailConverter.mimeMessageToEmail(incoming);
    }

    private static String body(final Email email, final String type) {
        return "text/html".equals(type) ? email.getHTMLText() : email.getPlainText();
    }

    private static MimeMessage message(final BodySource source, final boolean nested) throws Exception {
        final DataHandler content = new DataHandler(source);
        content.setCommandMap(new MailcapCommandMap() {
            @Override public DataContentHandler createDataContentHandler(final String type) { return null; }
        });
        final MimeMessage incoming = new MimeMessage(Session.getInstance(new Properties()));
        incoming.setFrom("sender@example.org");
        incoming.setRecipients(RecipientType.TO, "reader@example.org");
        if (nested) {
            final MimeBodyPart body = new MimeBodyPart();
            body.setDataHandler(content);
            body.setHeader("Content-Type", source.getContentType());
            final MimeMultipart alternatives = new MimeMultipart("alternative");
            alternatives.addBodyPart(body);
            incoming.setContent(alternatives);
            incoming.setHeader("Content-Type", alternatives.getContentType());
        } else {
            incoming.setDataHandler(content);
            incoming.setHeader("Content-Type", source.getContentType());
        }
        return incoming;
    }

    private static final class BodySource implements DataSource {
        private final byte[] bytes;
        private final String type;
        private final String charset;
        private IOException readFailure;
        private IOException closeFailure;
        private int opened;
        private int closed;

        private BodySource(final byte[] bytes, final String type, final String charset) {
            this.bytes = bytes;
            this.type = type;
            this.charset = charset;
        }

        @Override public InputStream getInputStream() {
            opened++;
            final ByteArrayInputStream data = new ByteArrayInputStream(bytes);
            return new InputStream() {
                @Override public int read() throws IOException {
                    if (readFailure != null) throw readFailure;
                    return data.read();
                }
                @Override public int read(final byte[] buffer, final int offset, final int length) throws IOException {
                    if (readFailure != null) throw readFailure;
                    return data.read(buffer, offset, length);
                }
                @Override public void close() throws IOException {
                    closed++;
                    if (closeFailure != null) throw closeFailure;
                }
            };
        }
        @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
        @Override public String getContentType() { return type + (charset == null ? "" : "; charset=\"" + charset + "\""); }
        @Override public String getName() { return "body"; }
    }
}
