package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSendProgress;
import org.simplejavamail.api.mailer.MailSendProgressStage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.mailer.internal.MailSendProgressDispatcherTest.take;

@Timeout(15)
class MailSendProgressRecorderTest {
    @TempDir java.nio.file.Path legacyClasses;

    @Test
    void outcomesSerializedBeforeObservationIdsRemainReadable() throws Exception {
        // Reproduce the old stream descriptor in an isolated loader: same implementation name/UID, but no observationId field at all.
        final String className = "org.simplejavamail.mailer.internal.MailSendOutcomeImpl";
        final java.nio.file.Path source = legacyClasses.resolve("MailSendOutcomeImpl.java");
        java.nio.file.Files.writeString(source, "package org.simplejavamail.mailer.internal; "
                + "public final class MailSendOutcomeImpl implements java.io.Serializable {"
                + "private static final long serialVersionUID = 1L;"
                + "private final java.time.Instant requestedAt = java.time.Instant.EPOCH;"
                + "private final java.time.Instant completedAt = java.time.Instant.EPOCH;"
                + "private final Throwable failure = new IllegalStateException(\"old failed attempt\"); }");
        assertThat(javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "--release", "11",
                "-d", legacyClasses.toString(), source.toString())).isZero();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (java.net.URLClassLoader loader = new java.net.URLClassLoader(new java.net.URL[]{legacyClasses.toUri().toURL()},
                ClassLoader.getPlatformClassLoader()); ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(loader.loadClass(className).getConstructor().newInstance());
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            final MailSendOutcome restored = (MailSendOutcome) input.readObject();
            assertThat(restored.getObservationId()).isNull();
            assertThat(restored.getRequestedAt()).isEqualTo(Instant.EPOCH);
            assertThat(restored.getFailure().orElseThrow()).hasMessage("old failed attempt");
        }
    }
    @Test
    void uploadThrottlingFinalCountsAndRetryResetAreIndependentOfTerminalDiagnostics() throws Exception {
        final AtomicLong clock = new AtomicLong(100);
        final BlockingQueue<Runnable> executor = new LinkedBlockingQueue<>();
        final AtomicReference<MailSendProgress> latest = new AtomicReference<>();
        final UUID id = UUID.randomUUID();
        try (MailSendProgressDispatcher dispatcher = new MailSendProgressDispatcher(latest::set, executor::add)) {
            final MailSendProgressRecorder recorder = new MailSendProgressRecorder(dispatcher, id, "private-message-id", clock::get);
            final MailSendDiagnosticsRecorder diagnostics = new MailSendDiagnosticsRecorder(null, recorder);
            diagnostics.startPreparation();
            take(executor).run();
            assertThat(latest.get().getSubmissionAttempt()).isZero();
            diagnostics.submissionInvoked();
            take(executor).run();
            recorder.contentTransferStarted(100L);
            take(executor).run();
            final long beforeUpload = latest.get().getSequence();
            clock.addAndGet(Duration.ofMillis(249).toNanos());
            recorder.contentBytesWritten(40);
            assertThat(executor).isEmpty();
            clock.addAndGet(Duration.ofMillis(1).toNanos());
            recorder.contentBytesWritten(50);
            take(executor).run();
            assertThat(latest.get().getSequence()).isEqualTo(beforeUpload + 1);
            assertThat(latest.get().getBytesWritten()).isEqualTo(50L);
            assertThat(latest.get().getElapsed()).isEqualTo(Duration.ofMillis(250));
            recorder.contentTransferFinished(100);
            take(executor).run();
            assertThat(latest.get().getTotalBytes()).isEqualTo(100L);
            recorder.waitingForResponse();
            take(executor).run();
            assertThat(latest.get().getStage()).isEqualTo(MailSendProgressStage.WAITING_FOR_RESPONSE);
            diagnostics.startRetryWait();
            take(executor).run();
            diagnostics.submissionInvoked();
            take(executor).run();
            assertThat(latest.get().getSubmissionAttempt()).isEqualTo(2);
            assertThat(latest.get().getBytesWritten()).isNull();
            recorder.contentTransferStarted(100L);
            recorder.contentTransferFinished(80); // Content changed: do not pretend the old total still describes it.
            recorder.finish("effective-private-id");
            take(executor).run();
            if (latest.get().getStage() != MailSendProgressStage.FINISHED) {
                take(executor).run();
            }
            assertThat(latest.get().getObservationId()).isEqualTo(id);
            assertThat(latest.get().getTotalBytes()).isNull();
            assertThat(latest.get().getBytesWritten()).isEqualTo(80L);
            assertThat(latest.get().getEffectiveMessageId()).isEqualTo("effective-private-id");
            recorder.contentBytesWritten(150);
            recorder.stage(MailSendProgressStage.PREPARING);
            recorder.finish("late-id");
            assertThat(executor).isEmpty();
            assertThat(diagnostics.finish(true)).isNull();
        }
    }

    @Test
    void snapshotsAreSerializableAndAutomaticRenderingDoesNotExposeMessageIds() throws Exception {
        final MailSendProgress progress = MailSendProgressImpl.builder().observationId(UUID.randomUUID()).sequence(7).observedAt(Instant.EPOCH)
                .elapsed(Duration.ofSeconds(3)).stage(MailSendProgressStage.UPLOADING).submissionAttempt(2)
                .initialMessageId("secret-initial").effectiveMessageId("secret-effective").bytesWritten(12L).totalBytes(20L).build();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(progress);
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertThat(input.readObject()).isEqualTo(progress);
        }
        assertThat(progress.toString()).contains(progress.getObservationId().toString(), "UPLOADING", "submissionAttempt=2", "PT3S", "12", "20")
                .doesNotContain("secret-initial", "secret-effective");
        assertThatThrownBy(() -> MailSendProgressImpl.builder().observationId(UUID.randomUUID()).sequence(1).observedAt(Instant.EPOCH)
                .elapsed(Duration.ZERO).stage(MailSendProgressStage.UPLOADING).bytesWritten(2L).totalBytes(1L).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void disabledAndResultOnlyPathsNeverRequestProviderProgress() {
        assertThat(MailSendDiagnosticsRecorder.unobserved().openProgressScope(true, null)).isNull();
        assertThat(new MailSendDiagnosticsRecorder(System::nanoTime).openProgressScope(false, null)).isNull();
        assertThat(MailSendAttempt.unobserved().diagnostics()).isSameAs(MailSendDiagnosticsRecorder.unobserved());
    }
}
