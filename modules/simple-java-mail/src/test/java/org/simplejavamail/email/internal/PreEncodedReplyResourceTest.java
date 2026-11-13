package org.simplejavamail.email.internal;

import jakarta.activation.DataSource;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.ReplyOptions;
import org.simplejavamail.api.email.config.OpenPgpEncryptionConfig;
import org.simplejavamail.api.mailer.MailFailureKind;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.email.ContentTransferEncoding.BASE_64;
import static org.simplejavamail.api.email.ContentTransferEncoding.QUOTED_PRINTABLE;

class PreEncodedReplyResourceTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    private static final String CID = "logo@example.org";
    private static final String HTML = "<p>Original image: <img src=\"cid:" + CID + "\"></p>";
    private static final String CUSTOM_QUOTE = "<section>%s</section>";
    private static final byte[] CONTENT = {0, 10, 13, 1, (byte) 255, 3};

    @ParameterizedTest(name = "{0}, {1}, {2}")
    @MethodSource("replyResources")
    void quotedPreencodedResourcesRetainDecodedContent(final ContentTransferEncoding encoding,
            final Representation representation, final ReplyRoute route) {
        final byte[] encoded = encoded(encoding);
        final CountingSource borrowed = new CountingSource(encoded);
        final CountingSource unrelated = new CountingSource(new byte[] {9});
        final OpenPgpEncryptionConfig unusedProtection = OpenPgpEncryptionConfig.builder()
                .addRecipientPublicKeyRing(new byte[] {1, 2, 3}).build();
        final EmailPopulatingBuilder sourceBuilder = original().withAttachment("unrelated.bin", unrelated)
                .encryptWithOpenPgp(unusedProtection);
        representation.add(sourceBuilder, encoded, borrowed, encoding);
        final Email original = sourceBuilder.buildEmail();
        final AttachmentResource sourceResource = representation.sourceResource(original);

        final Email reply = route.reply(original).from("bob@example.org").buildEmail();
        final AttachmentResource snapshot = reply.getEmbeddedImages().get(0);

        assertThat(reply.getEmbeddedImages()).hasSize(1);
        assertThat(snapshot.readAllBytes()).containsExactly(encoded);
        assertThat(snapshot.getPreEncodedContentTransferEncoding()).isEqualTo(encoding);
        assertThat(snapshot.getContentTransferEncoding()).isNull();
        assertThat(snapshot.getContentId()).isEqualTo(sourceResource.getContentId());
        assertThat(snapshot.getDescription()).isEqualTo(sourceResource.getDescription());
        assertThat(reply.getAttachments()).isEmpty();
        assertThat(reply.getHTMLText()).contains(HTML);
        assertThat(reply.getOpenPgpEncryptionConfig()).isNull();
        assertThat(original.getOpenPgpEncryptionConfig()).isSameAs(unusedProtection);
        assertThat(original.getHTMLText()).isEqualTo(HTML);
        assertThat(sourceResource.getPreEncodedContentTransferEncoding()).isEqualTo(encoding);
        assertThat(sourceResource.getContentTransferEncoding()).isNull();
        assertThat(borrowed.opens).hasValue(representation.borrowed ? 1 : 0);
        assertThat(borrowed.closes).hasValue(representation.borrowed ? 1 : 0);
        assertThat(unrelated.opens).hasValue(0);

        final MimeMessage rendered = MAIL.converter().fromEmail(reply).allowUnencryptedContent().toMimeMessage();
        final byte[] first = MAIL.converter().fromMimeMessage(rendered).toEmlBytes();
        final byte[] second = MAIL.converter().fromMimeMessage(rendered).toEmlBytes();
        assertThat(second).containsExactly(first);
        final Email imported = MAIL.converter().fromEml(first).toEmail();
        assertThat(imported.getEmbeddedImages()).singleElement()
                .satisfies(image -> assertThat(image.readAllBytes()).containsExactly(CONTENT));
        assertThat(snapshot.readAllBytes()).containsExactly(encoded);
        assertThat(borrowed.opens).hasValue(representation.borrowed ? 1 : 0);
        assertThat(borrowed.closes).hasValue(representation.borrowed ? 1 : 0);
        assertThat(unrelated.opens).hasValue(0);
        assertThat(sourceResource.readAllBytes()).containsExactly(encoded);
    }

    static Stream<Arguments> replyResources() {
        return Stream.of(
                Arguments.of(BASE_64, Representation.EMBEDDED_BYTES, ReplyRoute.REPLY),
                Arguments.of(BASE_64, Representation.EMBEDDED_SOURCE, ReplyRoute.REPLY_CUSTOM),
                Arguments.of(BASE_64, Representation.ATTACHMENT_BYTES, ReplyRoute.REPLY_ALL),
                Arguments.of(BASE_64, Representation.ATTACHMENT_SOURCE, ReplyRoute.REPLY_ALL_CUSTOM),
                Arguments.of(QUOTED_PRINTABLE, Representation.EMBEDDED_BYTES, ReplyRoute.REPLY_ALL_CUSTOM),
                Arguments.of(QUOTED_PRINTABLE, Representation.EMBEDDED_SOURCE, ReplyRoute.REPLY_ALL),
                Arguments.of(QUOTED_PRINTABLE, Representation.ATTACHMENT_BYTES, ReplyRoute.REPLY_CUSTOM),
                Arguments.of(QUOTED_PRINTABLE, Representation.ATTACHMENT_SOURCE, ReplyRoute.REPLY));
    }

    @ParameterizedTest
    @ValueSource(strings = {"No original quotation", "Progress 100%%", "%n", "%1$.0s"})
    void finalQuotedHtmlOmitsUnreferencedSourceImagesWithoutReadingThem(final String template) {
        final CountingSource borrowed = new CountingSource("AQID".getBytes(StandardCharsets.US_ASCII));
        final Email source = original().withPreEncodedEmbeddedImage(CID, borrowed, BASE_64)
                .encryptWithOpenPgp(OpenPgpEncryptionConfig.builder().addRecipientPublicKeyRing(new byte[] {1, 2, 3}).build()).buildEmail();
        final Email reply = MAIL.emailBuilder().replyingTo(source, ReplyOptions.builder().withQuotingTemplate(template).build())
                .clearPlainText().from("bob@example.org").buildEmail();
        final String expectedHtml = String.format(template, HTML);
        assertThat(reply.getHTMLText()).isEqualTo(expectedHtml);
        assertThat(reply.getEmbeddedImages()).isEmpty();
        assertThat(borrowed.opens).hasValue(0);
        final Email emitted = MAIL.converter().fromEml(MAIL.converter().fromEmail(reply).toEmlBytes()).toEmail();
        assertThat(emitted.getEmbeddedImages()).isEmpty();
        assertThat(emitted.getAttachments()).isEmpty();
        // MIME serialization canonicalizes line endings, including a platform-specific %n quotation.
        assertThat(emitted.getHTMLText()).isEqualTo(expectedHtml.replace("\r\n", "\n").replace("\n", "\r\n"));
        assertThat(borrowed.opens).hasValue(0);
        assertThat(borrowed.closes).hasValue(0);
    }

    @Test
    void replySnapshotNoLongerBorrowsTheEncodedSource() {
        final byte[] encoded = "AQID".getBytes(StandardCharsets.US_ASCII);
        final CountingSource borrowed = new CountingSource(encoded);
        final Email original = original().withPreEncodedEmbeddedImage(CID, borrowed, BASE_64).buildEmail();
        final Email reply = MAIL.emailBuilder().replyingTo(original).buildEmail();

        encoded[0] = 'B';
        assertThat(reply.getEmbeddedImages().get(0).readAllBytes()).containsExactly("AQID".getBytes(StandardCharsets.US_ASCII));
        final Email imported = MAIL.converter().fromEml(MAIL.converter().fromEmail(reply).toEmlBytes()).toEmail();
        assertThat(imported.getEmbeddedImages().get(0).readAllBytes()).containsExactly((byte) 1, (byte) 2, (byte) 3);
        assertThat(original.getEmbeddedImages().get(0).getDataSource()).isSameAs(borrowed);
        assertThat(original.getEmbeddedImages().get(0).getPreEncodedContentTransferEncoding()).isEqualTo(BASE_64);
        assertThat(borrowed.opens).hasValue(1);
        assertThat(borrowed.closes).hasValue(1);
    }

    @Test
    void snapshottingDoesNotAddAnInterpretationOfCallerSuppliedPreencodedData() {
        final CountingSource borrowed = new CountingSource("=ZZ".getBytes(StandardCharsets.US_ASCII));
        final Email original = original().withPreEncodedEmbeddedImage(CID, borrowed, QUOTED_PRINTABLE).buildEmail();

        final Email reply = MAIL.emailBuilder().replyingTo(original).buildEmail();
        assertThat(reply.getEmbeddedImages().get(0).readAllBytes()).containsExactly("=ZZ".getBytes(StandardCharsets.US_ASCII));
        assertThat(reply.getEmbeddedImages().get(0).getPreEncodedContentTransferEncoding()).isEqualTo(QUOTED_PRINTABLE);
        assertThat(borrowed.opens).hasValue(1);
        assertThat(borrowed.closes).hasValue(1);
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("sourceFailures")
    void preencodedSnapshotsRetainReadAndCloseFailures(final ContentTransferEncoding encoding, final SourceFailure kind) {
        final IOException readFailure = new IOException("Encoded source read failed");
        final IOException closeFailure = new IOException("Encoded source close failed");
        final AtomicInteger closes = new AtomicInteger();
        final DataSource source = new DataSource() {
            @Override public InputStream getInputStream() throws IOException {
                if (kind == SourceFailure.OPEN) throw readFailure;
                return new InputStream() {
                    private int position;
                    @Override public int read() throws IOException {
                        if (kind == SourceFailure.READ || kind == SourceFailure.READ_AND_CLOSE) throw readFailure;
                        final byte[] bytes = encoded(encoding);
                        return position == bytes.length ? -1 : bytes[position++] & 0xff;
                    }
                    @Override public void close() throws IOException {
                        closes.incrementAndGet();
                        if (kind == SourceFailure.CLOSE || kind == SourceFailure.READ_AND_CLOSE) throw closeFailure;
                    }
                };
            }
            @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
            @Override public String getContentType() { return "image/png"; }
            @Override public String getName() { return "encoded.png"; }
        };
        final Email original = original().withPreEncodedEmbeddedImage(CID, source, encoding).buildEmail();
        final IOException expected = kind == SourceFailure.CLOSE ? closeFailure : readFailure;

        assertThatThrownBy(() -> MAIL.emailBuilder().replyingTo(original)).isInstanceOf(MailException.class).hasCause(expected)
                .satisfies(failure -> assertThat(((MailException) failure).getKind()).isEqualTo(MailFailureKind.CONVERSION));
        assertThat(closes).hasValue(kind == SourceFailure.OPEN ? 0 : 1);
        if (kind == SourceFailure.READ_AND_CLOSE) assertThat(readFailure.getSuppressed()).containsExactly(closeFailure);
    }

    static Stream<Arguments> sourceFailures() {
        return Arrays.stream(SourceFailure.values()).map(kind -> Arguments.of(BASE_64, kind));
    }

    private static EmailPopulatingBuilder original() {
        return MAIL.emailBuilder().startingBlank().from("alice@example.org").to("bob@example.org")
                .cc("carol@example.org").withSubject("Quoted image").withHTMLText(HTML);
    }

    private static byte[] encoded(final ContentTransferEncoding encoding) {
        return encoding == BASE_64 ? Base64.getEncoder().encode(CONTENT)
                : "=00=0A=0D=01=FF=03".getBytes(StandardCharsets.US_ASCII);
    }

    private enum ReplyRoute {
        REPLY, REPLY_CUSTOM, REPLY_ALL, REPLY_ALL_CUSTOM;

        EmailPopulatingBuilder reply(final Email source) {
            switch (this) {
                case REPLY: return MAIL.emailBuilder().replyingTo(source);
                case REPLY_CUSTOM: return MAIL.emailBuilder().replyingTo(source, CUSTOM_QUOTE);
                case REPLY_ALL: return MAIL.emailBuilder().replyingToAll(source);
                case REPLY_ALL_CUSTOM: return MAIL.emailBuilder().replyingToAll(source, CUSTOM_QUOTE);
                default: throw new AssertionError(this);
            }
        }
    }

    private enum SourceFailure { OPEN, READ, CLOSE, READ_AND_CLOSE }

    private enum Representation {
        EMBEDDED_BYTES(false, false), EMBEDDED_SOURCE(true, false), ATTACHMENT_BYTES(false, true), ATTACHMENT_SOURCE(true, true);

        private final boolean borrowed;
        private final boolean attachment;

        Representation(final boolean borrowed, final boolean attachment) {
            this.borrowed = borrowed;
            this.attachment = attachment;
        }

        void add(final EmailPopulatingBuilder builder, final byte[] encoded, final DataSource source,
                final ContentTransferEncoding encoding) {
            if (!attachment) {
                if (borrowed) builder.withPreEncodedEmbeddedImage(CID, source, encoding);
                else builder.withPreEncodedEmbeddedImage(CID, encoded, "image/png", encoding);
            } else {
                final AttachmentResource.Builder resource = AttachmentResource.builder().withName("quoted.png")
                        .withContentId(CID).withDescription("Quoted logo").withPreEncodedContentTransferEncoding(encoding);
                builder.withAttachment((borrowed ? resource.withDataSource(source) : resource.withData(encoded, "image/png")).build());
            }
        }

        AttachmentResource sourceResource(final Email source) {
            return attachment ? source.getAttachments().get(1) : source.getEmbeddedImages().get(0);
        }
    }

    private static final class CountingSource implements DataSource {
        private final byte[] content;
        private final AtomicInteger opens = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();

        private CountingSource(final byte[] content) { this.content = content; }

        @Override public InputStream getInputStream() {
            opens.incrementAndGet();
            return new ByteArrayInputStream(content) {
                @Override public void close() { closes.incrementAndGet(); }
            };
        }
        @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
        @Override public String getContentType() { return "image/png"; }
        @Override public String getName() { return "borrowed.png"; }
    }
}
