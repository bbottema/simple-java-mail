package org.simplejavamail.internal.smimesupport;

import jakarta.activation.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.internal.smimesupport.model.OriginalSmimeDetailsImpl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.email.OriginalSmimeDetails.SmimeMode.SIGNED;
import static org.simplejavamail.api.mailer.MailFailureKind.PREPARATION;

class SmimeAttachmentReadFailureTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void openingFailureRetainsTheSmimeBoundary(final boolean decrypt) {
        final IOException opening = new IOException("opening failed");
        final FailingSource source = new FailingSource(opening, null, null);
        assertFailure(source, decrypt, opening);
        assertThat(source.closed).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readingFailureRetainsItsSuppressedClosingFailure(final boolean decrypt) {
        final IOException reading = new IOException("reading failed");
        final IOException closing = new IOException("closing failed");
        final FailingSource source = new FailingSource(null, reading, closing);
        assertFailure(source, decrypt, reading);
        assertThat(reading.getSuppressed()).containsExactly(closing);
        assertThat(source.closed).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closingFailurePreventsSuccessfulSmimeProcessing(final boolean decrypt) {
        final IOException closing = new IOException("closing failed");
        final FailingSource source = new FailingSource(null, null, closing);
        assertFailure(source, decrypt, closing);
        assertThat(source.closed).isEqualTo(1);
    }

    private static void assertFailure(final FailingSource source, final boolean decrypt, final IOException cause) {
        final AttachmentResource attachment = AttachmentResource.builder().withName("smime.p7m").withDataSource(source).build();
        final SMIMESupport smime = new SMIMESupport();
        assertThat(smime.isSmimeAttachment(attachment)).as("the fixture enters S/MIME attachment processing").isTrue();
        assertThatThrownBy(() -> {
            if (decrypt) {
                smime.decryptAttachments(singletonList(attachment), null,
                        OriginalSmimeDetailsImpl.builder().smimeMode(SIGNED).build());
            } else {
                smime.getSignedByAddress(attachment);
            }
        }).isInstanceOfSatisfying(SmimeException.class, failure -> assertThat(failure.getKind()).isEqualTo(PREPARATION))
                .hasRootCause(cause);
        assertThat(source.opened).isEqualTo(1);
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
