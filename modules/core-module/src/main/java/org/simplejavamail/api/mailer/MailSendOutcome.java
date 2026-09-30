package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Immutable terminal facts for one logical email send attempt.
 * <p>
 * This type describes the complete Simple Java Mail operation, including preparation and scheduling. When SMTP submission was reached, the optional
 * {@link MailSubmissionReceipt} provides the transport-neutral acceptance facts. It does not describe final mailbox delivery.
 * <p>
 * Applications normally receive instances through a configured {@link MailSendObserver}.
 */
public final class MailSendOutcome implements Serializable {

	private static final long serialVersionUID = 1L;

	@Nullable private final String initialMessageId;
	@Nullable private final String effectiveMessageId;
	@NotNull private final Instant requestedAt;
	@Nullable private final Instant readyAt;
	@Nullable private final Instant startedAt;
	@NotNull private final Instant completedAt;
	private final boolean successful;
	private final boolean loggingOnly;
	@Nullable private final MailSubmissionReceipt submissionReceipt;
	@Nullable private final Throwable failure;
	@Nullable private final MailSendDiagnostics diagnostics;

	/**
	 * Creates one immutable terminal outcome. Applications normally consume outcomes rather than construct them.
	 *
	 * @param initialMessageId  Message-ID present when the individual send attempt began, if any.
	 * @param effectiveMessageId Message-ID after mailer preparation and MIME conversion, if one was produced.
	 * @param requestedAt       Time at which this individual email attempt began.
	 * @param readyAt           Time at which preparation completed, or {@code null} when preparation failed.
	 * @param startedAt         Time at which send execution started, or {@code null} when preparation or scheduling failed.
	 * @param completedAt       Time at which the attempt reached its terminal result.
	 * @param successful        Whether the configured send operation completed without throwing.
	 * @param loggingOnly       Whether this attempt used transport logging-only mode.
	 * @param submissionReceipt Submission receipt when the attempt reached a receipt-producing path.
	 * @param failure           Exact failure exposed to the caller for an unsuccessful attempt.
	 */
	public MailSendOutcome(@Nullable final String initialMessageId,
			@Nullable final String effectiveMessageId,
			@NotNull final Instant requestedAt,
			@Nullable final Instant readyAt,
			@Nullable final Instant startedAt,
			@NotNull final Instant completedAt,
			final boolean successful,
			final boolean loggingOnly,
			@Nullable final MailSubmissionReceipt submissionReceipt,
			@Nullable final Throwable failure) {
		this(initialMessageId, effectiveMessageId, requestedAt, readyAt, startedAt, completedAt, successful, loggingOnly, submissionReceipt, failure, null);
	}

	/**
	 * Creates an outcome with optional actual-send measurements. The other arguments have the same contract as the original constructor.
	 *
	 * @param initialMessageId Initial Message-ID, if known.
	 * @param effectiveMessageId Effective Message-ID, if known.
	 * @param requestedAt Attempt entry time.
	 * @param readyAt Preparation completion time, if reached.
	 * @param startedAt Execution start time, if reached.
	 * @param completedAt Terminal snapshot time, before observer dispatch.
	 * @param successful Whether the operation completed successfully.
	 * @param loggingOnly Whether transport logging-only mode was configured.
	 * @param submissionReceipt Captured submission facts, including facts retained across later cleanup failure.
	 * @param failure Exact caller-facing failure, or null on success.
	 * @param diagnostics Actual-send measurements, or null when none were recorded.
	 */
	public MailSendOutcome(@Nullable final String initialMessageId, @Nullable final String effectiveMessageId,
			@NotNull final Instant requestedAt, @Nullable final Instant readyAt, @Nullable final Instant startedAt, @NotNull final Instant completedAt,
			final boolean successful, final boolean loggingOnly, @Nullable final MailSubmissionReceipt submissionReceipt,
			@Nullable final Throwable failure, @Nullable final MailSendDiagnostics diagnostics) {
		this.initialMessageId = initialMessageId;
		this.effectiveMessageId = effectiveMessageId;
		this.requestedAt = requireNonNull(requestedAt, "requestedAt");
		this.readyAt = readyAt;
		this.startedAt = startedAt;
		this.completedAt = requireNonNull(completedAt, "completedAt");
		this.successful = successful;
		this.loggingOnly = loggingOnly;
		this.submissionReceipt = submissionReceipt;
		this.failure = failure;
		this.diagnostics = diagnostics;
		validateTerminalState();
	}

	private void validateTerminalState() {
		if (successful && submissionReceipt == null) {
			throw new IllegalArgumentException("A successful mail send outcome requires a submission receipt");
		}
		if (successful && failure != null) {
			throw new IllegalArgumentException("A successful mail send outcome cannot contain a failure");
		}
		if (!successful && failure == null) {
			throw new IllegalArgumentException("An unsuccessful mail send outcome requires a failure");
		}
		if (startedAt != null && readyAt == null) {
			throw new IllegalArgumentException("A started mail send outcome requires a ready timestamp");
		}
	}

	/**
	 * @return The Message-ID present when the individual attempt began, or {@code null} when none was fixed by the caller.
	 */
	@Nullable
	public String getInitialMessageId() {
		return initialMessageId;
	}

	/**
	 * @return The effective Message-ID after preparation and MIME conversion, or {@code null} when none was produced.
	 */
	@Nullable
	public String getEffectiveMessageId() {
		return effectiveMessageId;
	}

	/**
	 * @return The time at which this individual email attempt began.
	 */
	@NotNull
	public Instant getRequestedAt() {
		return requestedAt;
	}

	/**
	 * @return The time at which preparation completed, or empty when preparation failed.
	 */
	@NotNull
	public Optional<Instant> getReadyAt() {
		return Optional.ofNullable(readyAt);
	}

	/**
	 * @return The time at which send execution started, or empty when preparation/admission failed or queued work was cancelled or expired.
	 */
	@NotNull
	public Optional<Instant> getStartedAt() {
		return Optional.ofNullable(startedAt);
	}

	/**
	 * @return The time at which the attempt reached its terminal result, before observer handoff or execution.
	 */
	@NotNull
	public Instant getCompletedAt() {
		return completedAt;
	}

	/**
	 * @return Whether the configured send operation completed without throwing.
	 */
	public boolean isSuccessful() {
		return successful;
	}

	/**
	 * @return Whether transport logging-only mode processed this email instead of invoking a sending transport.
	 */
	public boolean isLoggingOnly() {
		return loggingOnly;
	}

	/**
	 * @return The transport-neutral receipt when this attempt reached a receipt-producing path. Preparation and scheduling failures have no receipt.
	 * A later cleanup failure does not erase a captured receipt: inspect SMTP acceptance separately from {@link #isSuccessful()}.
	 */
	@NotNull
	public Optional<MailSubmissionReceipt> getSubmissionReceipt() {
		return Optional.ofNullable(submissionReceipt);
	}

	/**
	 * @return The exact failure exposed to the caller, or empty for a successful attempt.
	 */
	@NotNull
	public Optional<Throwable> getFailure() {
		return Optional.ofNullable(failure);
	}

	/**
	 * @return Actual-send timing and selected-endpoint diagnostics, captured automatically for observed attempts before observer dispatch.
	 * Empty for older serialized outcomes or manually constructed outcomes without measurements. This is not a connection probe or proof of delivery.
	 */
	@NotNull
	public Optional<MailSendDiagnostics> getDiagnostics() {
		return Optional.ofNullable(diagnostics);
	}
}
