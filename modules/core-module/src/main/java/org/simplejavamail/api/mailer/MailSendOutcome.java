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
	 * @return The time at which send execution started, or empty when preparation or scheduling failed.
	 */
	@NotNull
	public Optional<Instant> getStartedAt() {
		return Optional.ofNullable(startedAt);
	}

	/**
	 * @return The time at which the attempt reached its terminal result.
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
}
