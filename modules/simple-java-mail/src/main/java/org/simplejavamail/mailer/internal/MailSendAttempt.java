package org.simplejavamail.mailer.internal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSendHandlers;
import org.simplejavamail.MailException;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSendDiagnostics;
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
	@Nullable private MailSendHandlers generatedHandlers;
	private final boolean observesResults;
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

	private MailSendAttempt(@Nullable final MailSendObserverNotifier notifier, @Nullable final UUID observationId,
			@Nullable final MailSendHandlers generatedHandlers,
			@Nullable final String initialMessageId,
			@Nullable final Instant requestedAt,
			final boolean loggingOnly, @Nullable final MailSendControl control) {
		this.notifier = notifier;
		this.initialMessageId = initialMessageId;
		this.requestedAt = requestedAt;
		this.loggingOnly = loggingOnly;
		this.control = control;
		this.observationId = observationId;
		this.generatedHandlers = generatedHandlers;
		this.observesResults = notifier != null && notifier.observesResults(generatedHandlers);
		this.progress = notifier == null ? null : notifier.progressRecorder(observationId, initialMessageId, generatedHandlers);
		this.telemetry = notifier == null ? null : MailSendTelemetryRecorder.begin(notifier.captureTelemetryContext(), observationId);
		this.diagnostics = notifier == null ? MailSendDiagnosticsRecorder.unobserved()
				: MailSendDiagnosticsRecorder.forObservers(observesResults, progress, telemetry);
		if (loggingOnly) {
			diagnostics.useLoggingOnly();
		}
	}

	@NotNull
	static MailSendAttempt observed(@NotNull final MailSendObserverNotifier notifier, @NotNull final UUID observationId,
			@Nullable final MailSendHandlers generatedHandlers,
			@Nullable final String initialMessageId,
			@NotNull final Instant requestedAt,
			final boolean loggingOnly, @Nullable final MailSendControl control) {
		return new MailSendAttempt(notifier, observationId, generatedHandlers, initialMessageId, requestedAt, loggingOnly, control);
	}

	@NotNull
	static MailSendAttempt unobserved() {
		return new MailSendAttempt(null, null, null, null, null, false, null);
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
		if (failure instanceof MailException) {
			return ((MailException) failure).getSubmissionReceipt().orElse(diagnostics.capturedReceipt());
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
		final MailSendHandlers completedHandlers = generatedHandlers;
		generatedHandlers = null;
		final MailSendDiagnostics report = observesResults ? diagnostics.finish(successful) : null;
		final Instant completedAt = Instant.now();
		if (telemetry != null) {
			telemetry.complete(diagnostics.telemetryReport(successful, loggingOnly, submissionReceipt, failure));
		}
		if (!observesResults) {
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
			notifier.notifyCompletion(outcome, completedHandlers);
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
