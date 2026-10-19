package org.simplejavamail.api.email;

import jakarta.activation.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttachmentReadOwnershipTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void convenienceReadersCloseTheirStream(final boolean text) throws IOException {
        final TrackingSource source = new TrackingSource(false, false);
        final AttachmentResource attachment = new AttachmentResource("note.txt", source);
        assertThat(source.opened).isZero();
        if (text) {
            assertThat(attachment.readAllData()).isEqualTo("hello");
        } else {
            assertThat(attachment.readAllBytes()).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(source.opened).isEqualTo(1);
        assertThat(source.closed).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readingFailureRemainsPrimaryWhenClosingAlsoFails(final boolean text) {
        final TrackingSource source = new TrackingSource(true, true);
        final AttachmentResource attachment = new AttachmentResource("note.txt", source);
        assertThatThrownBy(() -> read(attachment, text)).isSameAs(source.readFailure)
                .satisfies(failure -> assertThat(failure.getSuppressed()).containsExactly(source.closeFailure));
        assertThat(source.closed).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closingFailureIsReportedAfterSuccessfulReading(final boolean text) {
        final TrackingSource source = new TrackingSource(false, true);
        assertThatThrownBy(() -> read(new AttachmentResource("note.txt", source), text)).isSameAs(source.closeFailure);
        assertThat(source.closed).isEqualTo(1);
    }

    @Test
    void directlyRequestedStreamsRemainCallerOwned() throws IOException {
        final TrackingSource source = new TrackingSource(false, false);
        final InputStream stream = new AttachmentResource("note.txt", source).getDataSourceInputStream();
        assertThat(source.closed).isZero();
        stream.close();
        assertThat(source.closed).isEqualTo(1);
    }

    private static void read(final AttachmentResource attachment, final boolean text) throws IOException {
        if (text) {
            attachment.readAllData(StandardCharsets.UTF_8);
        } else {
            attachment.readAllBytes();
        }
    }

    private static final class TrackingSource implements DataSource {
        private final IOException readFailure = new IOException("synthetic read failure");
        private final IOException closeFailure = new IOException("synthetic close failure");
        private final boolean failRead;
        private final boolean failClose;
        private int opened;
        private int closed;

        private TrackingSource(final boolean failRead, final boolean failClose) {
            this.failRead = failRead;
            this.failClose = failClose;
        }

        @Override
        public InputStream getInputStream() {
            opened++;
            return new InputStream() {
                private final byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
                private int position;

                @Override
                public int read() throws IOException {
                    if (failRead) {
                        throw readFailure;
                    }
                    return position == bytes.length ? -1 : bytes[position++];
                }

                @Override
                public void close() throws IOException {
                    closed++;
                    if (failClose) {
                        throw closeFailure;
                    }
                }
            };
        }

        @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
        @Override public String getContentType() { return "text/plain"; }
        @Override public String getName() { return "note.txt"; }
    }
}
