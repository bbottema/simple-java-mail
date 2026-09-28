package org.simplejavamail.internal.mailprovider.angus;

import jakarta.mail.Address;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.mailer.spi.ContentRequirement;
import org.simplejavamail.api.mailer.spi.DeliveryEnvelope;
import org.simplejavamail.api.mailer.spi.MailTransportCompatibilityException;
import org.simplejavamail.api.mailer.spi.PreparedMail;
import org.simplejavamail.internal.util.FinalizedMimeMessage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises the same body rules through streamed MIME and authoritative serialized bytes. */
class AngusContentInspectionTest {

    private static final String HEADERS = "From: sender@example.test\r\nTo: receiver@example.test\r\n"
            + "Content-Type: text/plain\r\nContent-Transfer-Encoding: 8bit\r\n\r\n";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void detectsEightBitAndZeroBytesAroundReadBoundaries(final boolean finalized) throws Exception {
        for (final int position : new int[] {0, 8191, 8192, 8193, 16383}) {
            final byte[] body = new byte[16384];
            Arrays.fill(body, (byte) '\n');
            body[position] = (byte) 0xe9;
            assertThat(inspect(body, finalized)).as("8-bit byte at %s", position).isEqualTo("BODY=8BITMIME");
            body[position] = 0;
            assertThatThrownBy(() -> inspect(body, finalized)).as("zero byte at %s", position)
                    .isInstanceOf(MailTransportCompatibilityException.class).hasMessageContaining("unencoded zero byte");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lineLengthCarriesAcrossBlocksAndAcceptsExactly998Bytes(final boolean finalized) throws Exception {
        final String prefix = "x\n".repeat(3850);
        assertThat(inspect((prefix + "x".repeat(998)).getBytes(StandardCharsets.US_ASCII), finalized)).isNull();
        assertThatThrownBy(() -> inspect((prefix + "x".repeat(999)).getBytes(StandardCharsets.US_ASCII), finalized))
                .isInstanceOf(MailTransportCompatibilityException.class).hasMessageContaining("longer than the supported limit of 998 bytes");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void allLineBreakFormsResetTheCountIncludingSplitCrLf(final boolean finalized) throws Exception {
        final String prefix = "x\n".repeat(3597) + "x".repeat(997);
        for (final String lineBreak : new String[] {"\r", "\n", "\r\n"}) {
            assertThat(inspect((prefix + lineBreak + "x".repeat(998)).getBytes(StandardCharsets.US_ASCII), finalized)).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void firstUnsupportedByteDeterminesTheFailure(final boolean finalized) throws Exception {
        assertThatThrownBy(() -> inspect(("\0" + "x".repeat(999)).getBytes(StandardCharsets.US_ASCII), finalized))
                .hasMessageContaining("unencoded zero byte").hasMessageNotContaining("longer than");
        assertThatThrownBy(() -> inspect(("x".repeat(999) + "\0").getBytes(StandardCharsets.US_ASCII), finalized))
                .hasMessageContaining("longer than").hasMessageNotContaining("unencoded zero byte");
        assertThat(inspect(new byte[0], finalized)).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 17, 8192})
    void shortReadsKeepLineStateAndCloseTheStream(final int readSize) throws Exception {
        final byte[] body = ("x\n".repeat(4096) + "x".repeat(999)).getBytes(StandardCharsets.US_ASCII);
        final AtomicBoolean closed = new AtomicBoolean();
        final InputStream input = new ByteArrayInputStream(body) {
            @Override
            public synchronized int read(final byte[] target, final int offset, final int length) {
                return super.read(target, offset, Math.min(readSize, length));
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
        assertThatThrownBy(() -> resolve(streamedMessage(input), ContentRequirement.NORMAL))
                .hasMessageContaining("longer than the supported limit of 998 bytes");
        assertThat(closed).isTrue();
    }

    @Test
    void successfulReadClosesTheStream() throws Exception {
        final AtomicBoolean closed = new AtomicBoolean();
        final InputStream input = new ByteArrayInputStream("ASCII body\r\n".getBytes(StandardCharsets.US_ASCII)) {
            @Override
            public void close() {
                closed.set(true);
            }
        };
        assertThat(resolve(streamedMessage(input), ContentRequirement.NORMAL)).isNull();
        assertThat(closed).isTrue();
    }

    @Test
    void failedReadKeepsTheCauseAndClosesTheStream() throws Exception {
        final IOException failure = new IOException("synthetic failure after one block");
        final AtomicBoolean closed = new AtomicBoolean();
        final InputStream input = new InputStream() {
            private int readBytes;

            @Override
            public int read() throws IOException {
                if (readBytes++ >= 8192) {
                    throw failure;
                }
                return '\n';
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
        assertThatThrownBy(() -> resolve(streamedMessage(input), ContentRequirement.NORMAL)).cause().isSameAs(failure);
        assertThat(closed).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ASCII body\r\n", "Café\r\n"})
    void unusedSerializationCapacityDoesNotBecomeBodyContent(final String body) throws Exception {
        final byte[] bytes = (HEADERS + body).getBytes(StandardCharsets.UTF_8);
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(bytes)) {
            @Override
            public void writeTo(final OutputStream output) throws IOException {
                // Uneven writes force spare capacity in the inspection buffer. Its unused zero bytes are not part of this email.
                output.write(bytes, 0, bytes.length - 1);
                output.write(bytes[bytes.length - 1]);
            }
        };
        assertThat(resolve(message, ContentRequirement.PRESERVE_ALL_BYTES)).isEqualTo(body.contains("é") ? "BODY=8BITMIME" : null);
    }

    @Test
    void serializedHeadersStillRejectMalformedUtf8() throws Exception {
        final byte[] bytes = ("X-Padding: " + "a".repeat(8190) + "\r\nSubject: é\r\n" + HEADERS).getBytes(StandardCharsets.UTF_8);
        final int accent = new String(bytes, StandardCharsets.ISO_8859_1).indexOf('\u00c3');
        bytes[accent + 1] = (byte) 'x';
        final MimeMessage message = FinalizedMimeMessage.fromExactMessageBytes(Session.getInstance(new Properties()), bytes);
        assertThatThrownBy(() -> resolve(message, ContentRequirement.PRESERVE_ALL_BYTES)).hasMessageContaining("not valid UTF-8");
    }

    private static String inspect(final byte[] body, final boolean finalized) throws Exception {
        if (!finalized) {
            return resolve(streamedMessage(new ByteArrayInputStream(body)), ContentRequirement.NORMAL);
        }
        final byte[] headers = HEADERS.getBytes(StandardCharsets.US_ASCII);
        final byte[] bytes = Arrays.copyOf(headers, headers.length + body.length);
        System.arraycopy(body, 0, bytes, headers.length, body.length);
        final FinalizedMimeMessage message = FinalizedMimeMessage.fromMessageBytes(Session.getInstance(new Properties()), bytes,
                FinalizedMimeMessage.ProtectionState.CONTENT_PROTECTED);
        final String extension = resolve(message, ContentRequirement.PRESERVE_PROTECTED_CONTENT);
        assertThat(message.getSerializedBytes()).containsExactly(bytes);
        return extension;
    }

    private static MimeMessage streamedMessage(final InputStream input) throws Exception {
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties())) {
            @Override
            public InputStream getInputStream() {
                return input;
            }
        };
        message.setFrom("sender@example.test");
        message.setHeader("Content-Type", "text/plain");
        message.setHeader("Content-Transfer-Encoding", "8bit");
        return message;
    }

    private static String resolve(final MimeMessage message, final ContentRequirement requirement) throws Exception {
        final Address[] recipients = {new InternetAddress("receiver@example.test")};
        final PreparedMail prepared = new PreparedMail(message, recipients, new DeliveryEnvelope(null, null), requirement);
        final SMTPTransport transport = new SMTPTransport(Session.getInstance(new Properties()), null) {
            @Override
            public boolean supportsExtension(final String extension) {
                return extension.equals("8BITMIME") || extension.equals("SMTPUTF8");
            }
        };
        return AngusContentNegotiation.resolveMailParameters(transport, prepared, "smtp", null, "test", recipients).getMailExtension();
    }
}
