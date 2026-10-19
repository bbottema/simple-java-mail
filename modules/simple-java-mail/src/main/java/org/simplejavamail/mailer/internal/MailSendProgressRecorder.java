package org.simplejavamail.mailer.internal;

import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailSendProgressStage;
import org.simplejavamail.api.mailer.spi.MailTransportProgress;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Records one logical send independently of terminal diagnostics. No application code runs here, including while a provider owns its transport monitor.
 * Synchronization fences terminal publication against any late provider observation; this is reporting state, not permission to send or retry.
 */
final class MailSendProgressRecorder implements MailTransportProgress {
    // Four upload updates per second are enough for a responsive dashboard without allocating a snapshot per stream write.
    private static final long UPLOAD_INTERVAL_NANOS = Duration.ofMillis(250).toNanos();
    private final MailSendProgressDispatcher dispatcher;
    @Nullable private MailSendProgressDispatcher.Target[] targets;
    private final UUID observationId;
    @Nullable private final String initialMessageId;
    private final LongSupplier clock;
    private final long requestedAt;
    @Nullable private String effectiveMessageId;
    @Nullable private MailSendProgressStage stage;
    @Nullable private Long bytesWritten;
    @Nullable private Long totalBytes;
    private long sequence;
    private long lastPublishedAt;
    private int submissionAttempt;
    private boolean finished;
    @Nullable private MailSendProgressScope activeScope;

    MailSendProgressRecorder(final MailSendProgressDispatcher dispatcher, final UUID observationId,
            @Nullable final String initialMessageId, final LongSupplier clock) {
        this(dispatcher, observationId, initialMessageId, clock, null);
    }

    MailSendProgressRecorder(final MailSendProgressDispatcher dispatcher, final UUID observationId,
            @Nullable final String initialMessageId, final LongSupplier clock, @Nullable final MailSendProgressDispatcher.Target[] targets) {
        this.dispatcher = dispatcher;
        this.targets = targets;
        this.observationId = observationId;
        this.initialMessageId = initialMessageId;
        this.clock = clock;
        requestedAt = clock.getAsLong();
    }

    synchronized void stage(final MailSendProgressStage next) {
        detachScope();
        providerStage(next);
    }

    synchronized void providerStage(final MailSendProgressStage next) {
        if (!finished && stage != next) {
            stage = next;
            publish(clock.getAsLong());
        }
    }

    synchronized MailSendProgressScope openScope(final boolean connectionSetup) {
        detachScope();
        final MailSendProgressScope scope = new MailSendProgressScope(this, connectionSetup);
        if (!finished) {
            activeScope = scope;
        } else {
            scope.detach();
        }
        return scope;
    }

    synchronized boolean isCurrentScope(final MailSendProgressScope scope) {
        return !finished && activeScope == scope;
    }

    synchronized void retireScope(final MailSendProgressScope scope, final boolean restoreStage) {
        if (activeScope == scope) {
            activeScope = null;
            // Final upload/response states remain meaningful until orchestration advances; only setup needs an enclosing fallback here.
            if (restoreStage && scope.enclosingStage() == MailSendProgressStage.ACQUIRING_CONNECTION) {
                providerStage(MailSendProgressStage.ACQUIRING_CONNECTION);
            }
        }
    }

    private void detachScope() {
        if (activeScope != null) {
            activeScope.detach();
            activeScope = null;
        }
    }

    synchronized void effectiveMessageId(@Nullable final String messageId) {
        if (!finished) {
            effectiveMessageId = messageId;
        }
    }

    synchronized void submissionInvoked() {
        if (!finished) {
            submissionAttempt++;
            bytesWritten = null;
            totalBytes = null;
            stage = MailSendProgressStage.SUBMITTING;
            publish(clock.getAsLong());
        }
    }

    @Override
    public synchronized void contentTransferStarted(@Nullable final Long totalBytes) {
        if (!finished) {
            this.totalBytes = totalBytes != null && totalBytes >= 0 ? totalBytes : null;
            bytesWritten = 0L;
            stage = MailSendProgressStage.UPLOADING;
            publish(clock.getAsLong());
        }
    }

    @Override
    public synchronized void contentBytesWritten(final long written) {
        if (!finished && stage == MailSendProgressStage.UPLOADING) {
            updateCount(written);
            final long now = clock.getAsLong();
            if (now - lastPublishedAt >= UPLOAD_INTERVAL_NANOS) {
                publish(now);
            }
        }
    }

    @Override
    public synchronized void contentTransferFinished(final long written) {
        if (!finished && stage == MailSendProgressStage.UPLOADING) {
            updateCount(written);
            if (totalBytes != null && !totalBytes.equals(bytesWritten)) {
                totalBytes = null;
            }
            publish(clock.getAsLong());
        }
    }

    private void updateCount(final long written) {
        if (written < 0 || (bytesWritten != null && written < bytesWritten)) {
            totalBytes = null;
            return;
        }
        bytesWritten = written;
        if (totalBytes != null && written > totalBytes) {
            totalBytes = null;
        }
    }

    @Override
    public void waitingForResponse() {
        stage(MailSendProgressStage.WAITING_FOR_RESPONSE);
    }

    synchronized void finish(@Nullable final String messageId) {
        if (!finished) {
            detachScope();
            if (messageId != null) {
                effectiveMessageId = messageId;
            }
            stage = MailSendProgressStage.FINISHED;
            finished = true;
            publish(clock.getAsLong());
            targets = null;
        }
    }

    private void publish(final long now) {
        final MailSendProgressImpl snapshot = MailSendProgressImpl.builder()
                .observationId(observationId).sequence(++sequence).observedAt(Instant.now())
                .elapsed(Duration.ofNanos(Math.max(0, now - requestedAt))).stage(stage).submissionAttempt(submissionAttempt)
                .initialMessageId(initialMessageId).effectiveMessageId(effectiveMessageId).bytesWritten(bytesWritten).totalBytes(totalBytes).build();
        if (targets == null) { dispatcher.offer(snapshot); }
        else { dispatcher.offer(snapshot, targets); }
        lastPublishedAt = now;
    }
}
