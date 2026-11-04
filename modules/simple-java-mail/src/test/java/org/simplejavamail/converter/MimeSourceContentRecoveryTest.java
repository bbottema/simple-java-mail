package org.simplejavamail.converter;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.*;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import testutil.ConfigLoaderTestHelper;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Supplier;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class MimeSourceContentRecoveryTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    private static final String HEADERS = "Message-ID: <source@example.org>\r\nFrom: sender@example.org\r\nTo: reader@example.org\r\n";
    enum Route { FLUENT_EML, FLUENT_MIME, STATIC_EML, STATIC_MIME, EXACT }

    @Test
    void retainsReadableNeighborsWhenOneBodyCharsetIsUnavailable() {
        final String input = HEADERS + "Content-Type: multipart/mixed; boundary=parts\r\n\r\n"
                + "--parts\r\nContent-Type: text/plain; charset=X-NO-SUCH-CHARSET\r\n\r\nUninterpreted body\r\n"
                + "--parts\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n<p>Readable neighbor</p>\r\n"
                + "--parts\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=good.bin\r\n"
                + "Content-Transfer-Encoding: base64\r\n\r\nZ29vZA==\r\n--parts--\r\n";

        final Email email = assertDoesNotThrow(() -> MAIL.converter().fromEml(input).toEmail());

        assertThat(email.getPlainText()).isNull();
        assertThat(email.getHTMLText()).isEqualTo("<p>Readable neighbor</p>");
        assertThat(email.getAttachments()).hasSize(1);
        assertThat(email.getAttachments().get(0).readAllBytes()).isEqualTo(new byte[] {'g', 'o', 'o', 'd'});
        assertThat(email.getProblems()).hasSize(1);
    }

    @ParameterizedTest @EnumSource(Route.class)
    void keepsTheOriginalUndecodableBodyAndLinksItsProblem(final Route route) throws Exception {
        final Email email = convert(route, mixed("Content-Type: text/plain; charset=X-NO-SUCH-CHARSET\r\n\r\nOriginal body"));

        final EmailSourcePart failed = retainedParts(email.getSourceData()).get(0);
        assertThat(email.getHTMLText()).isEqualTo("<p>Readable neighbor</p>");
        assertThat(email.getPlainText()).isNull();
        assertThat(failed.getContent().getRepresentation()).isEqualTo(EmailSourceContent.Representation.SOURCE_BYTES);
        assertThat(failed.getContent().readAllBytes()).isEqualTo("Original body".getBytes(UTF_8));
        assertThat(failed.getContent().isFullyCaptured()).isTrue();
        assertThat(email.getProblems()).singleElement().satisfies(problem -> {
            assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.VALUE_NOT_DECODED);
            assertThat(problem.getOrigin().getMessageId()).isEqualTo("<source@example.org>");
            assertThat(problem.getSources()).containsExactly(failed);
            assertThat(problem.getSources().get(0)).isSameAs(failed);
        });
    }

    @Test
    void healthyCanonicalDecodingKeepsMetadataWithoutAnotherPayload() throws Exception {
        final Email email = MAIL.converter().fromEml(mixed("Content-Type: application/octet-stream\r\n"
                + "Content-Disposition: attachment; filename=good.bin\r\nContent-Transfer-Encoding: base64\r\n"
                + "X-Opaque: =?X-NO-SUCH-CHARSET?Q?not_interpreted?=\r\n\r\nZ29vZA==")).toEmail();

        assertThat(email.getAttachments()).hasSize(1);
        assertThat(email.getAttachments().get(0).readAllBytes()).isEqualTo("good".getBytes(UTF_8));
        assertThat(email.getProblems()).isEmpty();
        assertThat(retainedParts(email.getSourceData())).isEmpty();
        assertThat(allParts(email.getSourceData())).hasSize(3);
        assertThat(allParts(email.getSourceData()).stream().flatMap(part -> part.getHeaders().stream()))
                .anySatisfy(header -> {
                    assertThat(header.getName()).isEqualTo("X-Opaque");
                    assertThat(header.getRawValue()).contains("X-NO-SUCH-CHARSET");
                });
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {"text/plain", "text/html"})
    void concatenatedBodiesRetainTheirIndividualOriginalsWithoutProblems(final String type) {
        final String input = HEADERS + "Content-Type: multipart/mixed; boundary=parts\r\n\r\n"
                + "--parts\r\nContent-Type: " + type + "; charset=UTF-8\r\n\r\nFirst fragment\r\n"
                + "--parts\r\nContent-Type: " + type + "; charset=UTF-8\r\n\r\nSecond fragment\r\n--parts--\r\n";
        final Email email = MAIL.converter().fromEml(input).toEmail();

        assertThat(type.equals("text/plain") ? email.getPlainText() : email.getHTMLText()).isEqualTo("First fragmentSecond fragment");
        assertThat(email.getProblems()).isEmpty();
        assertThat(retainedParts(email.getSourceData())).extracting(MimeSourceContentRecoveryTest::originalText)
                .containsExactly("First fragment", "Second fragment");
    }

    @Test
    void anEmptyNeighborDoesNotRetainAnotherCopyOfTheUnchangedBodyValue() {
        final String input = HEADERS + "Content-Type: multipart/mixed; boundary=parts\r\n\r\n"
                + "--parts\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n\r\n"
                + "--parts\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nUnchanged value\r\n--parts--\r\n";

        final Email email = MAIL.converter().fromEml(input).toEmail();

        assertThat(email.getPlainText()).isEqualTo("Unchanged value");
        assertThat(email.getProblems()).isEmpty();
        assertThat(retainedParts(email.getSourceData())).extracting(MimeSourceContentRecoveryTest::originalText).containsExactly("");
    }

    @Test
    void overwrittenInlineContentRemainsAvailableWithoutArchivingTheRepresentedImage() throws Exception {
        final String input = HEADERS + "Content-Type: multipart/related; boundary=parts\r\n\r\n"
                + "--parts\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n<img src=\"cid:shared\">\r\n"
                + "--parts\r\nContent-Type: image/png\r\nContent-Disposition: inline; filename=first.png\r\nContent-ID: <shared>\r\n\r\nfirst\r\n"
                + "--parts\r\nContent-Type: image/png\r\nContent-Disposition: inline; filename=last.png\r\nContent-ID: <shared>\r\n\r\nlast\r\n--parts--\r\n";
        final Email email = MAIL.converter().fromEml(input).toEmail();

        assertThat(email.getEmbeddedImages()).hasSize(1);
        assertThat(email.getEmbeddedImages().get(0).readAllBytes()).isEqualTo("last".getBytes(UTF_8));
        assertThat(email.getAttachments()).isEmpty();
        assertThat(email.getProblems()).isEmpty();
        assertThat(retainedParts(email.getSourceData())).singleElement().satisfies(part ->
                assertThat(part.getContent().readAllBytes()).isEqualTo("first".getBytes(UTF_8)));
    }

    @Test
    void malformedUtf8FromASelectedBodyStreamIsNotSilentlyReplaced() throws Exception {
        final byte[] malformed = {(byte) 0xc3, 0x28};
        final DataSource source = new jakarta.mail.util.ByteArrayDataSource(malformed, "text/plain; charset=UTF-8");
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setDataHandler(new DataHandler(source) {
            @Override public Object getContent() throws IOException { return source.getInputStream(); }
        });
        message.setHeader("Content-Type", source.getContentType());
        message.setHeader("Message-ID", "<source@example.org>");

        final Email email = withoutProtection(() -> MAIL.converter().fromMimeMessage(message).toEmail());

        assertThat(email.getPlainText()).isNull();
        assertThat(email.getProblems()).singleElement().satisfies(problem ->
                assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.VALUE_NOT_DECODED));
        final EmailSourceContent captured = retainedParts(email.getSourceData()).get(0).getContent();
        assertThat(captured.isFullyCaptured()).isTrue();
        assertThat(captured.getRepresentation()).isEqualTo(EmailSourceContent.Representation.READER_BYTES);
        assertThat(captured.readAllBytes()).isEqualTo(malformed);
    }

    @Test
    void anAlreadyDecodedProviderStringIsNotDecodedOrValidatedAgain() throws Exception {
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(
                (HEADERS + "Content-Type: text/plain; charset=X-NO-SUCH-CHARSET\r\n\r\nsource").getBytes(UTF_8))) {
            @Override public Object getContent() { return "Decoded by the provider"; }
        };

        final Email email = MAIL.converter().fromMimeMessage(message).toEmail();

        assertThat(email.getPlainText()).isEqualTo("Decoded by the provider");
        assertThat(email.getProblems()).isEmpty();
        assertThat(retainedParts(email.getSourceData())).isEmpty();
    }

    @Test
    void theOwnedCalendarHandlerPreservesMalformedBytesRatherThanReplacementCharacters() throws Exception {
        final byte[] malformed = {(byte) 0xc3, 0x28};
        final DataSource source = new jakarta.mail.util.ByteArrayDataSource(malformed, "text/calendar; charset=UTF-8; method=REQUEST");
        final MimeMessage message = bodyMessage(new DataHandler(source), source.getContentType());

        final Email email = MAIL.converter().fromMimeMessage(message).toEmail();

        assertThat(email.getCalendarText()).isNull();
        assertThat(email.getProblems()).singleElement().satisfies(problem ->
                assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.VALUE_NOT_DECODED));
        final EmailSourceContent captured = retainedParts(email.getSourceData()).get(0).getContent();
        assertThat(captured.isFullyCaptured()).isTrue();
        assertThat(captured.getRepresentation()).isEqualTo(EmailSourceContent.Representation.READER_BYTES);
        assertThat(captured.readAllBytes()).isEqualTo(malformed);
    }

    @Test
    void theOwnedCalendarHandlerKeepsItsAcquiredPrefixAndClosesTheStream() throws Exception {
        final FailingSource source = new FailingSource(false, "text/calendar; charset=UTF-8; method=REQUEST");
        final MimeMessage message = bodyMessage(new DataHandler(source), source.getContentType());
        final Email email = withoutProtection(() -> MAIL.converter().fromMimeMessage(message).toEmail());

        assertThat(email.getCalendarText()).isNull();
        assertThat(source.reads).isEqualTo(1);
        assertThat(source.closes).isEqualTo(1);
        assertThat(email.getProblems()).hasSize(1);
        final EmailSourceContent captured = retainedParts(email.getSourceData()).get(0).getContent();
        assertThat(captured.isFullyCaptured()).isFalse();
        assertThat(captured.readAllBytes()).isEqualTo("prefix".getBytes(UTF_8));
        captured.readAllBytes();
        assertThat(source.reads).isEqualTo(1);
    }

    @Test
    void anUnsupportedAttachmentTransferEncodingKeepsRawBytesAndNeighbors() {
        final Email email = MAIL.converter().fromEml(mixed("Content-Type: application/octet-stream\r\n"
                + "Content-Disposition: attachment; filename=bad.bin\r\nContent-Transfer-Encoding: x-unavailable\r\n\r\nuntouched")).toEmail();

        assertThat(email.getAttachments()).isEmpty();
        assertThat(email.getHTMLText()).isEqualTo("<p>Readable neighbor</p>");
        assertThat(email.getProblems()).hasSize(1);
        final EmailSourceContent content = retainedParts(email.getSourceData()).get(0).getContent();
        assertThat(content.getRepresentation()).isEqualTo(EmailSourceContent.Representation.SOURCE_BYTES);
        assertThat(content.readAllBytes()).isEqualTo("untouched".getBytes(UTF_8));
    }

    @Test
    void namingRecoveryDoesNotDuplicateTheUsableAttachmentPayload() throws Exception {
        final String name = "=?X-NO-SUCH-CHARSET?Q?original_name?=";
        final Email email = MAIL.converter().fromEml(mixed("Content-Type: application/octet-stream\r\n"
                + "Content-Disposition: attachment; filename=\"" + name + "\"\r\n"
                + "Content-Transfer-Encoding: base64\r\n\r\nZ29vZA==")).toEmail();

        assertThat(email.getAttachments()).hasSize(1);
        assertThat(email.getAttachments().get(0).readAllBytes()).isEqualTo("good".getBytes(UTF_8));
        assertThat(retainedParts(email.getSourceData())).isEmpty();
        assertThat(email.getProblems()).singleElement().satisfies(problem -> {
            assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.VALUE_NOT_DECODED);
            assertThat(problem.getSources()).allMatch(source -> source instanceof EmailSourceHeader);
            assertThat(problem.getSources()).extracting(source -> ((EmailSourceHeader) source).getRawValue())
                    .contains("attachment; filename=\"" + name + "\"");
        });
    }

    @Test
    void anAddressOccurrenceProblemLinksOnlyItsOriginalOccurrence() {
        final String bad = ",";
        final String input = "Message-ID: <source@example.org>\r\nFrom: sender@example.org\r\n"
                + "To: good@example.org\r\nTo: " + bad + "\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nbody";

        final Email email = MAIL.converter().fromEml(input).toEmail();

        assertThat(email.getProblems()).singleElement().satisfies(problem -> {
            assertThat(problem.getLocation()).isEqualTo("To[1]");
            assertThat(problem.getSources()).singleElement().satisfies(source -> {
                assertThat(source).isInstanceOf(EmailSourceHeader.class);
                assertThat(((EmailSourceHeader) source).getRawValue()).isEqualTo(bad);
            });
        });
    }

    @Test
    void literalHeaderNamesWithBracketsDoNotBecomeAnInventedOccurrenceIndex() {
        final String value = "=?X-NO-SUCH-CHARSET?Q?original?=";
        final Email email = MAIL.converter().fromEml(HEADERS + "X-Field[12]: " + value
                + "\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nbody").toEmail();

        assertThat(email.getProblems()).singleElement().satisfies(problem ->
                assertThat(problem.getSources()).singleElement().satisfies(source -> {
                    assertThat(source).isInstanceOf(EmailSourceHeader.class);
                    assertThat(((EmailSourceHeader) source).getName()).isEqualTo("X-Field[12]");
                    assertThat(((EmailSourceHeader) source).getRawValue()).isEqualTo(value);
                }));
    }

    @Test
    void anUndecodableReaderNameWithoutAMimeNamingHeaderRemainsAProperty() throws Exception {
        final String original = "=?X-NO-SUCH-CHARSET?Q?reader_name?=";
        final jakarta.mail.util.ByteArrayDataSource source = new jakarta.mail.util.ByteArrayDataSource("good".getBytes(UTF_8), "application/octet-stream");
        source.setName(original);
        final MimeMessage message = bodyMessage(new DataHandler(source), source.getContentType());

        final Email email = withoutProtection(() -> MAIL.converter().fromMimeMessage(message).toEmail());

        assertThat(email.getAttachments()).hasSize(1);
        assertThat(email.getAttachments().get(0).readAllBytes()).isEqualTo("good".getBytes(UTF_8));
        assertThat(retainedParts(email.getSourceData())).isEmpty();
        final EmailSourceProperty property = allParts(email.getSourceData()).stream().flatMap(part -> part.getProperties().stream())
                .filter(value -> value.getName().equals("reader.name")).findFirst().orElseThrow();
        assertThat(property.getValue()).isEqualTo(original);
        assertThat(email.getProblems()).singleElement().satisfies(problem -> {
            assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.VALUE_NOT_DECODED);
            assertThat(problem.getSources()).containsExactly(property);
            assertThat(problem.getSources().get(0)).isSameAs(property);
        });
    }

    @Test
    void aMalformedNestedContainerDoesNotHideItsReadableSibling() {
        final Email email = MAIL.converter().fromEml(mixed("Content-Type: multipart/mixed\r\n\r\nUnframed original container")).toEmail();

        assertThat(email.getHTMLText()).isEqualTo("<p>Readable neighbor</p>");
        assertThat(email.getAttachments()).isEmpty();
        assertThat(email.getProblems()).hasSize(1);
        assertThat(retainedParts(email.getSourceData()).get(0).getContent().readAllBytes())
                .isEqualTo("Unframed original container".getBytes(UTF_8));
    }

    @Test
    void acquiredPrefixesRemainInspectableAfterCopiesAndSerializationWithoutAnotherRead() throws Exception {
        final FailingSource source = new FailingSource(false);
        final MimeMessage message = withAttachment(source);
        final Email imported = withoutProtection(() -> MAIL.converter().fromMimeMessage(message).toEmail());

        assertThat(imported.getAttachments()).isEmpty();
        assertThat(imported.getHTMLText()).isEqualTo("<p>Readable neighbor</p>");
        assertThat(source.reads).isEqualTo(1);
        assertThat(source.closes).isEqualTo(1);
        final EmailSourcePart failed = retainedParts(imported.getSourceData()).get(0);
        final EmailSourceContent captured = failed.getContent();
        assertThat(captured.getRepresentation()).isEqualTo(EmailSourceContent.Representation.READER_BYTES);
        assertThat(captured.isFullyCaptured()).isFalse();
        assertThat(captured.getSize()).isEqualTo(6);
        assertThat(captured.readAllBytes()).isEqualTo("prefix".getBytes(UTF_8));
        final byte[] defensive = captured.readAllBytes();
        defensive[0] = 0;
        try (InputStream stream = captured.openStream()) {
            assertThat(stream.readAllBytes()).isEqualTo("prefix".getBytes(UTF_8));
        }

        final Email copy = MAIL.emailBuilder().copying(imported).withSubject("Edited normal content")
                .fixingMessageId("<copy@example.org>").withAttachment("replacement.bin", "fixed".getBytes(UTF_8), "application/octet-stream")
                .buildEmail();
        assertThat(copy.getAttachments()).hasSize(1);
        assertThat(copy.getSourceData()).isSameAs(imported.getSourceData());
        assertThat(copy.getProblems()).hasSize(1);
        assertThat(copy.getProblems().get(0).getOrigin().getMessageId()).isEqualTo("<source@example.org>");
        assertThat(copy.getProblems().get(0).getSources().get(0)).isSameAs(failed);
        final EmailSourceData restored = restore(imported.getSourceData());
        assertThat(retainedParts(restored).get(0).getContent().readAllBytes()).isEqualTo("prefix".getBytes(UTF_8));
        assertThat(source.reads).isEqualTo(1);
        assertThat(source.closes).isEqualTo(1);
    }

    @Test
    void deferredAttachmentInspectionDoesNotReadOrCreateAProblem() throws Exception {
        final FailingSource source = new FailingSource(false);
        final MimeMessage message = withAttachment(source);
        final Email email = withoutProtection(() -> MAIL.converter().fromMimeMessage(message).withFetchAttachmentData(false).toEmail());

        assertThat(email.getAttachments()).hasSize(1);
        assertThat(source.reads).isZero();
        assertThat(email.getProblems()).isEmpty();
        assertThat(retainedParts(email.getSourceData())).isEmpty();
        assertThatThrownBy(() -> email.getAttachments().get(0).readAllBytes()).hasRootCauseMessage("content read failed");
        assertThat(email.getProblems()).isEmpty();
        assertThat(source.reads).isEqualTo(1);
    }

    @Test
    void closingFailureDoesNotHideFullyAcquiredAttachmentContent() throws Exception {
        final FailingSource source = new FailingSource(true);
        final MimeMessage message = withAttachment(source);
        final Email email = withoutProtection(() -> MAIL.converter().fromMimeMessage(message).toEmail());

        assertThat(source.reads).isEqualTo(1);
        assertThat(source.closes).isEqualTo(1);
        assertThat(email.getAttachments()).hasSize(1);
        assertThat(email.getAttachments().get(0).readAllBytes()).isEqualTo("prefix".getBytes(UTF_8));
        assertThat(retainedParts(email.getSourceData())).isEmpty();
        assertThat(email.getProblems()).singleElement().satisfies(problem -> {
            assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.RESOURCE_CLOSE_FAILED);
            assertThat(problem.getMessage()).contains("close failed");
        });
    }

    @Test
    void aFullyCapturedEmptyFailingBodyIsDifferentFromUncapturedContent() {
        final Email email = MAIL.converter().fromEml(mixed("Content-Type: text/plain; charset=X-NO-SUCH-CHARSET\r\n\r\n")).toEmail();

        final EmailSourceContent content = retainedParts(email.getSourceData()).get(0).getContent();
        assertThat(content).isNotNull();
        assertThat(content.isFullyCaptured()).isTrue();
        assertThat(content.getSize()).isZero();
        assertThat(content.readAllBytes()).isEmpty();
    }

    @Test
    void anUnsupportedCalendarCharsetAndMethodKeepSourceInsteadOfInvalidCalendarState() {
        final Email badCharset = MAIL.converter().fromEml(mixed("Content-Type: text/calendar; charset=X-NO-SUCH-CHARSET; method=REQUEST\r\n\r\nBEGIN:VCALENDAR\r\nEND:VCALENDAR")).toEmail();
        assertThat(badCharset.getCalendarText()).isNull();
        assertThat(badCharset.getHTMLText()).isEqualTo("<p>Readable neighbor</p>");
        assertThat(retainedParts(badCharset.getSourceData())).hasSize(1);

        final Email badMethod = MAIL.converter().fromEml(mixed("Content-Type: text/calendar; charset=UTF-8; method=UNSUPPORTED\r\n\r\nBEGIN:VCALENDAR\r\nEND:VCALENDAR")).toEmail();
        assertThat(badMethod.getCalendarText()).isNull();
        assertThat(retainedParts(badMethod.getSourceData())).hasSize(1);
        assertThat(badMethod.getProblems()).singleElement().satisfies(problem ->
                assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.CONTENT_NOT_REPRESENTED));
    }

    @Test
    void unrelatedApplicationRuntimeFailuresAreNotReclassifiedAsSourceFailures() throws Exception {
        final IllegalStateException failure = new IllegalStateException("application failure", new IOException("nested cause"));
        final MimeMessage source = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(
                (HEADERS + "Content-Type: text/plain; charset=UTF-8\r\n\r\nbody").getBytes(UTF_8))) {
            @Override public Object getContent() { throw failure; }
        };

        assertThatThrownBy(() -> MAIL.converter().fromMimeMessage(source).toEmail()).isSameAs(failure);
    }

    private static String mixed(final String firstPart) {
        return HEADERS + "Content-Type: multipart/mixed; boundary=parts\r\n\r\n--parts\r\n" + firstPart
                + "\r\n--parts\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n<p>Readable neighbor</p>\r\n--parts--\r\n";
    }

    private static MimeMessage withAttachment(final DataSource source) throws Exception {
        final MimeBodyPart readable = new MimeBodyPart();
        readable.setContent("<p>Readable neighbor</p>", "text/html; charset=UTF-8");
        final MimeBodyPart attachment = new MimeBodyPart();
        attachment.setDataHandler(new DataHandler(source));
        attachment.setHeader("Content-Type", source.getContentType());
        attachment.setDisposition(Part.ATTACHMENT);
        attachment.setFileName("source.bin");
        final MimeMultipart mixed = new MimeMultipart();
        mixed.addBodyPart(readable);
        mixed.addBodyPart(attachment);
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setHeader("Message-ID", "<source@example.org>");
        message.setFrom("sender@example.org");
        message.setRecipients(jakarta.mail.Message.RecipientType.TO, "reader@example.org");
        message.setContent(mixed);
        message.setHeader("Content-Type", mixed.getContentType());
        return message;
    }

    private static MimeMessage bodyMessage(final DataHandler handler, final String type) throws Exception {
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setDataHandler(handler);
        message.setHeader("Content-Type", type);
        message.setHeader("Message-ID", "<source@example.org>");
        return message;
    }

    private static Email withoutProtection(final Supplier<Email> conversion) {
        // Optional protection inspection has its own documented source reads; isolate the parser-owned read boundary here.
        try (MockedStatic<ModuleLoader> ignored = Mockito.mockStatic(ModuleLoader.class)) {
            return conversion.get();
        }
    }

    @SuppressWarnings("deprecation")
    private static Email convert(final Route route, final String eml) throws Exception {
        switch (route) {
            case STATIC_EML: return EmailConverter.emlToEmail(eml);
            case STATIC_MIME: return EmailConverter.mimeMessageToEmail(mime(eml));
            case FLUENT_MIME: return MAIL.converter().fromMimeMessage(mime(eml)).toEmail();
            case EXACT: return MAIL.emailBuilder().startingFromExactEml(eml.getBytes(UTF_8))
                    .withEnvelopeRecipients("reader@example.org").buildEmail();
            default: return MAIL.converter().fromEml(eml).toEmail();
        }
    }

    private static MimeMessage mime(final String eml) throws Exception {
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(eml.getBytes(UTF_8)));
    }

    private static List<EmailSourcePart> allParts(final EmailSourceData source) {
        final List<EmailSourcePart> result = new ArrayList<>();
        for (final EmailSourcePart part : source.getParts()) collect(part, result);
        return result;
    }

    private static void collect(final EmailSourcePart part, final List<EmailSourcePart> result) {
        result.add(part);
        for (final EmailSourcePart child : part.getChildren()) collect(child, result);
    }

    private static List<EmailSourcePart> retainedParts(final EmailSourceData source) {
        final List<EmailSourcePart> result = new ArrayList<>();
        for (final EmailSourcePart part : allParts(source)) {
            if (part.getContent() != null || part.getText() != null) result.add(part);
        }
        return result;
    }

    private static String originalText(final EmailSourcePart part) {
        return part.getText() != null ? part.getText() : new String(part.getContent().readAllBytes(), UTF_8);
    }

    private static EmailSourceData restore(final EmailSourceData source) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(source); }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (EmailSourceData) input.readObject();
        }
    }

    private static final class FailingSource implements DataSource {
        private final boolean failClosing;
        private final String contentType;
        private int reads;
        private int closes;

        private FailingSource(final boolean failClosing) { this(failClosing, "application/octet-stream"); }
        private FailingSource(final boolean failClosing, final String contentType) {
            this.failClosing = failClosing;
            this.contentType = contentType;
        }

        @Override public InputStream getInputStream() {
            reads++;
            return new InputStream() {
                private final byte[] prefix = "prefix".getBytes(UTF_8);
                private int position;
                @Override public int read() throws IOException {
                    if (position < prefix.length) return prefix[position++];
                    if (failClosing) return -1;
                    throw new IOException("content read failed");
                }
                @Override public void close() throws IOException {
                    closes++;
                    if (failClosing) throw new IOException("close failed");
                }
            };
        }

        @Override public OutputStream getOutputStream() throws IOException { throw new IOException("read only"); }
        @Override public String getContentType() { return contentType; }
        @Override public String getName() { return "source.bin"; }
    }
}
