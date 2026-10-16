package org.simplejavamail.mailer.internal;

import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailSendTelemetry;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.internal.telemetry.MailSendTelemetryBridge;
import org.simplejavamail.internal.telemetry.MailSendTelemetryReport;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.modules.OpenTelemetryModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/** Isolates optional SDK calls from the existing send lifecycle. No callbacks, locks, queues or SDK instances are owned here. */
final class MailSendTelemetryRecorder {
    private static final Logger LOGGER = LoggerFactory.getLogger(MailSendTelemetryRecorder.class);

    @Nullable private MailSendTelemetryBridge.Attempt attempt;
    @Nullable private MailSendTelemetryBridge.Submission submission;
    @Nullable private MailSendTelemetryBridge.Scope submissionScope;

    private MailSendTelemetryRecorder(final MailSendTelemetryBridge.Attempt attempt) {
        this.attempt = attempt;
    }

    @Nullable
    static MailSendTelemetryBridge.Runtime bind(@Nullable final MailSendTelemetry registration, final boolean enabled, final boolean endpointAttributes) {
        if (!enabled) {
            return null;
        }
        // A requested missing module is a configuration error, not an SDK failure to suppress.
        final OpenTelemetryModule module = ModuleLoader.loadOpenTelemetryModule();
        try {
            return module.bind(registration, endpointAttributes);
        } catch (RuntimeException unavailableTelemetry) {
            warn("initializing the integration");
            return null;
        }
    }

    @Nullable
    static MailSendTelemetryBridge.CapturedContext capture(@Nullable final MailSendTelemetryBridge.Runtime runtime) {
        if (runtime == null) {
            return null;
        }
        try {
            return runtime.captureContext();
        } catch (RuntimeException unavailableTelemetry) {
            warn("capturing trace context");
            return null;
        }
    }

    @Nullable
    static MailSendTelemetryRecorder begin(@Nullable final MailSendTelemetryBridge.CapturedContext context, final UUID observationId) {
        if (context == null) {
            return null;
        }
        try {
            return new MailSendTelemetryRecorder(context.beginSend(observationId));
        } catch (RuntimeException unavailableTelemetry) {
            warn("starting the send observation");
            return null;
        }
    }

    @Nullable
    static MailSendTelemetryBridge.Scope attach(@Nullable final MailSendTelemetryBridge.CapturedContext context) {
        if (context == null) {
            return null;
        }
        try {
            return guardedScope(context.makeCurrent());
        } catch (RuntimeException unavailableTelemetry) {
            warn("attaching captured context");
            return null;
        }
    }

    @Nullable
    MailSendTelemetryBridge.Scope attach() {
        if (attempt == null) {
            return null;
        }
        try {
            final MailSendTelemetryBridge.Scope scope = guardedScope(attempt.makeCurrent());
            return () -> {
                // A provider child cannot outlive the work turn or leave its context attached when an exceptional exit skips result capture.
                submissionCompleted(false, null, null, null, null);
                scope.close();
            };
        } catch (RuntimeException unavailableTelemetry) {
            warn("attaching the send context");
            return null;
        }
    }

    void submissionInvoked(final boolean customMailer) {
        if (attempt == null) {
            return;
        }
        try {
            submission = attempt.beginSubmission(customMailer);
            submissionScope = guardedScope(submission.makeCurrent());
        } catch (RuntimeException unavailableTelemetry) {
            warn("starting the submission observation");
            // A child can exist even when attaching its context fails. Keep it for the actual result, rather than inventing a send failure.
        }
    }

    void submissionCompleted(final boolean successful, @Nullable final MailSubmissionStatus status, @Nullable final Throwable failure,
            @Nullable final String smtpHost, @Nullable final Integer smtpPort) {
        final MailSendTelemetryBridge.Submission completedSubmission = submission;
        submission = null;
        final MailSendTelemetryBridge.Scope completedScope = submissionScope;
        submissionScope = null;
        if (completedScope != null) {
            completedScope.close();
        }
        if (completedSubmission != null) {
            try {
                completedSubmission.complete(successful, status, failure, smtpHost, smtpPort);
            } catch (RuntimeException unavailableTelemetry) {
                warn("finishing the submission observation");
            }
        }
    }

    void complete(final MailSendTelemetryReport report) {
        final MailSendTelemetryBridge.Attempt completedAttempt = attempt;
        attempt = null;
        // An exceptional provider exit must not leave an unfinished child even if it skipped its ordinary result capture.
        submissionCompleted(false, report.getSubmissionStatus(), report.getFailure(), null, null);
        if (completedAttempt != null) {
            try {
                completedAttempt.complete(report);
            } catch (RuntimeException unavailableTelemetry) {
                warn("finishing the send observation");
            }
        }
    }

    private static MailSendTelemetryBridge.Scope guardedScope(final MailSendTelemetryBridge.Scope scope) {
        return () -> {
            try {
                scope.close();
            } catch (RuntimeException unavailableTelemetry) {
                warn("retiring trace context");
            }
        };
    }

    private static void warn(final String operation) {
        // Do not log the SDK throwable: exporter messages can contain endpoint URLs, authentication material or application data.
        LOGGER.warn("Mail-send telemetry failed while {}; leaving the send result unchanged", operation);
    }
}
