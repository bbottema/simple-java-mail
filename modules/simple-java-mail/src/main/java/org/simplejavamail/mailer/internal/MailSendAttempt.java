package org.simplejavamail.mailer.internal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSendCancelledException;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSendDiagnostics;
import org.simplejavamail.api.mailer.MailSendTimeoutException;
import org.simplejavamail.api.mailer.MailSubmissionException;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.internal.util.concurrent.MailSendControl;
import org.simplejavamail.internal.telemetry.MailSendTelemetryBridge;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Objects.requireNonNull;

/**
 * Tracks the observable state of one logical email send attempt without exposing the email or transport lifecycle publicly.
 */
final class MailSendAttempt {

	@Nullable private final MailSendObserverNotifier notifier;
	@Nullable private final UUID observationId;
	@Nullable private final MailSendProgressRecorder progress;
	@Nullable private final MailSendTelemetryRecorder telemetry;
	@Nullable private final String initialMessageId;
	@Nullable private final Instant requestedAt;
	private boolean loggingOnly;
	@NotNull private final MailSendDiagnosticsRecorder diagnostics;
	@Nullable private Instant readyAt;
	@Nullable private Instant startedAt;
	@Nullable private Email effectiveEmail;
	private final AtomicBoolean completed = new AtomicBoolean();
	@Nullable private MailSendControl control;

	private MailSendAttempt(@Nullable final MailSendObserverNotifier notifier,
			@Nullable final String initialMessageId,
			@Nullable final Instant requestedAt,
			final boolean loggingOnly, @Nullable final MailSendControl control) {
		this.notifier = notifier;
		this.initialMessageId = initialMessageId;
		this.requestedAt = requestedAt;
		this.loggingOnly = loggingOnly;
		this.control = control;
		this.observationId = notifier == null ? null : UUID.randomUUID();
		this.progress = notifier == null ? null : notifier.progressRecorder(observationId, initialMessageId);
		this.telemetry = notifier == null ? null : MailSendTelemetryRecorder.begin(notifier.captureTelemetryContext(), observationId);
		this.diagnostics = notifier == null ? MailSendDiagnosticsRecorder.unobserved()
				: MailSendDiagnosticsRecorder.forObservers(notifier.observesResults(), progress, telemetry);
		if (loggingOnly) {
			diagnostics.useLoggingOnly();
		}
	}

	@NotNull
	static MailSendAttempt observed(@NotNull final MailSendObserverNotifier notifier,
			@Nullable final String initialMessageId,
			@NotNull final Instant requestedAt,
			final boolean loggingOnly, @Nullable final MailSendControl control) {
		return new MailSendAttempt(notifier, initialMessageId, requestedAt, loggingOnly, control);
	}

	@NotNull
	static MailSendAttempt unobserved() {
		return new MailSendAttempt(null, null, null, false, null);
	}

	void prepared(@NotNull final Email email) {
		if (notifier == null) {
			return;
		}
		effectiveEmail = email;
		if (progress != null) {
			progress.effectiveMessageId(email.getId());
		}
		diagnostics.prepared();
	}

	void readyForAdmission() {
		if (notifier != null) {
			readyAt = Instant.now();
			diagnostics.readyForAdmission();
		}
	}

	void preparationStarted() {
		diagnostics.startPreparation();
	}

	/** Record the destination-specific operational mode that determines what this attempt actually does. */
	void selectedSendingMode(final boolean loggingOnly) {
		this.loggingOnly = loggingOnly;
	}

	@NotNull
	MailSendDiagnosticsRecorder diagnostics() {
		return diagnostics;
	}

	boolean observesTelemetry() {
		return telemetry != null;
	}

	@Nullable
	MailSendTelemetryBridge.Scope attachTelemetry() {
		return telemetry == null ? null : telemetry.attach();
	}

	void attachControl(@NotNull final MailSendControl control) {
		this.control = control;
	}

	void started() {
		if (notifier != null) {
			startedAt = Instant.now();
			diagnostics.started();
		}
	}

	void completeSuccessfully(@NotNull final MailSubmissionReceipt submissionReceipt) {
		complete(true, submissionReceipt, null);
	}

	void completeWithFailure(@NotNull final Throwable failure) {
		final MailSubmissionReceipt submissionReceipt = submissionReceiptFromStartedFailure(failure);
		complete(false, submissionReceipt, failure);
	}

	/**
	 * Preparation and scheduling failures must remain receipt-free, even if application code throws a prebuilt submission exception before execution.
	 */
	@Nullable
	private MailSubmissionReceipt submissionReceiptFromStartedFailure(@NotNull final Throwable failure) {
		if (!diagnostics.hasEnteredSubmission()) {
			return null;
		}
		if (failure instanceof MailSendCancelledException) {
			return ((MailSendCancelledException) failure).getSubmissionReceipt().orElse(diagnostics.capturedReceipt());
		}
		if (failure instanceof MailSendTimeoutException) {
			return ((MailSendTimeoutException) failure).getSubmissionReceipt().orElse(diagnostics.capturedReceipt());
		}
		if (failure instanceof MailSubmissionException) {
			return ((MailSubmissionException) failure).getSubmissionReceipt();
		}
		return diagnostics.capturedReceipt();
	}

	private void complete(final boolean successful,
			@Nullable final MailSubmissionReceipt submissionReceipt,
			@Nullable final Throwable failure) {
		if (notifier == null || !completed.compareAndSet(false, true)) {
			return;
		}
		final String effectiveId = effectiveMessageId(submissionReceipt);
		if (progress != null) {
			progress.finish(effectiveId);
		}
		final MailSendDiagnostics report = notifier.observesResults() ? diagnostics.finish(successful) : null;
		final Instant completedAt = Instant.now();
		if (telemetry != null) {
			telemetry.complete(diagnostics.telemetryReport(successful, loggingOnly, submissionReceipt, failure));
		}
		if (!notifier.observesResults()) {
			effectiveEmail = null;
			diagnostics.releaseReceipt();
			return;
		}
		final MailSendOutcome outcome = MailSendOutcomeImpl.builder()
			.observationId(observationId)
			.initialMessageId(initialMessageId)
			.effectiveMessageId(effectiveId)
			.requestedAt(requireNonNull(requestedAt, "requestedAt"))
			.readyAt(readyAt)
			.startedAt(startedAt)
			.completedAt(completedAt)
			.successful(successful)
			.loggingOnly(loggingOnly)
			.submissionReceipt(submissionReceipt)
			.failure(failure)
			.diagnostics(report)
			.build();
		effectiveEmail = null;
		diagnostics.releaseReceipt();
		try (MailSendControl.DeadlinePause ignored = control == null ? null : control.pauseDeadline()) {
			notifier.notifyCompletion(outcome);
		}
	}

	@Nullable
	private String effectiveMessageId(@Nullable final MailSubmissionReceipt submissionReceipt) {
		if (submissionReceipt != null && submissionReceipt.getEmailId() != null) {
			return submissionReceipt.getEmailId();
		}
		return effectiveEmail != null ? effectiveEmail.getId() : null;
	}
}
