package org.simplejavamail.converter;

import jakarta.activation.DataSource;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.converter.ConfiguredEmailConverter.EmlConversion;
import org.simplejavamail.api.converter.ConfiguredEmailConverter.MimeMessageConversion;
import org.simplejavamail.api.converter.EmailConversionResult;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.email.EmailSourceContent;
import org.simplejavamail.api.email.EmailSourceData;
import org.simplejavamail.api.email.EmailSourcePart;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.mailer.MailFailureKind;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.converter.internal.EmailConverterException;
import org.simplejavamail.internal.util.JakartaMailImplementation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArtifactConversionResultTest {

    private static final byte[] EML = ("From: sender@example.org\r\nTo: reader@example.org\r\nSubject: Source\r\n"
            + "Message-ID: <received@example.org>\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nbody\r\n").getBytes(UTF_8);
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

    @Test
    void supportsExistingArtifactTargetsWithoutWrappingEmail() throws Exception {
        final Session session = JakartaMailImplementation.createSession(new Properties());
        final EmailConversionResult<MimeMessage> parsed = mail.converter().fromEml(EML).withSession(session).toMimeMessageResult();
        assertThat(parsed.hasValue()).isTrue();
        assertThat(parsed.getFailure()).isNull();
        assertThat(parsed.getProblems()).isEmpty();
        assertThat(parsed.getValue().getSession()).isSameAs(session);
        assertThat(parsed.getSources()).hasSize(1);

        final Email email = mail.converter().fromEml(EML).toEmail();
        assertThat(mail.converter().fromEmail(email).toEmlResult().getValue()).contains("Subject: Source");
        assertThat(mail.converter().fromEmail(email).toEmlBytesResult().getValue()).isNotEmpty();
        assertThat(mail.converter().fromMimeMessage(parsed.getValue()).toEmlResult().getValue()).contains("Subject: Source");
        assertThat(mail.converter().fromMimeMessage(parsed.getValue()).toEmlBytesResult().getValue()).isNotEmpty();

        final EmailConversionResult<MimeMessage> mutable = mail.converter().fromEmail(email).toMimeMessageResult();
        mutable.getValue().setSubject("caller edit");
        assertThat(mutable.getValue().getSubject()).isEqualTo("caller edit");
        assertThat(email.getSubject()).isEqualTo("Source");
        assertThatThrownBy(() -> mutable.getProblems().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> mutable.getSources().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void outputObservationsAreNewWhileInputHistoryAndOriginsRemainUnchanged() throws Exception {
        final byte[] invalidDate = new String(EML, UTF_8).replace("Subject: Source", "Date: not-a-date\r\nSubject: Source").getBytes(UTF_8);
        final Email received = mail.converter().fromEml(invalidDate).toEmail();
        assertThat(received.getProblems()).isNotEmpty();
        final EmailProblem original = received.getProblems().get(0);
        final AtomicInteger opened = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        final Email edited = mail.emailBuilder().copying(received).fixingMessageId("<edited@example.org>")
                .withAttachment("failed.pdf", partialSource(opened, closed)).buildEmail();

        final EmailConversionResult<byte[]> result = mail.converter().fromEmail(edited).toEmlBytesResult();

        assertThat(result.hasValue()).isTrue();
        assertThat(result.getFailure()).isNull();
        assertThat(result.getProblems()).singleElement().satisfies(problem -> {
            assertThat(problem.getOperation()).isEqualTo(EmailProblem.Operation.CONVERSION);
            assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.CONTENT_READ_FAILED);
            assertThat(problem.getOrigin().getMessageId()).isEqualTo("<edited@example.org>");
            assertThat(problem.getOrigin().getReference()).isNotEqualTo(original.getOrigin().getReference());
            final EmailSourcePart part = (EmailSourcePart) problem.getSources().get(0);
            assertThat(part.getContent().getRepresentation()).isEqualTo(EmailSourceContent.Representation.READER_BYTES);
            assertThat(part.getContent().isFullyCaptured()).isFalse();
            assertThat(part.getContent().readAllBytes()).containsExactly(10, 20, 30);
            final byte[] defensive = part.getContent().readAllBytes();
            defensive[0] = 99;
            assertThat(part.getContent().readAllBytes()).containsExactly(10, 20, 30);
            try (InputStream stream = part.getContent().openStream()) {
                assertThat(stream.readAllBytes()).containsExactly(10, 20, 30);
            } catch (IOException unexpected) {
                throw new AssertionError(unexpected);
            }
        });
        assertThat(result.getSources()).anyMatch(source -> source == edited.getSourceData());
        assertThat(result.getSources()).anyMatch(source -> source.getRepresentation() == EmailSourceData.Representation.GENERATED);
        assertThat(edited.getProblems()).hasSameSizeAs(received.getProblems());
        assertThat(original.getOrigin().getMessageId()).isEqualTo("<received@example.org>");
        assertThat(edited.getAttachments()).hasSize(1);
        assertThat(mail.converter().fromEml(result.getValue()).toEmail().getAttachments()).isEmpty();
        assertThat(opened).hasValue(1);
        assertThat(closed).hasValue(1);
    }

    @Test
    void healthyHistoricalInputProblemsAreNotReemittedDuringExport() {
        final Email received = mail.converter().fromEml(new String(EML, UTF_8)
                .replace("Subject: Source", "Date: not-a-date\r\nSubject: Source")).toEmail();
        assertThat(received.getProblems()).isNotEmpty();

        final EmailConversionResult<byte[]> result = mail.converter().fromEmail(received).toEmlBytesResult();

        assertThat(result.hasValue()).isTrue();
        assertThat(result.getProblems()).isEmpty();
        assertThat(result.getSources()).containsExactly(received.getSourceData());
        assertThat(received.getProblems()).isNotEmpty();
    }

    @Test
    void aPartialOutputBufferIsEvidenceNotTheArtifactValue() {
        final IOException failure = new IOException("output framing cannot continue");
        final MimeMessage source = new MimeMessage(JakartaMailImplementation.createSession(new Properties())) {
            @Override public void writeTo(final OutputStream output) throws IOException {
                output.write("partial generated message".getBytes(UTF_8));
                throw failure;
            }
        };

        final EmailConversionResult<byte[]> result = mail.converter().fromMimeMessage(source).toEmlBytesResult();

        assertThat(result.hasValue()).isFalse();
        assertThat(result.getValue()).isNull();
        assertThat(result.getFailure()).isInstanceOf(EmailConverterException.class).hasCause(failure);
        assertThat(result.getProblems()).singleElement().satisfies(problem -> {
            assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.CONVERSION_FAILED);
            final EmailSourcePart part = (EmailSourcePart) problem.getSources().get(0);
            assertThat(part.getContent().isFullyCaptured()).isFalse();
            assertThat(new String(part.getContent().readAllBytes(), UTF_8)).isEqualTo("partial generated message");
        });
        assertThatThrownBy(() -> mail.converter().fromMimeMessage(source).toEmlBytes())
                .isInstanceOf(EmailConverterException.class).hasCause(failure);
    }

    @Test
    void rawPassthroughDoesNotRequestHeadersToPopulateItsResult() {
        final AtomicInteger headerReads = new AtomicInteger();
        final AtomicInteger writes = new AtomicInteger();
        final MimeMessage input = new MimeMessage(JakartaMailImplementation.createSession(new Properties())) {
            @Override public java.util.Enumeration<jakarta.mail.Header> getAllHeaders() {
                headerReads.incrementAndGet();
                throw new IllegalStateException("header acquisition was not requested");
            }
            @Override public void writeTo(final OutputStream output) throws IOException {
                writes.incrementAndGet();
                output.write(EML);
            }
        };

        assertThat(mail.converter().fromMimeMessage(input).toEmlBytes()).isEqualTo(EML);
        final EmailConversionResult<byte[]> bytes = mail.converter().fromMimeMessage(input).toEmlBytesResult();
        final EmailConversionResult<String> text = mail.converter().fromMimeMessage(input).toEmlResult();

        assertThat(bytes.getValue()).isEqualTo(EML);
        assertThat(bytes.getProblems()).isEmpty();
        assertThat(bytes.getSources()).isEmpty();
        assertThat(text.getValue()).isEqualTo(new String(EML, UTF_8));
        assertThat(headerReads).hasValue(0);
        assertThat(writes).hasValue(3);
    }

    @Test
    void closeFailureAfterFullAcquisitionKeepsUsableContentAndReportsCleanup() throws Exception {
        final AtomicInteger closed = new AtomicInteger();
        final DataSource source = dataSource(() -> new ByteArrayInputStream(new byte[] {4, 5, 6}) {
            @Override public void close() throws IOException {
                closed.incrementAndGet();
                throw new IOException("content stream close failed");
            }
        });
        final Email input = message().withAttachment("complete.bin", source).buildEmail();

        final EmailConversionResult<byte[]> result = mail.converter().fromEmail(input).toEmlBytesResult();

        assertThat(result.hasValue()).isTrue();
        assertThat(result.getFailure()).isNull();
        assertThat(result.getProblems()).singleElement().satisfies(problem -> {
            assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.RESOURCE_CLOSE_FAILED);
            assertThat(((EmailSourcePart) problem.getSources().get(0)).getContent()).isNull();
        });
        final Email restored = mail.converter().fromEml(result.getValue()).toEmail();
        assertThat(restored.getAttachments()).hasSize(1);
        try (InputStream data = restored.getAttachments().get(0).openStream()) {
            assertThat(data.readAllBytes()).containsExactly(4, 5, 6);
        }
        assertThat(closed).hasValue(1);
    }

    @Test
    void acquisitionFailureRetainsPrefixAndConsumesBorrowedInputWithoutClosingIt() {
        final AtomicInteger opened = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        final EmlConversion conversion;
        try {
            conversion = mail.converter().fromEml(partialSource(opened, closed).getInputStream());
        } catch (IOException unexpected) {
            throw new AssertionError(unexpected);
        }

        final EmailConversionResult<MimeMessage> result = conversion.toMimeMessageResult();

        assertThat(result.hasValue()).isFalse();
        assertThat(result.getValue()).isNull();
        assertThat(result.getFailure()).hasRootCauseMessage("source read failed");
        assertThat(result.getProblems()).singleElement().satisfies(problem -> {
            assertThat(problem.getOperation()).isEqualTo(EmailProblem.Operation.IMPORT);
            assertThat(((EmailSourcePart) problem.getSources().get(0)).getContent().readAllBytes()).containsExactly(10, 20, 30);
        });
        assertThat(closed).hasValue(0);
        assertThatThrownBy(conversion::toMimeMessageResult).hasMessageContaining("already been used");
        assertThatThrownBy(conversion::resetSession).hasMessageContaining("already been used");
    }

    @Test
    void invalidOptionsCanBeCorrectedBeforeResultConsumesTheSource() {
        final AtomicInteger written = new AtomicInteger();
        final MimeMessage source = new MimeMessage(JakartaMailImplementation.createSession(new Properties())) {
            @Override public void writeTo(final OutputStream output) throws IOException {
                written.incrementAndGet();
                output.write(EML);
            }
        };
        final MimeMessageConversion conversion = mail.converter().fromMimeMessage(source).withFetchAttachmentData(false);

        assertThatThrownBy(conversion::toEmlBytesResult).isInstanceOf(IllegalStateException.class);
        assertThat(written).hasValue(0);
        assertThat(conversion.resetFetchAttachmentData().toEmlBytesResult().getValue()).isEqualTo(EML);
        assertThat(written).hasValue(1);
    }

    @Test
    void resultSelectionDoesNotAddMimeEncodingProbeReads() {
        final AtomicInteger directReads = new AtomicInteger();
        final AtomicInteger resultReads = new AtomicInteger();
        final Email direct = message().withAttachment("readable.bin", countingSource(directReads)).buildEmail();
        final Email diagnostic = message().withAttachment("readable.bin", countingSource(resultReads)).buildEmail();

        mail.converter().fromEmail(direct).toMimeMessage();
        final EmailConversionResult<MimeMessage> result = mail.converter().fromEmail(diagnostic).toMimeMessageResult();

        assertThat(result.hasValue()).isTrue();
        assertThat(result.getProblems()).isEmpty();
        assertThat(resultReads.get()).isEqualTo(directReads.get());
    }

    @Test
    void outputStagingDoesNotReplayTheConvertersUnselectedImagePolicy(@TempDir final Path directory) {
        final AtomicInteger acquired = new AtomicInteger();
        final Email input = message().withHTMLText("<p>body</p><img src=\"missing.png\">")
                .withAttachment("good.bin", countingSource(acquired)).buildEmail();
        final SimpleJavaMail strictFactory = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap("strict image policy", Map.of(
                ConfigLoader.Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_DIR.key(), true,
                ConfigLoader.Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_CLASSPATH.key(), false,
                ConfigLoader.Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_URL.key(), false,
                ConfigLoader.Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_DIR.key(), directory.toString(),
                ConfigLoader.Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_MUSTBESUCCESFUL.key(), true)).load());

        final EmailConversionResult<MimeMessage> mime = strictFactory.converter().fromEmail(input).toMimeMessageResult();
        final EmailConversionResult<byte[]> artifact = strictFactory.converter().fromEmail(input).toEmlBytesResult();
        final byte[] staticArtifact = EmailConverter.emailToEMLByteArray(input);

        assertThat(mime.hasValue()).isTrue();
        assertThat(artifact.hasValue()).isTrue();
        assertThat(artifact.getProblems()).isEmpty();
        assertThat(mail.converter().fromEml(artifact.getValue()).toEmail().getHTMLText()).contains("missing.png");
        assertThat(mail.converter().fromEml(staticArtifact).toEmail().getHTMLText()).contains("missing.png");
        assertThat(input.getHTMLText()).contains("missing.png");
        assertThat(input.getEmbeddedImages()).isEmpty();
        assertThat(acquired).hasValue(3);
    }

    @Test
    void textArtifactReportsUtf8DecodeRecoveryAndRetainsOriginalSerializedBytes() throws Exception {
        final byte[] headers = ("From: sender@example.org\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(UTF_8);
        final byte[] binary = java.util.Arrays.copyOf(headers, headers.length + 2);
        binary[headers.length] = (byte) 0xff;
        binary[headers.length + 1] = (byte) 0xfe;
        final MimeMessage input = new MimeMessage(JakartaMailImplementation.createSession(new Properties()), new ByteArrayInputStream(binary));

        final EmailConversionResult<String> result = mail.converter().fromMimeMessage(input).toEmlResult();

        assertThat(result.hasValue()).isTrue();
        assertThat(result.getProblems()).singleElement().satisfies(problem -> {
            assertThat(problem.getOperation()).isEqualTo(EmailProblem.Operation.CONVERSION);
            assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.VALUE_NOT_DECODED);
            assertThat(problem.getSources()).singleElement().satisfies(element -> {
                final EmailSourcePart original = (EmailSourcePart) element;
                assertThat(original.getContent().isFullyCaptured()).isTrue();
                assertThat(original.getContent().readAllBytes()).endsWith((byte) 0xff, (byte) 0xfe);
            });
        });
        final EmailSourcePart original = result.getSources().stream().filter(source -> source.getRepresentation()
                == EmailSourceData.Representation.GENERATED).flatMap(source -> source.getParts().stream()).findFirst().orElseThrow();
        assertThat(original.getContent().isFullyCaptured()).isTrue();
        assertThat(original.getContent().readAllBytes()).endsWith((byte) 0xff, (byte) 0xfe);
    }

    @Test
    void byteArtifactDoesNotInventAUtf8ProblemForOpaqueMimeContent() throws Exception {
        final byte[] headers = "Content-Type: application/octet-stream\r\n\r\n".getBytes(UTF_8);
        final byte[] binary = java.util.Arrays.copyOf(headers, headers.length + 2);
        binary[headers.length] = (byte) 0xff;
        binary[headers.length + 1] = (byte) 0xfe;
        final MimeMessage input = new MimeMessage(JakartaMailImplementation.createSession(new Properties()), new ByteArrayInputStream(binary));

        final EmailConversionResult<byte[]> result = mail.converter().fromMimeMessage(input).toEmlBytesResult();

        assertThat(result.hasValue()).isTrue();
        assertThat(result.getValue()).endsWith((byte) 0xff, (byte) 0xfe);
        assertThat(result.getProblems()).isEmpty();
    }

    @Test
    void aValidUtf8ReplacementCharacterDoesNotIndicateDecodeFailure() throws Exception {
        final byte[] bytes = ("Content-Type: text/plain; charset=UTF-8\r\n\r\nValid literal \uFFFD\r\n").getBytes(UTF_8);
        final MimeMessage input = new MimeMessage(JakartaMailImplementation.createSession(new Properties()), new ByteArrayInputStream(bytes));

        final EmailConversionResult<String> result = mail.converter().fromMimeMessage(input).toEmlResult();

        assertThat(result.hasValue()).isTrue();
        assertThat(result.getValue()).contains("Valid literal \uFFFD");
        assertThat(result.getProblems()).isEmpty();
    }

    @Test
    void declaredPreencodedContentIsNotEncodedAgainByResourceStaging() throws Exception {
        final Email source = message().withAttachment(AttachmentResource.builder().withName("encoded.bin")
                .withData("AQID".getBytes(UTF_8), "application/octet-stream")
                .withPreEncodedContentTransferEncoding(ContentTransferEncoding.BASE_64).build()).buildEmail();

        final EmailConversionResult<byte[]> result = mail.converter().fromEmail(source).toEmlBytesResult();
        final Email restored = mail.converter().fromEml(result.getValue()).toEmail();

        assertThat(result.getProblems()).isEmpty();
        try (InputStream data = restored.getAttachments().get(0).openStream()) {
            assertThat(data.readAllBytes()).containsExactly(1, 2, 3);
        }
    }

    @Test
    void exactEmlStillUsesItsAuthoritativeBytes() {
        final byte[] bytes = new String(EML, UTF_8).replace("To: reader@example.org", "To: reader@example.org\r\nBcc: hidden@example.org").getBytes(UTF_8);
        final Email exact = mail.emailBuilder().startingFromExactEml(bytes).withEnvelopeRecipients("reader@example.org").buildEmail();

        final EmailConversionResult<byte[]> result = mail.converter().fromEmail(exact).toEmlBytesResult();

        assertThat(result.hasValue()).isTrue();
        assertThat(result.getValue()).isEqualTo(bytes);
        assertThat(result.getProblems()).isEmpty();
    }

    @Test
    void outlookOutputResultsRetainTheirImportedSources() {
        final Path source = Path.of(determineResourceFolder("simple-java-mail"), "test/resources/test-messages/simple email with TO and CC.msg");

        final EmailConversionResult<byte[]> bytes = mail.converter().fromOutlookMsg(source).toEmlBytesResult();
        final EmailConversionResult<String> text = mail.converter().fromOutlookMsg(source).toEmlResult();
        final EmailConversionResult<MimeMessage> mime = mail.converter().fromOutlookMsg(source).toMimeMessageResult();

        assertThat(bytes.hasValue()).isTrue();
        assertThat(text.hasValue()).isTrue();
        assertThat(mime.hasValue()).isTrue();
        assertThat(bytes.getSources()).anyMatch(view -> view.getFormat() == EmailSourceData.Format.OUTLOOK_MSG);
        assertThat(text.getSources()).anyMatch(view -> view.getFormat() == EmailSourceData.Format.OUTLOOK_MSG);
        assertThat(mime.getSources()).anyMatch(view -> view.getFormat() == EmailSourceData.Format.OUTLOOK_MSG);
    }

    @Test
    void unrelatedApplicationFailuresAreNotChangedIntoDiagnosticResults() {
        final IllegalStateException failure = new IllegalStateException("application source bug");
        final Email source = message().withAttachment("broken.pdf", dataSource(() -> { throw failure; })).buildEmail();

        assertThatThrownBy(() -> mail.converter().fromEmail(source).toEmlBytesResult()).isSameAs(failure);
    }

    @Test
    void knownRequestedProtectionFailureRemainsTheOriginalNoArtifactFailure() {
        final Email source = message().signWithDomainKey(invalidSigningKey()).buildEmail();

        final EmailConversionResult<byte[]> result = mail.converter().fromEmail(source).toEmlBytesResult();

        assertThat(result.hasValue()).isFalse();
        assertThat(result.getValue()).isNull();
        assertThat(result.getFailure().getKind()).isEqualTo(MailFailureKind.PREPARATION);
        assertThat(result.getFailure()).hasMessageContaining("DKIM");
        assertThat(result.getProblems()).singleElement().satisfies(problem -> {
            assertThat(problem.getOperation()).isEqualTo(EmailProblem.Operation.CONVERSION);
            assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.PROTECTION_FAILED);
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void applicationPreparationFailuresInSourceAccessAreStillThrown(final boolean metadataFailure) {
        final ApplicationFailure failure = new ApplicationFailure();
        final DataSource extension = new DataSource() {
            @Override public InputStream getInputStream() {
                if (!metadataFailure) throw failure;
                return new ByteArrayInputStream(new byte[] {1, 2, 3});
            }
            @Override public String getContentType() {
                if (metadataFailure) throw failure;
                return "application/octet-stream";
            }
            @Override public String getName() { return "application content"; }
            @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
        };
        final Email source = message().withAttachment("extension.bin", extension).signWithDomainKey(invalidSigningKey()).buildEmail();

        assertThatThrownBy(() -> mail.converter().fromEmail(source).toEmlBytesResult()).isSameAs(failure);
    }

    private static DkimConfig invalidSigningKey() {
        return DkimConfig.builder().dkimSigningDomain("example.org").dkimSelector("test")
                .dkimPrivateKeyData("-----BEGIN PRIVATE KEY-----\nAQID\n-----END PRIVATE KEY-----".getBytes(UTF_8)).build();
    }

    private static final class ApplicationFailure extends MailException {
        private static final long serialVersionUID = 1L;
        ApplicationFailure() { super(MailFailureKind.PREPARATION, "application source failed", null, null); }
    }

    private org.simplejavamail.api.email.EmailPopulatingBuilder message() {
        return mail.emailBuilder().startingBlank().from("sender@example.org")
                .withRecipients(mail.recipientBuilder().to(null, "reader@example.org")).withPlainText("body");
    }

    private static DataSource countingSource(final AtomicInteger opened) {
        return dataSource(() -> { opened.incrementAndGet(); return new ByteArrayInputStream(new byte[] {1, 2, 3}); });
    }

    private static DataSource partialSource(final AtomicInteger opened, final AtomicInteger closed) {
        return dataSource(() -> {
            opened.incrementAndGet();
            return new InputStream() {
                private boolean prefixReturned;
                @Override public int read(final byte[] bytes, final int offset, final int length) throws IOException {
                    if (prefixReturned) throw new IOException("source read failed");
                    prefixReturned = true;
                    System.arraycopy(new byte[] {10, 20, 30}, 0, bytes, offset, 3);
                    return 3;
                }
                @Override public int read() throws IOException { throw new IOException("source read failed"); }
                @Override public void close() { closed.incrementAndGet(); }
            };
        });
    }

    private static DataSource dataSource(final StreamFactory factory) {
        return new DataSource() {
            @Override public InputStream getInputStream() throws IOException { return factory.open(); }
            @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
            @Override public String getContentType() { return "application/octet-stream"; }
            @Override public String getName() { return "source"; }
        };
    }

    private interface StreamFactory {
        InputStream open() throws IOException;
    }
}
