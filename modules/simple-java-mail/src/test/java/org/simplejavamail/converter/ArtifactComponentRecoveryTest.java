package org.simplejavamail.converter;

import jakarta.activation.DataSource;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.config.ConfigLoader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArtifactComponentRecoveryTest {

    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completedArtifactKeepsReadableNeighborsWithoutMutatingInput(final boolean staticApi) {
        final AtomicInteger closed = new AtomicInteger();
        final IOException failure = new IOException("attachment storage became unavailable");
        final DataSource failed = source("failed.pdf", () -> new InputStream() {
            private boolean suppliedPrefix;

            @Override
            public int read(final byte[] buffer, final int offset, final int length) throws IOException {
                if (suppliedPrefix) throw failure;
                suppliedPrefix = true;
                buffer[offset] = 42;
                return 1;
            }

            @Override
            public int read() throws IOException { throw failure; }

            @Override
            public void close() { closed.incrementAndGet(); }
        });
        final Email input = message().withAttachment("failed.pdf", failed)
                .withAttachment("good.txt", "good content".getBytes(UTF_8), "text/plain").buildEmail();

        final byte[] bytes = staticApi ? EmailConverter.emailToEMLByteArray(input)
                : mail.converter().fromEmail(input).toEmlBytes();
        final Email output = mail.converter().fromEml(bytes).toEmail();

        assertThat(output.getPlainText()).contains("readable body");
        assertThat(output.getAttachments()).extracting(resource -> resource.getName()).containsExactly("good.txt");
        assertThat(input.getAttachments()).hasSize(2);
        assertThat(input.getAttachments().get(0).getDataSource()).isSameAs(failed);
        assertThat(closed).hasValue(1);
    }

    @Test
    void ordinaryMimeTargetDoesNotAcquireDeferredAttachments() {
        final AtomicInteger opened = new AtomicInteger();
        final Email input = message().withAttachment(AttachmentResource.builder().withName("deferred.pdf")
                .withContentTransferEncoding(ContentTransferEncoding.BASE_64).withDataSource(source("deferred.pdf", () -> {
            opened.incrementAndGet();
            return new ByteArrayInputStream(new byte[] {1, 2, 3});
        })).build()).buildEmail();

        final MimeMessage value = mail.converter().fromEmail(input).toMimeMessage();

        assertThat(value).isNotNull();
        assertThat(opened).hasValue(0);
    }

    @Test
    void unrelatedApplicationReadFailureStillPropagates() {
        final IllegalStateException failure = new IllegalStateException("application source bug");
        final Email input = message().withAttachment("broken.pdf", source("broken.pdf", () -> { throw failure; })).buildEmail();

        assertThatThrownBy(() -> mail.converter().fromEmail(input).toEmlBytes()).isSameAs(failure);
    }

    private org.simplejavamail.api.email.EmailPopulatingBuilder message() {
        return mail.emailBuilder().startingBlank().from("sender@example.org")
                .withRecipients(mail.recipientBuilder().to(null, "reader@example.org")).withPlainText("readable body");
    }

    private static DataSource source(final String name, final StreamFactory factory) {
        return new DataSource() {
            @Override public InputStream getInputStream() throws IOException { return factory.open(); }
            @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
            @Override public String getContentType() { return "application/pdf"; }
            @Override public String getName() { return name; }
        };
    }

    private interface StreamFactory {
        InputStream open() throws IOException;
    }
}
