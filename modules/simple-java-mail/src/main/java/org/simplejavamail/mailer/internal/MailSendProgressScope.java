package org.simplejavamail.mailer.internal;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.ApiStatus;
import org.simplejavamail.api.mailer.MailSendProgressStage;
import org.simplejavamail.api.mailer.spi.MailTransportProgress;
import org.simplejavamail.internal.util.concurrent.MailSendControl;

import java.util.function.Consumer;

/**
 * Gives a provider reporting authority for exactly one setup or submission. Identity, not a transport or Session, identifies this generation.
 * All recording is fenced by the recorder monitor; retirement never takes a network monitor or invokes application code.
 */
@ApiStatus.Internal
public final class MailSendProgressScope implements MailTransportProgress, AutoCloseable {
    @Nullable private volatile MailSendProgressRecorder owner;
    private final boolean connectionSetup;
    @Nullable private MailSendControl.Registration cancellation;

    MailSendProgressScope(final MailSendProgressRecorder owner, final boolean connectionSetup) {
        this.owner = owner;
        this.connectionSetup = connectionSetup;
    }

    void observeCancellation(final MailSendControl control) {
        cancellation = control.onStop(() -> retire(false));
    }

    /** Cancellation revokes reporting immediately; the owning execution later detaches the provider registration. */
    private void retire(final boolean restoreStage) {
        final MailSendProgressRecorder recorder = owner;
        if (recorder != null) {
            synchronized (recorder) {
                owner = null;
                recorder.retireScope(this, restoreStage);
            }
        }
    }

    /** Called under the recorder monitor when orchestration replaces the generation or completes the send. */
    void detach() {
        owner = null;
    }

    MailSendProgressStage enclosingStage() {
        return connectionSetup ? MailSendProgressStage.ACQUIRING_CONNECTION : MailSendProgressStage.SUBMITTING;
    }

    @Override
    public void stageChanged(final MailSendProgressStage stage) {
        if (accepts(stage)) {
            record(recorder -> recorder.providerStage(stage));
        }
    }

    private boolean accepts(@Nullable final MailSendProgressStage stage) {
        return stage == enclosingStage() || (connectionSetup ? isSetupStage(stage) : isSubmissionStage(stage));
    }

    private static boolean isSetupStage(@Nullable final MailSendProgressStage stage) {
        return stage == MailSendProgressStage.WAITING_FOR_GREETING || stage == MailSendProgressStage.NEGOTIATING_CAPABILITIES
                || stage == MailSendProgressStage.NEGOTIATING_TLS || stage == MailSendProgressStage.AUTHENTICATING;
    }

    private static boolean isSubmissionStage(@Nullable final MailSendProgressStage stage) {
        return stage == MailSendProgressStage.SUBMITTING_SENDER || stage == MailSendProgressStage.SUBMITTING_RECIPIENTS
                || stage == MailSendProgressStage.WAITING_FOR_DATA_PERMISSION;
    }

    @Override
    public void contentTransferStarted(@Nullable final Long totalBytes) {
        if (!connectionSetup) {
            record(recorder -> recorder.contentTransferStarted(totalBytes));
        }
    }

    @Override
    public void contentBytesWritten(final long bytesWritten) {
        if (!connectionSetup) {
            record(recorder -> recorder.contentBytesWritten(bytesWritten));
        }
    }

    @Override
    public void contentTransferFinished(final long bytesWritten) {
        if (!connectionSetup) {
            record(recorder -> recorder.contentTransferFinished(bytesWritten));
        }
    }

    @Override
    public void waitingForResponse() {
        if (!connectionSetup) {
            record(recorder -> recorder.providerStage(MailSendProgressStage.WAITING_FOR_RESPONSE));
        }
    }

    private void record(final Consumer<MailSendProgressRecorder> observation) {
        final MailSendProgressRecorder recorder = owner;
        if (recorder != null) {
            synchronized (recorder) {
                if (owner == recorder && recorder.isCurrentScope(this)) {
                    observation.accept(recorder);
                }
            }
        }
    }

    @Override
    public void close() {
        retire(true);
        if (cancellation != null) {
            cancellation.close();
            cancellation = null;
        }
    }
}
