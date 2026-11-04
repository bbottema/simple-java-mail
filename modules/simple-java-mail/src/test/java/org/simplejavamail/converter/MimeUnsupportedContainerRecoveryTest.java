package org.simplejavamail.converter;

import jakarta.activation.DataContentHandler;
import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.activation.MailcapCommandMap;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.email.EmailSourceContent;
import org.simplejavamail.api.email.EmailSourcePart;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class MimeUnsupportedContainerRecoveryTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    private static final byte[] ORIGINAL = "--inside\r\nContent-Type: text/plain\r\n\r\nOriginal nested body\r\n--inside--".getBytes(UTF_8);

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void anUninterpretedMultipartStreamRetainsAvailableContentAndItsReadableSibling(final boolean failReading) throws Exception {
        final ContainerSource source = new ContainerSource(failReading);
        final MimeMessage input = message(source);
        final Email email;
        try (MockedStatic<ModuleLoader> ignored = Mockito.mockStatic(ModuleLoader.class)) {
            email = assertDoesNotThrow(() -> MAIL.converter().fromMimeMessage(input).toEmail());
        }

        assertThat(email.getHTMLText()).isEqualTo("<p>Readable neighbor</p>");
        assertThat(email.getAttachments()).isEmpty();
        assertThat(email.getProblems()).singleElement().satisfies(problem -> {
            assertThat(problem.getCode()).isEqualTo(failReading
                    ? EmailProblem.Code.CONTENT_READ_FAILED : EmailProblem.Code.CONTENT_NOT_REPRESENTED);
            assertThat(problem.getSources()).singleElement().satisfies(affected -> {
                assertThat(affected).isInstanceOf(EmailSourcePart.class);
                final EmailSourcePart part = (EmailSourcePart) affected;
                assertThat(part.getChildren()).isEmpty();
                assertThat(part.getContent().getRepresentation()).isEqualTo(EmailSourceContent.Representation.READER_BYTES);
                assertThat(part.getContent().isFullyCaptured()).isEqualTo(!failReading);
                assertThat(part.getContent().readAllBytes()).isEqualTo(failReading ? "prefix".getBytes(UTF_8) : ORIGINAL);
            });
        });
        assertThat(source.opened).isEqualTo(1);
        assertThat(source.closed).isEqualTo(1);
    }

    private static MimeMessage message(final ContainerSource source) throws Exception {
        final DataHandler unhandled = new DataHandler(source);
        unhandled.setCommandMap(new MailcapCommandMap() {
            @Override public DataContentHandler createDataContentHandler(final String type) { return null; }
        });
        final MimeBodyPart container = new MimeBodyPart();
        container.setDataHandler(unhandled);
        container.setHeader("Content-Type", source.getContentType());
        final MimeBodyPart readable = new MimeBodyPart();
        readable.setContent("<p>Readable neighbor</p>", "text/html; charset=UTF-8");
        final MimeMultipart mixed = new MimeMultipart();
        mixed.addBodyPart(container);
        mixed.addBodyPart(readable);
        final MimeMessage result = new MimeMessage(Session.getInstance(new Properties()));
        result.setFrom("sender@example.org");
        result.setRecipients(jakarta.mail.Message.RecipientType.TO, "reader@example.org");
        result.setContent(mixed);
        result.setHeader("Content-Type", mixed.getContentType());
        return result;
    }

    private static final class ContainerSource implements DataSource {
        private final boolean failReading;
        private int opened;
        private int closed;
        private ContainerSource(final boolean failReading) { this.failReading = failReading; }
        @Override public InputStream getInputStream() {
            opened++;
            return new InputStream() {
                private final ByteArrayInputStream data = new ByteArrayInputStream(failReading ? "prefix".getBytes(UTF_8) : ORIGINAL);
                @Override public int read() throws IOException {
                    final int value = data.read();
                    if (value == -1 && failReading) throw new IOException("multipart stream failed");
                    return value;
                }
                @Override public void close() { closed++; }
            };
        }
        @Override public OutputStream getOutputStream() throws IOException { throw new IOException("read only"); }
        @Override public String getContentType() { return "multipart/mixed; boundary=inside"; }
        @Override public String getName() { return "container"; }
    }
}
