package org.simplejavamail.internal.telemetry;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailSubmissionStatus;

import java.util.UUID;

/** Runtime lifecycle contracts used by the loaded telemetry module, never implemented by application configuration objects. */
@ApiStatus.Internal
public interface MailSendTelemetryBridge {

    interface Runtime {
        @NotNull CapturedContext captureContext();
    }

    interface CapturedContext {
        @NotNull Scope makeCurrent();
        @NotNull Attempt beginSend(@NotNull UUID observationId);
    }

    interface Attempt {
        @NotNull Scope makeCurrent();
        @NotNull Submission beginSubmission(boolean customMailer);
        void complete(@NotNull MailSendTelemetryReport report);
    }

    interface Submission {
        @NotNull Scope makeCurrent();
        void complete(boolean successful, @Nullable MailSubmissionStatus status, @Nullable Throwable failure,
                @Nullable String smtpHost, @Nullable Integer smtpPort);
    }

    /** Must be closed on the thread that attached it, before a work turn suspends or calls an application result observer. */
    interface Scope extends AutoCloseable {
        @Override void close();
    }
}
