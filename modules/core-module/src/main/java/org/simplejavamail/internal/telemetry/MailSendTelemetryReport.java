package org.simplejavamail.internal.telemetry;

import lombok.Builder;
import lombok.Value;
import lombok.ToString;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailSubmissionStatus;

import java.time.Duration;

/** Supplied terminal facts for an integration, without public diagnostic histories, content or recipient identities. */
@Value
@Builder
@ApiStatus.Internal
public class MailSendTelemetryReport {
    boolean successful;
    boolean loggingOnly;
    @Nullable MailSubmissionStatus submissionStatus;
    @ToString.Exclude @Nullable Throwable failure;
    /** The existing measurement that observed the primary failure; absent for success or an unmeasured failure. */
    @Nullable String failurePhase;
    @NotNull Duration elapsed;
    @Nullable Duration preparation;
    @Nullable Duration scheduling;
    @Nullable Duration rateLimitWait;
    @Nullable Duration connectionAcquisition;
    @Nullable Duration mimePreparation;
    @Nullable Duration submission;
    @Nullable Duration retryWait;
    @Nullable Duration cleanup;
}
