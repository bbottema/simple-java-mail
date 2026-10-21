package org.simplejavamail.api.email;

import jakarta.activation.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.simplejavamail.MailException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.mailer.MailFailureKind.PREPARATION;

class AttachmentReadOwnershipTest {
    @ParameterizedTest
    @EnumSource(Reader.class)
    void convenienceReadersCloseTheirStream(final Reader reader) {
        final TrackingSource source = new TrackingSource(false, false, false);
        final AttachmentResource attachment = attachment(source);
        assertThat(source.opened).isZero();
        if (reader == Reader.BYTES) {
            assertThat((byte[]) reader.read(attachment)).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
        } else {
            assertThat(reader.read(attachment)).isEqualTo("hello");
        }
        assertThat(source.opened).isEqualTo(1);
        assertThat(source.closed).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(Reader.class)
    void openingFailureUsesThePreparationContract(final Reader reader) {
        final TrackingSource source = new TrackingSource(true, false, false);
        assertReadFailure(attachment(source), reader, source.openFailure);
        assertThat(source.opened).isEqualTo(1);
        assertThat(source.closed).isZero();
    }

    @ParameterizedTest
    @EnumSource(Reader.class)
    void readingFailureUsesThePreparationContractAndClosesTheStream(final Reader reader) {
        final TrackingSource source = new TrackingSource(false, true, false);
        assertReadFailure(attachment(source), reader, source.readFailure);
        assertThat(source.readFailure.getSuppressed()).isEmpty();
        assertThat(source.opened).isEqualTo(1);
        assertThat(source.closed).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(Reader.class)
    void readingFailureRemainsPrimaryWhenClosingAlsoFails(final Reader reader) {
        final TrackingSource source = new TrackingSource(false, true, true);
        assertReadFailure(attachment(source), reader, source.readFailure);
        assertThat(source.readFailure.getSuppressed()).containsExactly(source.closeFailure);
        assertThat(source.opened).isEqualTo(1);
        assertThat(source.closed).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(Reader.class)
    void closingFailureUsesThePreparationContractAfterSuccessfulReading(final Reader reader) {
        final TrackingSource source = new TrackingSource(false, false, true);
        assertReadFailure(attachment(source), reader, source.closeFailure);
        assertThat(source.opened).isEqualTo(1);
        assertThat(source.closed).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(Reader.class)
    void convenienceReadersDoNotDeclareCheckedIoFailures(final Reader reader) throws NoSuchMethodException {
        final Method method = reader == Reader.BYTES ? AttachmentResource.class.getMethod("readAllBytes")
                : reader == Reader.UTF8_TEXT ? AttachmentResource.class.getMethod("readAllData")
                : AttachmentResource.class.getMethod("readAllData", java.nio.charset.Charset.class);
        assertThat(method.getExceptionTypes()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(Reader.class)
    void applicationRuntimeFailuresKeepTheirIdentity(final Reader reader) {
        final TrackingSource source = new TrackingSource(false, false, false);
        source.applicationFailure = new IllegalStateException("application datasource failure");
        assertThatThrownBy(() -> reader.read(attachment(source))).isSameAs(source.applicationFailure);
        assertThat(source.closed).isZero();
    }

    @ParameterizedTest
    @EnumSource(Reader.class)
    void applicationErrorsKeepTheirIdentity(final Reader reader) {
        final TrackingSource source = new TrackingSource(false, false, false);
        source.applicationError = new AssertionError("application datasource error");
        assertThatThrownBy(() -> reader.read(attachment(source))).isSameAs(source.applicationError);
        assertThat(source.closed).isZero();
    }

    @Test
    void invalidCharsetRemainsAnArgumentErrorWithoutOpeningContent() {
        final TrackingSource source = new TrackingSource(false, false, false);
        assertThatThrownBy(() -> attachment(source).readAllData(null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("charset");
        assertThat(source.opened).isZero();
        assertThat(source.closed).isZero();
    }

    @Test
    void rawStreamAcquisitionDeclaresCheckedIoAndReplacesTheOldGetter() throws NoSuchMethodException {
        assertThat(AttachmentResource.class.getMethod("openStream").getExceptionTypes()).containsExactly(IOException.class);
        assertThatThrownBy(() -> AttachmentResource.class.getMethod("getDataSourceInputStream"))
                .isInstanceOf(NoSuchMethodException.class);
    }

    @Test
    void rawStreamOpeningFailureRetainsItsIdentity() {
        final TrackingSource source = new TrackingSource(true, false, false);
        assertThatThrownBy(() -> attachment(source).openStream()).isSameAs(source.openFailure);
        assertThat(source.opened).isEqualTo(1);
        assertThat(source.closed).isZero();
    }

    @Test
    void directlyRequestedStreamsRemainCallerOwned() throws IOException {
        final TrackingSource source = new TrackingSource(false, false, false);
        final InputStream stream = attachment(source).openStream();
        assertThat(stream).isSameAs(source.lastStream);
        assertThat(source.opened).isEqualTo(1);
        assertThat(source.closed).isZero();
        stream.close();
        assertThat(source.closed).isEqualTo(1);
    }

    @Test
    void rawStreamReadAndCloseFailuresRetainTheirIdentity() throws IOException {
        final TrackingSource source = new TrackingSource(false, true, true);
        final InputStream stream = attachment(source).openStream();
        assertThatThrownBy(stream::read).isSameAs(source.readFailure);
        assertThat(source.closed).isZero();
        assertThatThrownBy(stream::close).isSameAs(source.closeFailure);
        assertThat(source.closed).isEqualTo(1);
    }

    private static AttachmentResource attachment(final TrackingSource source) {
        return AttachmentResource.builder().withName("note.txt").withDataSource(source).build();
    }

    private static void assertReadFailure(final AttachmentResource attachment, final Reader reader, final IOException cause) {
        assertThatThrownBy(() -> reader.read(attachment)).isInstanceOfSatisfying(MailException.class, failure -> {
            assertThat(failure.getKind()).isEqualTo(PREPARATION);
            assertThat(failure.getCause()).isSameAs(cause);
            assertThat(failure.getSubmissionReceipt()).isEmpty();
        });
    }

    private enum Reader {
        BYTES, UTF8_TEXT, CHARSET_TEXT;

        private Object read(final AttachmentResource attachment) {
            switch (this) {
                case BYTES:
                    return attachment.readAllBytes();
                case UTF8_TEXT:
                    return attachment.readAllData();
                case CHARSET_TEXT:
                    return attachment.readAllData(StandardCharsets.UTF_8);
                default:
                    throw new AssertionError(this);
            }
        }
    }

    private static final class TrackingSource implements DataSource {
        private final IOException openFailure = new IOException("synthetic open failure");
        private final IOException readFailure = new IOException("synthetic read failure");
        private final IOException closeFailure = new IOException("synthetic close failure");
        private final boolean failOpen;
        private final boolean failRead;
        private final boolean failClose;
        private RuntimeException applicationFailure;
        private Error applicationError;
        private InputStream lastStream;
        private int opened;
        private int closed;

        private TrackingSource(final boolean failOpen, final boolean failRead, final boolean failClose) {
            this.failOpen = failOpen;
            this.failRead = failRead;
            this.failClose = failClose;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            opened++;
            if (applicationFailure != null) {
                throw applicationFailure;
            }
            if (applicationError != null) {
                throw applicationError;
            }
            if (failOpen) {
                throw openFailure;
            }
            lastStream = new InputStream() {
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
            return lastStream;
        }

        @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
        @Override public String getContentType() { return "text/plain"; }
        @Override public String getName() { return "note.txt"; }
    }
}
