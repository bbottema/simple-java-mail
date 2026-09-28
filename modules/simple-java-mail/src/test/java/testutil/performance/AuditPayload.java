package testutil.performance;

import jakarta.activation.DataSource;
import jakarta.activation.FileDataSource;
import jakarta.mail.util.ByteArrayDataSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.recipient.RecipientBuilder;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Date;
import java.util.Random;
import java.util.concurrent.atomic.LongAdder;

/** Stable content is reused; ordinary MIME generation and DKIM signing still occur for every send. */
final class AuditPayload {
    final Email email;
    final LongAdder openedStreams = new LongAdder();
    final LongAdder readBytes = new LongAdder();

    AuditPayload(final SimpleJavaMail factory, final String kind, final int attachmentBytes, final Path directory) throws Exception {
        // Match the existing DKIM test fixture's DNS-free signing path; DNS/key publication is outside this content-cost audit.
        final String sender = "dkim".equals(kind) ? "sender@supersecret-testing-domain.com" : "sender@example.test";
        final EmailPopulatingBuilder builder = factory.emailBuilder().startingBlank().from(sender)
                .withRecipients(RecipientBuilder.to(null, "receiver@example.test"))
                .withSubject("Synthetic order confirmation: caf\u00e9")
                .withPlainText("A synthetic order confirmation. No customer content.\r\n")
                .fixingMessageId("<performance-audit@example.test>").fixingSentDate(new Date(0));
        if (!"small".equals(kind)) {
            final byte[] content = new byte[attachmentBytes];
            new Random(749).nextBytes(content);
            final DataSource source;
            if ("file".equals(kind)) {
                final Path file = directory.resolve("synthetic-attachment.bin");
                Files.write(file, content);
                source = new FileDataSource(file.toFile());
            } else {
                source = new ByteArrayDataSource(content, "application/octet-stream");
            }
            builder.withAttachment("synthetic-attachment.bin", new CountingDataSource(source));
        }
        if ("dkim".equals(kind)) {
            final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            builder.signWithDomainKey(DkimConfig.builder().dkimPrivateKeyData(generator.generateKeyPair().getPrivate().getEncoded())
                    .dkimSigningDomain("supersecret-testing-domain.com").dkimSelector("audit").build());
        }
        final Email composed = builder.buildEmail();
        email = "exact".equals(kind)
                ? factory.emailBuilder().startingFromExactEml(EmailConverter.mimeMessageToEMLByteArray(EmailConverter.emailToMimeMessage(composed)))
                        .withEnvelopeSender("sender@example.test").withEnvelopeRecipients("receiver@example.test").buildEmail()
                : composed;
    }

    /** Counts at stream close, not through an atomic operation for every byte or buffer read. */
    private final class CountingDataSource implements DataSource {
        private final DataSource delegate;

        private CountingDataSource(final DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            openedStreams.increment();
            return new FilterInputStream(delegate.getInputStream()) {
                private long consumed;
                private boolean counted;

                @Override
                public int read() throws IOException {
                    final int value = in.read();
                    if (value >= 0) { consumed++; }
                    return value;
                }

                @Override
                public int read(final byte[] bytes, final int offset, final int length) throws IOException {
                    final int read = in.read(bytes, offset, length);
                    if (read > 0) { consumed += read; }
                    return read;
                }

                @Override
                public void close() throws IOException {
                    if (!counted) {
                        counted = true;
                        readBytes.add(consumed);
                    }
                    super.close();
                }
            };
        }

        @Override public OutputStream getOutputStream() throws IOException { return delegate.getOutputStream(); }
        @Override public String getContentType() { return delegate.getContentType(); }
        @Override public String getName() { return delegate.getName(); }
    }
}
