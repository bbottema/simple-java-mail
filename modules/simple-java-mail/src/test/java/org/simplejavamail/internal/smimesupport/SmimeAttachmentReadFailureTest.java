package org.simplejavamail.internal.smimesupport;

import jakarta.activation.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import jakarta.activation.DataHandler;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.simplejavamail.internal.protection.ProtectionLayerResult;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmimeAttachmentReadFailureTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void openingFailureRemainsAnEncounteredInputFailure(final boolean encrypted) throws Exception {
        final IOException opening = new IOException("opening failed");
        final FailingSource source = new FailingSource(opening, null, null);
        assertFailure(source, encrypted, opening);
        assertThat(source.closed).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readingFailureRetainsItsSuppressedClosingFailure(final boolean encrypted) throws Exception {
        final IOException reading = new IOException("reading failed");
        final IOException closing = new IOException("closing failed");
        final FailingSource source = new FailingSource(null, reading, closing);
        assertFailure(source, encrypted, reading);
        assertThat(reading.getSuppressed()).containsExactly(closing);
        assertThat(source.closed).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closingFailurePreventsSuccessfulSmimeProcessing(final boolean encrypted) throws Exception {
        final IOException closing = new IOException("closing failed");
        final FailingSource source = new FailingSource(null, null, closing);
        assertFailure(source, encrypted, closing);
        assertThat(source.closed).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void interruptedResourceReadingIsNotAnOrdinarySignatureFailure(final boolean encrypted) throws Exception {
        final InterruptedIOException interrupted = new InterruptedIOException("S/MIME content read interrupted");
        final FailingSource source = new FailingSource(null, interrupted, null);
        final MimeMessage message = message(source, encrypted);
        try {
            assertThatThrownBy(() -> new SMIMESupport().openIncomingLayer(message.getSession(), message, null))
                    .isInstanceOf(CancellationException.class).hasRootCause(interrupted);
            assertThat(source.opened).isEqualTo(1);
            assertThat(source.closed).isEqualTo(1);
        } finally {
            Thread.interrupted();
        }
    }

    private static void assertFailure(final FailingSource source, final boolean encrypted, final IOException cause) throws Exception {
        final MimeMessage message = message(source, encrypted);
        final ProtectionLayerResult result = new SMIMESupport().openIncomingLayer(message.getSession(), message, null);
        assertThat(result.isRecognized()).isTrue();
        assertThat(result.isOpened()).isFalse();
        assertThat(result.getProcessingFailure()).isNotNull();
        assertThat(result.getInputReadFailure()).isSameAs(source.opening != null ? source.opening : source.reading);
        assertThat(result.getInputCloseFailure()).isSameAs(source.opening == null ? source.closing : null);
        Throwable original = result.getProcessingFailure();
        while (original.getCause() != null) original = original.getCause();
        assertThat(original).isSameAs(cause);
        assertThat(source.opened).isEqualTo(1);
    }

    private static MimeMessage message(final FailingSource source, final boolean encrypted) throws Exception {
        final MimeMessage message = new MimeMessage(Session.getInstance(new java.util.Properties()));
        message.setDataHandler(new DataHandler(source));
        message.setHeader("Content-Type", "application/pkcs7-mime; smime-type=" + (encrypted ? "enveloped-data" : "signed-data"));
        message.setHeader("Content-Transfer-Encoding", "base64");
        message.saveChanges();
        return message;
    }

    private static final class FailingSource implements DataSource {
        private final IOException opening;
        private final IOException reading;
        private final IOException closing;
        private int opened;
        private int closed;

        private FailingSource(final IOException opening, final IOException reading, final IOException closing) {
            this.opening = opening;
            this.reading = reading;
            this.closing = closing;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            opened++;
            if (opening != null) {
                throw opening;
            }
            return new InputStream() {
                private final InputStream content = new ByteArrayInputStream(new byte[] {1, 2, 3});

                @Override
                public int read() throws IOException {
                    if (reading != null) {
                        throw reading;
                    }
                    return content.read();
                }

                @Override
                public void close() throws IOException {
                    closed++;
                    if (closing != null) {
                        throw closing;
                    }
                }
            };
        }

        @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
        @Override public String getContentType() { return "application/pkcs7-mime"; }
        @Override public String getName() { return "smime.p7m"; }
    }
}
