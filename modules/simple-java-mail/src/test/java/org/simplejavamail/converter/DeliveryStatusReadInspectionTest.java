package org.simplejavamail.converter;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.DeliveryAction;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.email.EmailSourceContent;
import org.simplejavamail.api.email.EmailSourcePart;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Properties;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryStatusReadInspectionTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    private static final String COMPLETE = "Reporting-MTA: dns; relay.example.test\r\n\r\n"
            + "Final-Recipient: rfc822; reader@example.test\r\nAction: failed\r\nStatus: 5.1.1\r\n\r\n";
    private static final String PREFIX = COMPLETE + "Final-Recipient: rfc822; second@example.test\r\nAction: fail";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emlReportWarningReferencesItsReceivedStatusPart(final boolean eager) {
        final String eml = "Content-Type: message/delivery-status\r\n\r\n" + COMPLETE.replace("Action: failed", "Action: sideways");
        final Email email = MAIL.converter().fromEml(eml).withFetchAttachmentData(eager).toEmail();

        assertSemanticWarningSources(email, email.getSourceData().getParts().get(0));
        assertThat(email.getDeliveryStatusReport().getRecipients()).singleElement()
                .satisfies(recipient -> assertThat(recipient.getAction()).isEqualTo(DeliveryAction.UNKNOWN));
    }

    @ParameterizedTest(name = "{0}, root={1}, eager={2}")
    @MethodSource("reportWarningInputs")
    void semanticWarningSourcesSurviveCopiesAndSerializationWithoutAnotherRead(final String type,
            final boolean root, final boolean eager) throws Exception {
        final ReportSource status = new ReportSource(type, COMPLETE.replace("Action: failed", "Action: sideways"), null, null);
        final ReportSource unrelated = new ReportSource("application/octet-stream", "ordinary attachment", null, null);
        final MimeMessage input;
        if (root) {
            input = message(status);
        } else {
            final MimeBodyPart text = new MimeBodyPart();
            text.setText("Readable explanation");
            final MimeMultipart multipart = new MimeMultipart("report; report-type=" + type.substring("message/".length()));
            multipart.addBodyPart(text);
            multipart.addBodyPart(part(status));
            multipart.addBodyPart(part(unrelated));
            input = new MimeMessage(Session.getInstance(new Properties()));
            input.setContent(multipart);
            input.setHeader("Content-Type", multipart.getContentType());
        }
        final Email email = convert(input, eager);
        final EmailSourcePart statusPart = reportPart(email, root);

        assertSemanticWarningSources(email, statusPart);
        assertThat(statusPart.getContent()).isNull();
        assertThat(email.getDeliveryStatusReport().getRecipients()).singleElement()
                .satisfies(recipient -> assertThat(recipient.getAction()).isEqualTo(DeliveryAction.UNKNOWN));
        assertThat(status.opens).hasValue(1);
        assertThat(status.closes).hasValue(1);
        assertThat(unrelated.opens).hasValue(!root && eager ? 1 : 0);

        final Email copy = MAIL.emailBuilder().copying(email).clearAttachments().withSubject("Edited").buildEmail();
        assertThat(reportPart(copy, root)).isSameAs(statusPart);
        assertSemanticWarningSources(copy, statusPart);
        final Email restored = roundTrip(copy);
        assertSemanticWarningSources(restored, reportPart(restored, root));
        assertThat(reportPart(restored, root).getContent()).isNull();
        assertThat(status.opens).hasValue(1);
        assertThat(unrelated.opens).hasValue(!root && eager ? 1 : 0);
    }

    static Stream<Arguments> reportWarningInputs() {
        return Stream.of("message/delivery-status", "message/global-delivery-status").flatMap(type ->
                Stream.of(false, true).flatMap(root -> Stream.of(false, true).map(eager -> Arguments.of(type, root, eager))));
    }

    @Test
    void ambiguousReportWarningReferencesItsKnownConflictingStatusParts() throws Exception {
        final ReportSource first = new ReportSource("message/delivery-status", COMPLETE, null, null);
        final ReportSource second = new ReportSource("message/delivery-status", COMPLETE, null, null);
        final MimeMultipart multipart = new MimeMultipart("report; report-type=delivery-status");
        multipart.addBodyPart(part(first));
        multipart.addBodyPart(part(second));
        final MimeMessage input = new MimeMessage(Session.getInstance(new Properties()));
        input.setContent(multipart);
        input.setHeader("Content-Type", multipart.getContentType());
        final Email email = convert(input);
        final EmailSourcePart container = email.getSourceData().getParts().get(0);

        assertThat(email.getDeliveryStatusReport().getRecipients()).isEmpty();
        assertSemanticWarningSources(email, container.getChildren().get(0), container.getChildren().get(1));
        assertThat(first.opens).hasValue(0);
        assertThat(second.opens).hasValue(0);
    }

    @Test
    void missingStatusPartWarningReferencesItsKnownReportContainer() throws Exception {
        final MimeBodyPart text = new MimeBodyPart();
        text.setText("Readable explanation");
        final MimeMultipart multipart = new MimeMultipart("report; report-type=delivery-status");
        multipart.addBodyPart(text);
        final MimeMessage input = new MimeMessage(Session.getInstance(new Properties()));
        input.setContent(multipart);
        input.setHeader("Content-Type", multipart.getContentType());
        final Email email = convert(input);

        assertThat(email.getPlainText()).isEqualTo("Readable explanation");
        assertSemanticWarningSources(email, email.getSourceData().getParts().get(0));
    }

    @Test
    void outOfOrderReportWarningReferencesTheRecoveredStatusPart() throws Exception {
        final ReportSource status = new ReportSource("message/delivery-status", COMPLETE, null, null);
        final MimeMultipart multipart = new MimeMultipart("report; report-type=delivery-status");
        multipart.addBodyPart(part(status));
        final MimeMessage input = new MimeMessage(Session.getInstance(new Properties()));
        input.setContent(multipart);
        input.setHeader("Content-Type", multipart.getContentType());
        final Email email = convert(input);

        assertThat(email.getDeliveryStatusReport().getRecipients()).hasSize(1);
        assertSemanticWarningSources(email, email.getSourceData().getParts().get(0).getChildren().get(0));
        assertThat(status.opens).hasValue(1);
    }

    private static EmailSourcePart reportPart(final Email email, final boolean root) {
        final EmailSourcePart part = email.getSourceData().getParts().get(0);
        return root ? part : part.getChildren().get(1);
    }

    private static void assertSemanticWarningSources(final Email email, final EmailSourcePart... parts) {
        assertThat(email.getProblems()).filteredOn(problem -> problem.getCode() == EmailProblem.Code.INTERPRETATION_WARNING)
                .singleElement().satisfies(problem -> {
                    assertThat(problem.getLocation()).isEqualTo("delivery-status[0]");
                    assertThat(problem.getSources()).containsExactly(parts);
                    for (int i = 0; i < parts.length; i++) assertThat(problem.getSources().get(i)).isSameAs(parts[i]);
                    assertThat(problem.getOrigin()).isSameAs(parts[0].getOrigin());
                });
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("interruptedStages")
    void typedInterruptionDoesNotBecomeSuccessfulOptionalInspection(final Stage stage, final Interrupt kind) throws Exception {
        final IOException failure = kind.failure();
        final ReportSource source = new ReportSource("message/delivery-status", PREFIX, stage, failure);
        final MimeMessage message = message(source);

        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertThatThrownBy(() -> convert(message)).isInstanceOf(CancellationException.class)
                .satisfies(thrown -> assertThat(hasCause(thrown, failure)).isTrue());
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertThat(source.opens).hasValue(1);
        assertThat(source.closes).hasValue(stage == Stage.ACQUIRE ? 0 : 1);
    }

    static Stream<Arguments> interruptedStages() {
        return Arrays.stream(Stage.values()).flatMap(stage -> Arrays.stream(Interrupt.values()).map(kind -> Arguments.of(stage, kind)));
    }

    @Test
    void interruptedCleanupCannotBecomeOptionalRecoveryAfterAnOrdinaryReadFailure() throws Exception {
        final ReportSource source = new ReportSource("message/delivery-status", PREFIX, Stage.READ, new IOException("status read failed"));
        source.closeFailure = new InterruptedIOException("cleanup was cancelled");
        final MimeMessage message = message(source);

        assertThatThrownBy(() -> convert(message)).isInstanceOf(CancellationException.class)
                .satisfies(thrown -> assertThat(hasCause(thrown, source.failure)).isTrue());
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertThat(source.opens).hasValue(1);
        assertThat(source.closes).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"message/delivery-status", "message/global-delivery-status"})
    void failedSelectedReportReadKeepsItsPrefixAndPartLinkedProblem(final String type) throws Exception {
        final IOException failure = new IOException("status read failed");
        final ReportSource source = new ReportSource(type, PREFIX, Stage.READ, failure);
        source.closeFailure = new IOException("status close also failed");
        final Email email = convert(message(source));

        assertThat(email.getSubject()).isEqualTo("Readable subject");
        assertThat(email.getDeliveryStatusReport().getRecipients()).singleElement()
                .satisfies(recipient -> assertThat(recipient.getAction()).isEqualTo(DeliveryAction.FAILED));
        final EmailSourcePart part = email.getSourceData().getParts().get(0);
        assertCaptured(part, PREFIX, false);
        assertThat(email.getProblems()).anySatisfy(problem -> {
            assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.CONTENT_READ_FAILED);
            assertThat(problem.getSources()).containsExactly(part);
            assertThat(problem.getOrigin()).isSameAs(part.getOrigin());
            assertThat(problem.getMessage()).contains("status read failed", "status close also failed");
        });
        assertThat(source.opens).hasValue(1);
        assertThat(source.closes).hasValue(1);

        // Editing ordinary content and serializing the historical inspection graph cannot reopen the borrowed source.
        final Email copy = MAIL.emailBuilder().copying(email).clearAttachments().withSubject("Edited").buildEmail();
        final Email restored = roundTrip(copy);
        assertCaptured(restored.getSourceData().getParts().get(0), PREFIX, false);
        assertThat(restored.getProblems()).anySatisfy(problem -> {
            assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.CONTENT_READ_FAILED);
            assertThat(problem.getSources()).containsExactly(restored.getSourceData().getParts().get(0));
        });
        assertThat(source.opens).hasValue(1);
        assertThat(source.closes).hasValue(1);
    }

    @Test
    void reportInterruptionRemainsAFailureWithOptionalProtectionModulesPresent() throws Exception {
        assertThat(ModuleLoader.openPgpModuleAvailable()).isTrue();
        assertThat(ModuleLoader.smimeModuleAvailable()).isTrue();
        final ReportSource source = new ReportSource("message/delivery-status", COMPLETE, Stage.ACQUIRE,
                new InterruptedIOException("cancelled first read"));
        source.failureOnOpen = 1;
        final AtomicInteger serializations = new AtomicInteger();
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties())) {
            @Override public void writeTo(final OutputStream output) throws IOException, MessagingException {
                serializations.incrementAndGet();
                super.writeTo(output);
            }
        };
        message.setDataHandler(new DataHandler(source));
        message.setHeader("Content-Type", source.getContentType());
        message.setSubject("Readable subject");
        message.setHeader("Content-Transfer-Encoding", "8bit");

        // The ordinary-MIME fast path from 3c2b63d7e (#704) skips protection serialization.
        // Interrupt the report parser's first selected acquisition.
        assertThatThrownBy(() -> MAIL.converter().fromMimeMessage(message).withFetchAttachmentData(false).toEmail())
                .isInstanceOf(CancellationException.class).hasCause(source.failure);
        assertThat(serializations).as("ordinary MIME must bypass protection serialization (#704)").hasValue(0);
        assertThat(source.opens).hasValue(1);
        assertThat(source.closes).hasValue(0);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void aFailedChildStatusReadLinksTheChildAndLeavesItsBorrowedResourceAndNeighborsUsable() throws Exception {
        final ReportSource status = new ReportSource("message/delivery-status", PREFIX, Stage.READ, new IOException("child status failed"));
        final ReportSource unrelated = new ReportSource("application/octet-stream", "unused", Stage.ACQUIRE,
                new IOException("must remain deferred"));
        final MimeBodyPart text = new MimeBodyPart();
        text.setText("Readable explanation");
        final MimeMultipart multipart = new MimeMultipart("report; report-type=delivery-status");
        multipart.addBodyPart(text);
        multipart.addBodyPart(part(status));
        multipart.addBodyPart(part(unrelated));
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setContent(multipart);
        message.setHeader("Content-Type", multipart.getContentType());
        final Email email = convert(message);
        final EmailSourcePart child = email.getSourceData().getParts().get(0).getChildren().get(1);

        assertCaptured(child, PREFIX, false);
        assertThat(email.getPlainText()).isEqualTo("Readable explanation");
        assertThat(email.getDeliveryStatusReport().getRecipients()).hasSize(1);
        assertThat(email.getProblems()).anySatisfy(problem -> {
            assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.CONTENT_READ_FAILED);
            assertThat(problem.getSources()).containsExactly(child);
        });
        assertThat(status.opens).hasValue(1);
        assertThat(unrelated.opens).hasValue(0);
        // The normal resource still delegates to the supplied source instead of becoming the retained partial snapshot.
        try (InputStream borrowed = email.getAttachments().get(0).openStream()) {
            assertThat(borrowed.read()).isEqualTo(PREFIX.getBytes(UTF_8)[0]);
        }
        assertThat(status.opens).hasValue(2);
        assertThat(status.closes).hasValue(2);
        assertCaptured(child, PREFIX, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"message/delivery-status", "message/global-delivery-status"})
    void closeFailureKeepsCompleteContentAndAnAffectedCloseProblem(final String type) throws Exception {
        final ReportSource source = new ReportSource(type, COMPLETE, Stage.CLOSE, new IOException("status close failed"));
        final Email email = convert(message(source));
        final EmailSourcePart part = email.getSourceData().getParts().get(0);

        assertCaptured(part, COMPLETE, true);
        assertThat(email.getDeliveryStatusReport().getRecipients()).hasSize(1);
        assertThat(email.getProblems()).anySatisfy(problem -> {
            assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.RESOURCE_CLOSE_FAILED);
            assertThat(problem.getSources()).containsExactly(part);
            assertThat(problem.getMessage()).contains("status close failed");
        });
        assertThat(source.opens).hasValue(1);
        assertThat(source.closes).hasValue(1);
    }

    @Test
    void ordinarySocketTimeoutStillRecoversCompletedFactsAndTheAcquiredPrefix() throws Exception {
        final ReportSource source = new ReportSource("message/delivery-status", PREFIX, Stage.READ,
                new SocketTimeoutException("status timed out"));
        final Email email = convert(message(source));

        assertThat(email.getDeliveryStatusReport().getRecipients()).hasSize(1);
        assertCaptured(email.getSourceData().getParts().get(0), PREFIX, false);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void acquisitionFailureHasAnAffectedPartWithoutInventedEmptyContent() throws Exception {
        final ReportSource source = new ReportSource("message/delivery-status", COMPLETE, Stage.ACQUIRE,
                new IOException("status could not be opened"));
        final Email email = convert(message(source));
        final EmailSourcePart part = email.getSourceData().getParts().get(0);

        assertThat(part.getContent()).isNull();
        assertThat(email.getProblems()).anySatisfy(problem -> {
            assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.CONTENT_READ_FAILED);
            assertThat(problem.getSources()).containsExactly(part);
            assertThat(problem.getMessage()).contains("status could not be opened");
        });
        assertThat(source.opens).hasValue(1);
        assertThat(source.closes).hasValue(0);
    }

    @Test
    void successfulReportInspectionDoesNotRetainAnotherArchiveOrFetchUnrelatedResources() throws Exception {
        final ReportSource status = new ReportSource("message/delivery-status", COMPLETE, null, null);
        final ReportSource unrelated = new ReportSource("application/octet-stream", "unused", Stage.ACQUIRE,
                new IOException("must remain deferred"));
        final MimeMultipart multipart = new MimeMultipart("report; report-type=delivery-status");
        multipart.addBodyPart(part(status));
        multipart.addBodyPart(part(unrelated));
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setContent(multipart);
        message.setHeader("Content-Type", multipart.getContentType());
        final Email email = convert(message);

        assertThat(email.getDeliveryStatusReport().getRecipients()).hasSize(1);
        assertThat(email.getSourceData().getParts().get(0).getChildren().get(0).getContent()).isNull();
        assertThat(email.getAttachments()).hasSize(2);
        assertThat(status.opens).hasValue(1);
        assertThat(status.closes).hasValue(1);
        assertThat(unrelated.opens).hasValue(0);
        email.getSourceData().getParts();
        email.getProblems();
        assertThat(status.opens).hasValue(1);
        assertThat(unrelated.opens).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void transferDecodedGlobalStatusKeepsItsContentWithoutADuplicateArchive(final boolean eager) throws Exception {
        final String status = COMPLETE.replace("reader@example.test", "josé@例え.test").replace("rfc822;", "utf-8;");
        final String eml = "Content-Type: message/global-delivery-status\r\nContent-Transfer-Encoding: base64\r\n\r\n"
                + Base64.getMimeEncoder().encodeToString(status.getBytes(UTF_8));
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(eml.getBytes(UTF_8)));
        final Email email = convert(message, eager);

        assertThat(email.getDeliveryStatusReport().getRecipients()).singleElement()
                .satisfies(recipient -> assertThat(recipient.getFinalRecipient().getAddress()).isEqualTo("josé@例え.test"));
        assertThat(email.getSourceData().getParts().get(0).getContent()).isNull();
        assertThat(email.getAttachments()).singleElement().satisfies(resource -> assertThat(resource.readAllBytes()).containsExactly(status.getBytes(UTF_8)));
        assertThat(email.getProblems()).isEmpty();
    }

    private static Email convert(final MimeMessage message) {
        return convert(message, false);
    }

    private static Email convert(final MimeMessage message, final boolean eager) {
        // The optional-module-absent runtime is supported; keep the selected MIME read separate from protection-module work.
        try (MockedStatic<ModuleLoader> modules = Mockito.mockStatic(ModuleLoader.class, Mockito.CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::smimeModuleAvailable).thenReturn(false);
            modules.when(ModuleLoader::openPgpModuleAvailable).thenReturn(false);
            return MAIL.converter().fromMimeMessage(message).withFetchAttachmentData(eager).toEmail();
        }
    }

    private static MimeMessage message(final DataSource source) throws Exception {
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setDataHandler(new DataHandler(source));
        message.setHeader("Content-Type", source.getContentType());
        message.setSubject("Readable subject");
        return message;
    }

    private static MimeBodyPart part(final DataSource source) throws Exception {
        final MimeBodyPart part = new MimeBodyPart();
        part.setDataHandler(new DataHandler(source));
        part.setHeader("Content-Type", source.getContentType());
        return part;
    }

    private static void assertCaptured(final EmailSourcePart part, final String expected, final boolean fullyCaptured) {
        final EmailSourceContent content = part.getContent();
        assertThat(content).isNotNull();
        assertThat(content.getRepresentation()).isEqualTo(EmailSourceContent.Representation.READER_BYTES);
        assertThat(content.readAllBytes()).containsExactly(expected.getBytes(UTF_8));
        assertThat(content.getSize()).isEqualTo(expected.getBytes(UTF_8).length);
        assertThat(content.isFullyCaptured()).isEqualTo(fullyCaptured);
    }

    private static boolean hasCause(final Throwable thrown, final Throwable original) {
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            if (current == original) return true;
        }
        return false;
    }

    private static Email roundTrip(final Email email) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(email); }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (Email) input.readObject();
        }
    }

    private enum Stage { ACQUIRE, READ, CLOSE }

    private enum Interrupt {
        TYPED, CLOSED, CAUSE, SUPPRESSED;

        IOException failure() {
            switch (this) {
                case TYPED: return new InterruptedIOException("cancelled status");
                case CLOSED: return new ClosedByInterruptException();
                case CAUSE: return new IOException("await stopped", new InterruptedException("cancelled wait"));
                case SUPPRESSED:
                    final IOException failure = new IOException("status failed");
                    failure.addSuppressed(new InterruptedIOException("cancelled cleanup"));
                    return failure;
                default: throw new AssertionError(this);
            }
        }
    }

    private static final class ReportSource implements DataSource {
        private final String type;
        private final byte[] bytes;
        private final Stage stage;
        private final IOException failure;
        private IOException closeFailure;
        private int failureOnOpen;
        private final AtomicInteger opens = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();

        private ReportSource(final String type, final String body, final Stage stage, final IOException failure) {
            this.type = type;
            this.bytes = body.getBytes(UTF_8);
            this.stage = stage;
            this.failure = failure;
        }

        @Override public InputStream getInputStream() throws IOException {
            opens.incrementAndGet();
            final Stage selectedStage = failureOnOpen == 0 || opens.get() == failureOnOpen ? stage : null;
            if (selectedStage == Stage.ACQUIRE) throw failure;
            final ByteArrayInputStream content = new ByteArrayInputStream(bytes);
            return new InputStream() {
                @Override public int read() throws IOException {
                    final int value = content.read();
                    if (value == -1 && selectedStage == Stage.READ) throw failure;
                    return value;
                }

                @Override public void close() throws IOException {
                    closes.incrementAndGet();
                    if (selectedStage == Stage.CLOSE) throw failure;
                    if (closeFailure != null) throw closeFailure;
                }
            };
        }

        @Override public OutputStream getOutputStream() throws IOException { throw new IOException("read only"); }
        @Override public String getContentType() { return type; }
        @Override public String getName() { return "status.dat"; }
    }
}
