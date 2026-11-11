package org.simplejavamail.converter;

import jakarta.mail.Multipart;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.SignatureStatus;
import org.simplejavamail.api.email.ValueAvailability;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompleteFailedInputOriginalTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    private static final byte[] INPUT = "Not a compound MSG file".getBytes(StandardCharsets.US_ASCII);

    @Test
    void fullyAcquiredUninterpretableInputRemainsACompleteOriginalWithoutInventingContent() throws Exception {
        final Email email = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(INPUT)).toEmail();
        assertThat(email.getProblems()).isNotEmpty();
        assertThat(email.getInspection().getPlainText().getAvailability()).isEqualTo(ValueAvailability.UNKNOWN);
        assertThat(email.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
        assertThat(email.getInspection().getOriginalMessage()).isNotNull();
        assertThat(email.getInspection().getOriginalMessage().readAllBytes()).isEqualTo(INPUT);
        assertThat(email.getInspection().getOriginalMessage().getDataSource().getContentType()).isEqualTo("application/vnd.ms-outlook");
        final byte[] exposed = email.getInspection().getOriginalMessage().readAllBytes();
        exposed[0] = 0;
        assertThat(email.getSourceData().getParts().get(0).getContent().readAllBytes()).isEqualTo(INPUT);

        final Email forward = MAIL.emailBuilder().forwardingOriginal(email).from("reader@example.org").to("archive@example.org")
                .fixingMessageId("<failed-input-archive@example.org>").buildEmail();
        final Multipart emitted = (Multipart) MAIL.converter().fromEmail(forward).toMimeMessage().getContent();
        try (InputStream payload = emitted.getBodyPart(emitted.getCount() - 1).getInputStream()) {
            assertThat(payload.readAllBytes()).isEqualTo(INPUT);
        }
    }

    @Test
    void aReadFailurePrefixIsEvidenceAndNeverAnOriginalForwardPayload() {
        final AtomicInteger reads = new AtomicInteger();
        final InputStream source = new InputStream() {
            @Override public int read(final byte[] bytes, final int offset, final int length) throws IOException {
                if (reads.getAndIncrement() != 0) throw new IOException("Synthetic acquisition failure");
                System.arraycopy(INPUT, 0, bytes, offset, INPUT.length);
                return INPUT.length;
            }
            @Override public int read() throws IOException { throw new IOException("Synthetic acquisition failure"); }
            @Override public void close() { throw new AssertionError("The caller owns this stream"); }
        };
        final Email email = MAIL.converter().fromOutlookMsg(source).toEmail();
        final int acquiredReads = reads.get();
        assertThat(email.getInspection().getOriginalMessage()).isNull();
        assertThat(email.getSourceData().getParts().get(0).getContent().isFullyCaptured()).isFalse();
        assertThat(email.getSourceData().getParts().get(0).getContent().readAllBytes()).isEqualTo(INPUT);
        assertThatThrownBy(() -> MAIL.emailBuilder().forwardingOriginal(email)).isInstanceOf(IllegalStateException.class);
        assertThat(reads).hasValue(acquiredReads);
    }
}
