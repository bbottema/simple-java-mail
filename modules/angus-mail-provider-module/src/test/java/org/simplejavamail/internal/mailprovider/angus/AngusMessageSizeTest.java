package org.simplejavamail.internal.mailprovider.angus;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.mailer.spi.MailTransportCompatibilityException;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AngusMessageSizeTest {

    @ParameterizedTest
    @ValueSource(strings = {"", "plain", "one\ntwo", "one\rtwo\r", "one\r\ntwo\r\n", ".\n..\nend", "Subject: café\n\nJosé"})
    void countsCanonicalContentWithoutTransparencyDotsOrTerminator(final String content) throws Exception {
        final String normalized = content.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\r\n");
        final String terminated = normalized.isEmpty() || normalized.endsWith("\r\n") ? normalized : normalized + "\r\n";
        final MimeMessage message = rawMessage(content);
        assertThat(AngusMessageSize.measure(message, () -> false)).isEqualTo(terminated.getBytes(UTF_8).length);
    }

    @Test
    void countsAnIncrementallyGeneratedLargeMessageWithoutRetainingItsContent() throws Exception {
        final byte[] chunk = new byte[8192];
        Arrays.fill(chunk, (byte) 'x');
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties())) {
            @Override
            public void writeTo(final OutputStream output, final String[] omittedHeaders) throws IOException {
                assertThat(omittedHeaders).containsExactly("Bcc", "Content-Length");
                for (int index = 0; index < 2048; index++) {
                    output.write(chunk);
                }
            }
        };
        assertThat(AngusMessageSize.measure(message, () -> false)).isEqualTo(16L * 1024 * 1024 + 2);
    }

    @Test
    void cancellationStopsTheReadInsteadOfPublishingAPartialCount() {
        final AtomicInteger checks = new AtomicInteger();
        assertThatThrownBy(() -> AngusMessageSize.measure(rawMessage("first\nsecond\nthird"), () -> checks.incrementAndGet() >= 2))
                .isInstanceOf(MessagingException.class).hasMessageContaining("No message was submitted").hasCauseInstanceOf(IOException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SIZE", "SIZE=", "SIZE=-1", "SIZE=+1", "SIZE=1.0", "SIZE=123456789012345678901", "SIZE=1 size=2", "SIZE =12"})
    void rejectsMalformedOrDuplicateAdvancedDeclarations(final String extension) {
        assertThatThrownBy(() -> AngusMessageSize.validateDeclaration(extension, "the Mailer property 'mail.smtp.mailextension'", new Address[0]))
                .isInstanceOf(MailTransportCompatibilityException.class).hasMessageContaining("Remove the SIZE option");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SIZE=0", "size=000123", "SIZE=18446744073709551615", "AUTH=user SIZE=42 X-TEST=yes"})
    void preservesOneValidAdvancedEstimateEvenWhenItDiffersFromMeasuredContent(final String extension) throws Exception {
        AngusMessageSize.validateDeclaration(extension, "test", new Address[0]);
        assertThat(AngusMessageSize.withDeclaration(extension, 200)).isEqualTo(extension);
    }

    @Test
    void appendsAutomaticSizeWithoutChangingOtherOptions() {
        assertThat(AngusMessageSize.withDeclaration(null, 123)).isEqualTo("SIZE=123");
        assertThat(AngusMessageSize.withDeclaration("AUTH=user X-SIZE=42", 123)).isEqualTo("AUTH=user X-SIZE=42 SIZE=123");
    }

    private static MimeMessage rawMessage(final String rawContent) {
        return new MimeMessage(Session.getInstance(new Properties())) {
            @Override
            public void writeTo(final OutputStream output, final String[] omittedHeaders) throws IOException {
                output.write(rawContent.getBytes(UTF_8));
            }
        };
    }
}
