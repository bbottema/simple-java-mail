package org.simplejavamail.mailer.internal;

import lombok.Builder;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailSendDiagnostics;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/** Library-owned immutable implementation of {@link MailSendOutcome}. */
@ApiStatus.Internal
public final class MailSendOutcomeImpl implements MailSendOutcome {

	private static final long serialVersionUID = 1L;
	@Nullable private final UUID observationId;

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

	/** Copies and validates the supplied observations; it performs no send work. */
	@Builder
	private MailSendOutcomeImpl(@Nullable final UUID observationId, @Nullable final String initialMessageId, @Nullable final String effectiveMessageId,
			@NotNull final Instant requestedAt, @Nullable final Instant readyAt, @Nullable final Instant startedAt, @NotNull final Instant completedAt,
			final boolean successful, final boolean loggingOnly, @Nullable final MailSubmissionReceipt submissionReceipt,
			@Nullable final Throwable failure, @Nullable final MailSendDiagnostics diagnostics) {
		this.observationId = observationId;
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

	/** @see MailSendOutcome#getObservationId() */
	@Override
	@Nullable
	public UUID getObservationId() {
		return observationId;
	}

	/** @see MailSendOutcome#getInitialMessageId() */
	@Override
	@Nullable
	public String getInitialMessageId() {
		return initialMessageId;
	}

	/** @see MailSendOutcome#getEffectiveMessageId() */
	@Override
	@Nullable
	public String getEffectiveMessageId() {
		return effectiveMessageId;
	}

	/** @see MailSendOutcome#getRequestedAt() */
	@Override
	@NotNull
	public Instant getRequestedAt() {
		return requestedAt;
	}

	/** @see MailSendOutcome#getReadyAt() */
	@Override
	@NotNull
	public Optional<Instant> getReadyAt() {
		return Optional.ofNullable(readyAt);
	}

	/** @see MailSendOutcome#getStartedAt() */
	@Override
	@NotNull
	public Optional<Instant> getStartedAt() {
		return Optional.ofNullable(startedAt);
	}

	/** @see MailSendOutcome#getCompletedAt() */
	@Override
	@NotNull
	public Instant getCompletedAt() {
		return completedAt;
	}

	/** @see MailSendOutcome#isSuccessful() */
	@Override
	public boolean isSuccessful() {
		return successful;
	}

	/** @see MailSendOutcome#isLoggingOnly() */
	@Override
	public boolean isLoggingOnly() {
		return loggingOnly;
	}

	/** @see MailSendOutcome#getSubmissionReceipt() */
	@Override
	@NotNull
	public Optional<MailSubmissionReceipt> getSubmissionReceipt() {
		return Optional.ofNullable(submissionReceipt);
	}

	/** @see MailSendOutcome#getFailure() */
	@Override
	@NotNull
	public Optional<Throwable> getFailure() {
		return Optional.ofNullable(failure);
	}

	/** @see MailSendOutcome#getDiagnostics() */
	@Override
	@NotNull
	public Optional<MailSendDiagnostics> getDiagnostics() {
		return Optional.ofNullable(diagnostics);
	}
}
