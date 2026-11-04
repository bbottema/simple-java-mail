package org.simplejavamail.converter;

import jakarta.activation.DataSource;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.converter.EmailConversionResult;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.email.OriginalOpenPgpDetails;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.util.JakartaMailImplementation;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.util.Properties;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversionCancellationTest {

    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

    @ParameterizedTest
    @EnumSource(InterruptedRead.class)
    void interruptedInputAcquisitionIsNotReturnedAsAProblemOnlyEmail(final InterruptedRead kind) {
        final IOException original = kind.failure();
        try {
            assertThatThrownBy(() -> mail.converter().fromEml(failingInput(original, true)).toEmail())
                    .isInstanceOf(CancellationException.class).hasCause(original);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @EnumSource(InterruptedRead.class)
    void interruptedSelectedOutputResourceDoesNotYieldAPartialSuccessfulArtifact(final InterruptedRead kind) {
        final IOException original = kind.failure();
        final Email input = message().withAttachment("cancelled.bin", failingDataSource(original, true)).buildEmail();
        try {
            assertThatThrownBy(() -> mail.converter().fromEmail(input).toEmlBytesResult())
                    .isInstanceOf(CancellationException.class).hasCause(original);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @EnumSource(InterruptedRead.class)
    void interruptedForwardSerializationDoesNotSilentlyRemoveTheForward(final InterruptedRead kind) {
        final IOException original = kind.failure();
        final MimeMessage embedded = new MimeMessage(JakartaMailImplementation.createSession(new Properties())) {
            @Override public void writeTo(final OutputStream output) throws IOException {
                Thread.currentThread().interrupt();
                throw original;
            }
        };
        final Email input = mail.emailBuilder().forwarding(embedded).from("sender@example.org")
                .withRecipients(mail.recipientBuilder().to(null, "reader@example.org")).withPlainText("wrapper body").buildEmail();
        try {
            assertThatThrownBy(() -> mail.converter().fromEmail(input).toEmlBytesResult())
                    .isInstanceOf(CancellationException.class).hasCause(original);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void aNonInterruptedInputTimeoutRemainsAnInspectionProblem() {
        final Email inspected = mail.converter().fromEml(failingInput(new SocketTimeoutException("input timed out"), false)).toEmail();

        assertThat(inspected).isNotNull();
        assertThat(inspected.getProblems()).anySatisfy(problem -> assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.CONTENT_READ_FAILED));
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void aNonInterruptedResourceTimeoutRemainsRecoverable() {
        final Email input = message().withAttachment("timed-out.bin", failingDataSource(new SocketTimeoutException("resource timed out"), false))
                .buildEmail();

        final EmailConversionResult<byte[]> result = mail.converter().fromEmail(input).toEmlBytesResult();

        assertThat(result.hasValue()).isTrue();
        assertThat(result.getProblems()).singleElement().satisfies(problem -> assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.CONTENT_READ_FAILED));
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void anAwaitInterruptionWrappedAsIoIsCancelledAfterTheFlagWasClearedDuringInputRead() {
        final ClearedInterruptReader reader = new ClearedInterruptReader(false);
        try {
            assertThatThrownBy(() -> mail.converter().fromEml(reader).toEmail())
                    .isInstanceOf(CancellationException.class).hasCause(reader.ioFailure);
            assertThat(reader.interruptCleared).isTrue();
            assertThat(reader.ioFailure.getCause()).isSameAs(reader.interruption);
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void anAwaitInterruptionWrappedAsIoIsCancelledAfterTheFlagWasClearedDuringArtifactRead() {
        final ClearedInterruptReader reader = new ClearedInterruptReader(false);
        final Email input = message().withAttachment("awaiting.bin", readerSource(reader)).buildEmail();
        try {
            assertThatThrownBy(() -> mail.converter().fromEmail(input).toEmlBytesResult())
                    .isInstanceOf(CancellationException.class).hasCause(reader.ioFailure);
            assertThat(reader.interruptCleared).isTrue();
            assertThat(reader.ioFailure.getCause()).isSameAs(reader.interruption);
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anApplicationRuntimeWrapperAroundAwaitInterruptionRetainsItsIdentity(final boolean artifact) {
        final ClearedInterruptReader reader = new ClearedInterruptReader(true);
        final Email input = artifact ? message().withAttachment("extension.bin", readerSource(reader)).buildEmail() : null;
        try {
            assertThatThrownBy(() -> {
                if (artifact) mail.converter().fromEmail(input).toEmlBytesResult();
                else mail.converter().fromEml(reader).toEmail();
            }).isSameAs(reader.runtimeFailure);
            assertThat(reader.interruptCleared).isTrue();
            assertThat(reader.runtimeFailure.getCause()).isSameAs(reader.interruption);
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @EnumSource(MetadataCancellation.class)
    void optionalModuleAbsentProbeDoesNotHideOneShotMetadataCancellation(final MetadataCancellation kind) throws Exception {
        final MetadataProbeMessage message = new MetadataProbeMessage(kind, false);
        try (MockedStatic<ModuleLoader> modules = Mockito.mockStatic(ModuleLoader.class, Mockito.CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::openPgpModuleAvailable).thenReturn(false);
            modules.when(ModuleLoader::smimeModuleAvailable).thenReturn(false);
            assertThatThrownBy(() -> mail.converter().fromMimeMessage(message).toEmail())
                    .isInstanceOf(CancellationException.class).hasCause(message.metadataFailure);
            assertThat(message.failures).isEqualTo(1);
            assertThat(Thread.currentThread().isInterrupted()).isEqualTo(kind == MetadataCancellation.INTERRUPTED_IO);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void ordinaryOneShotOptionalProbeFailureStillKeepsUnknownFactsAndUsableContent() throws Exception {
        final MetadataProbeMessage message = new MetadataProbeMessage(null, true);
        try (MockedStatic<ModuleLoader> modules = Mockito.mockStatic(ModuleLoader.class, Mockito.CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::openPgpModuleAvailable).thenReturn(false);
            modules.when(ModuleLoader::smimeModuleAvailable).thenReturn(false);
            final Email inspected = mail.converter().fromMimeMessage(message).toEmail();
            assertThat(inspected.getPlainText()).contains("readable body");
            assertThat(inspected.getOriginalOpenPgpDetails().getOpenPgpMode()).isEqualTo(OriginalOpenPgpDetails.OpenPgpMode.UNKNOWN);
            assertThat(message.failures).isEqualTo(1);
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
        }
    }

    private org.simplejavamail.api.email.EmailPopulatingBuilder message() {
        return mail.emailBuilder().startingBlank().from("sender@example.org")
                .withRecipients(mail.recipientBuilder().to(null, "reader@example.org")).withPlainText("readable body");
    }

    private static DataSource failingDataSource(final IOException failure, final boolean interrupt) {
        return new DataSource() {
            @Override public InputStream getInputStream() { return failingInput(failure, interrupt); }
            @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
            @Override public String getContentType() { return "application/octet-stream"; }
            @Override public String getName() { return "selected resource"; }
        };
    }

    private static InputStream failingInput(final IOException failure, final boolean interrupt) {
        return new InputStream() {
            @Override public int read() throws IOException {
                if (interrupt) Thread.currentThread().interrupt();
                throw failure;
            }
        };
    }

    private static DataSource readerSource(final InputStream reader) {
        return new DataSource() {
            @Override public InputStream getInputStream() { return reader; }
            @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
            @Override public String getContentType() { return "application/octet-stream"; }
            @Override public String getName() { return "application reader"; }
        };
    }

    /** Exercises real JUC interrupt clearing inside the selected public conversion reader, not a guard implementation double. */
    private static final class ClearedInterruptReader extends InputStream {
        private final boolean runtime;
        private InterruptedException interruption;
        private IOException ioFailure;
        private IllegalStateException runtimeFailure;
        private boolean interruptCleared;

        ClearedInterruptReader(final boolean runtime) { this.runtime = runtime; }

        @Override public int read() throws IOException {
            Thread.currentThread().interrupt();
            try {
                new CountDownLatch(1).await();
                throw new AssertionError("An already interrupted await must not return normally");
            } catch (final InterruptedException actualInterruption) {
                interruption = actualInterruption;
                interruptCleared = !Thread.currentThread().isInterrupted();
                if (runtime) {
                    runtimeFailure = new IllegalStateException("application reader failed", actualInterruption);
                    throw runtimeFailure;
                }
                ioFailure = new IOException("reader await was interrupted", actualInterruption);
                throw ioFailure;
            }
        }
    }

    private enum MetadataCancellation { CLEARED_AWAIT, INTERRUPTED_IO }

    /** The failure is armed only after valid setup and occurs once, so later ordinary parsing can otherwise succeed. */
    private static final class MetadataProbeMessage extends MimeMessage {
        private final MetadataCancellation kind;
        private final boolean ordinary;
        private boolean armed;
        private int failures;
        private MessagingException metadataFailure;

        MetadataProbeMessage(final MetadataCancellation kind, final boolean ordinary) throws MessagingException {
            super(JakartaMailImplementation.createSession(new Properties()));
            this.kind = kind;
            this.ordinary = ordinary;
            setFrom("sender@example.org");
            setRecipients(jakarta.mail.Message.RecipientType.TO, "reader@example.org");
            setText("readable body", "UTF-8");
            saveChanges();
            armed = true;
        }

        @Override public String getContentType() throws MessagingException {
            if (armed) {
                armed = false;
                failures++;
                if (ordinary) metadataFailure = new MessagingException("one-shot optional metadata failure");
                else if (kind == MetadataCancellation.INTERRUPTED_IO) {
                    Thread.currentThread().interrupt();
                    metadataFailure = new MessagingException("metadata I/O interrupted", new InterruptedIOException("actual metadata interruption"));
                } else {
                    Thread.currentThread().interrupt();
                    try {
                        new CountDownLatch(1).await();
                        throw new AssertionError("An already interrupted await must not return normally");
                    } catch (final InterruptedException actualInterruption) {
                        metadataFailure = new MessagingException("metadata await interrupted", actualInterruption);
                    }
                }
                throw metadataFailure;
            }
            return super.getContentType();
        }
    }

    private enum InterruptedRead {
        INTERRUPTED_IO {
            @Override IOException failure() { return new InterruptedIOException("interrupted I/O"); }
        },
        CLOSED_BY_INTERRUPT {
            @Override IOException failure() { return new ClosedByInterruptException(); }
        };
        abstract IOException failure();
    }
}
