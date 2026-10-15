package org.simplejavamail.internal.mailprovider.angus;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.spi.MailTransportProgress;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AngusContentCountingStreamTest {
    @Test
    void countsOnlySuccessfulWritesAndDoesNotFlushOrCloseTheProviderStream() throws Exception {
        final OutputStream output = mock(OutputStream.class);
        final MailTransportProgress progress = mock(MailTransportProgress.class);
        final AngusContentCountingStream stream = new AngusContentCountingStream(output, progress);
        final byte[] content = "José\n.\n".getBytes(UTF_8);
        stream.write(content);
        stream.write('x');
        stream.finishObservation();
        verify(output).write(content, 0, content.length);
        verify(output).write('x');
        verifyNoMoreInteractions(output);
        verify(progress).contentBytesWritten(content.length);
        verify(progress).contentBytesWritten(content.length + 1);
        verify(progress).contentTransferFinished(content.length + 1);
        doThrow(new IOException("write failed")).when(output).write('!');
        assertThatThrownBy(() -> stream.write('!')).isInstanceOf(IOException.class);
        assertThat(stream.getCount()).isEqualTo(content.length + 1);
        verifyNoMoreInteractions(progress);
    }

    @Test
    void reusesOneSerializationForRawInputTotalAndCanonicalSmtpSize() throws Exception {
        final AtomicInteger serializations = new AtomicInteger();
        final AtomicLong rawLength = new AtomicLong(-1);
        final byte[] raw = "Subject: café\n\n.line one\rline two".getBytes(UTF_8);
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties())) {
            @Override
            public void writeTo(final OutputStream output, final String[] ignored) throws IOException {
                serializations.incrementAndGet();
                output.write(raw);
            }
        };
        final long canonical = AngusMessageSize.measure(message, () -> false, rawLength::set);
        assertThat(serializations).hasValue(1);
        assertThat(rawLength).hasValue(raw.length);
        assertThat(canonical).isEqualTo(raw.length + 5); // Three normalized newlines and a final CRLF; no extra transparency dot.
        final ByteArrayOutputStream actual = new ByteArrayOutputStream();
        final AngusContentCountingStream stream = new AngusContentCountingStream(actual, null);
        stream.write(raw);
        assertThat(actual.toByteArray()).isEqualTo(raw);
        assertThat(stream.getCount()).isEqualTo(rawLength.get());
    }
}
