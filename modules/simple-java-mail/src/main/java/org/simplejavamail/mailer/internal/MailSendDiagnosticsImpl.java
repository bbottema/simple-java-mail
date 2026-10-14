package org.simplejavamail.mailer.internal;

import lombok.Builder;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailSendConnectionStep;
import org.simplejavamail.api.mailer.MailSendDiagnostics;
import org.simplejavamail.api.mailer.MailSendMeasurement;
import org.simplejavamail.api.mailer.MailSendSubmissionStep;
import org.simplejavamail.internal.util.SmtpDiagnosticText;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

/** Library-owned immutable implementation of {@link MailSendDiagnostics}. */
@ApiStatus.Internal
public final class MailSendDiagnosticsImpl implements MailSendDiagnostics {

	private static final long serialVersionUID = 1L;
	private static final MailSendMeasurement RATE_WAIT_NOT_RECORDED = MailSendMeasurementImpl.builder()
		.unavailableReason("Sending-limit waiting was not recorded in this snapshot.")
		.build();
	private static final MailSendMeasurement RETRY_WAIT_NOT_RECORDED = MailSendMeasurementImpl.builder()
		.unavailableReason("Automatic retry waiting was not recorded for this send.")
		.build();

	@NotNull private final Duration elapsed;
	@NotNull private final MailSendMeasurement preparation;
	@NotNull private final MailSendMeasurement scheduling;
	@NotNull private final MailSendMeasurement rateLimitWait;
	@NotNull private final MailSendMeasurement connectionAcquisition;
	@NotNull private final MailSendMeasurement mimePreparation;
	@NotNull private final MailSendMeasurement submission;
	@NotNull private final MailSendMeasurement cleanup;
	@Nullable private final String smtpHost;
	@Nullable private final Integer smtpPort;
	@NotNull private final List<MailSendConnectionStep> connectionHistory;
	private final long omittedConnectionSteps;
	@NotNull private final MailSendMeasurement retryWait;
	@NotNull private final List<MailSendSubmissionStep> submissionHistory;
	private final long omittedSubmissionSteps;

	/** Copies and validates the supplied observations; it performs no send work. */
	@Builder
	private MailSendDiagnosticsImpl(final Duration elapsed, final MailSendMeasurement preparation, final MailSendMeasurement scheduling,
			final MailSendMeasurement connectionAcquisition, final MailSendMeasurement mimePreparation,
			final MailSendMeasurement submission, final MailSendMeasurement cleanup, final String smtpHost, final Integer smtpPort,
			final MailSendMeasurement rateLimitWait, final List<MailSendConnectionStep> connectionHistory, final long omittedConnectionSteps,
			final MailSendMeasurement retryWait, final List<MailSendSubmissionStep> submissionHistory, final long omittedSubmissionSteps) {
		this.elapsed = requireNonNull(elapsed, "elapsed");
		this.preparation = requireNonNull(preparation, "preparation");
		this.scheduling = requireNonNull(scheduling, "scheduling");
		this.connectionAcquisition = requireNonNull(connectionAcquisition, "connectionAcquisition");
		this.mimePreparation = requireNonNull(mimePreparation, "mimePreparation");
		this.submission = requireNonNull(submission, "submission");
		this.cleanup = requireNonNull(cleanup, "cleanup");
		this.rateLimitWait = rateLimitWait == null ? RATE_WAIT_NOT_RECORDED : rateLimitWait;
		this.retryWait = retryWait == null ? RETRY_WAIT_NOT_RECORDED : retryWait;
		this.connectionHistory = immutableConnectionHistory(connectionHistory == null ? Collections.emptyList() : connectionHistory, omittedConnectionSteps);
		this.omittedConnectionSteps = omittedConnectionSteps;
		this.submissionHistory = immutableSubmissionHistory(submissionHistory == null ? Collections.emptyList() : submissionHistory, omittedSubmissionSteps);
		this.omittedSubmissionSteps = omittedSubmissionSteps;
		this.smtpHost = smtpHost == null ? null : SmtpDiagnosticText.display(smtpHost);
		this.smtpPort = smtpPort;
		validateSuppliedObservations();
	}

	private void validateSuppliedObservations() {
		if (elapsed.isNegative()) {
			throw new IllegalArgumentException("The total elapsed mail-send duration cannot be negative");
		}
		if (Stream.of(preparation, scheduling, rateLimitWait, connectionAcquisition, mimePreparation, submission, cleanup, retryWait)
				.filter(MailSendMeasurement::isFailureObservedHere).count() > 1) {
			throw new IllegalArgumentException("Only one measurement can identify where the primary send failure was observed");
		}
		if (smtpPort != null && (smtpPort < 1 || smtpPort > 65535)) {
			throw new IllegalArgumentException("Supply the selected SMTP port between 1 and 65535, or null when it is unknown");
		}
	}

	/** @see MailSendDiagnostics#getElapsed() */
	@Override
	@NotNull
	public Duration getElapsed() { return elapsed; }

	/** @see MailSendDiagnostics#getPreparation() */
	@Override
	@NotNull
	public MailSendMeasurement getPreparation() { return preparation; }

	/** @see MailSendDiagnostics#getScheduling() */
	@Override
	@NotNull
	public MailSendMeasurement getScheduling() { return scheduling; }

	/** @see MailSendDiagnostics#getRateLimitWait() */
	@Override
	@NotNull
	public MailSendMeasurement getRateLimitWait() { return rateLimitWait; }

	/** @see MailSendDiagnostics#getConnectionAcquisition() */
	@Override
	@NotNull
	public MailSendMeasurement getConnectionAcquisition() { return connectionAcquisition; }

	/** @see MailSendDiagnostics#getMimePreparation() */
	@Override
	@NotNull
	public MailSendMeasurement getMimePreparation() { return mimePreparation; }

	/** @see MailSendDiagnostics#getSubmission() */
	@Override
	@NotNull
	public MailSendMeasurement getSubmission() { return submission; }

	/** @see MailSendDiagnostics#getCleanup() */
	@Override
	@NotNull
	public MailSendMeasurement getCleanup() { return cleanup; }

	/** @see MailSendDiagnostics#getSmtpHost() */
	@Override
	@NotNull
	public Optional<String> getSmtpHost() { return Optional.ofNullable(smtpHost); }

	/** @see MailSendDiagnostics#getSmtpPort() */
	@Override
	@NotNull
	public Optional<Integer> getSmtpPort() { return Optional.ofNullable(smtpPort); }

	/** @see MailSendDiagnostics#getConnectionHistory() */
	@Override
	@NotNull
	public List<MailSendConnectionStep> getConnectionHistory() {
		return connectionHistory;
	}

	/** @see MailSendDiagnostics#getOmittedConnectionSteps() */
	@Override
	public long getOmittedConnectionSteps() {
		return omittedConnectionSteps;
	}

	/** @see MailSendDiagnostics#getRetryWait() */
	@Override
	@NotNull
	public MailSendMeasurement getRetryWait() { return retryWait; }

	/** @see MailSendDiagnostics#getSubmissionHistory() */
	@Override
	@NotNull
	public List<MailSendSubmissionStep> getSubmissionHistory() {
		return submissionHistory;
	}

	/** @see MailSendDiagnostics#getOmittedSubmissionSteps() */
	@Override
	public long getOmittedSubmissionSteps() { return omittedSubmissionSteps; }

	/** @see MailSendDiagnostics#toString() */
	@Override
	public String toString() {
		return "Mail send timings:\n  Total: " + elapsed
				+ "\n  Preparation: " + preparation
				+ "\n  Scheduling: " + scheduling
				+ "\n  Sending-limit wait: " + getRateLimitWait()
				+ "\n  Retry wait: " + getRetryWait()
				+ "\n  Connection acquisition: " + connectionAcquisition
				+ "\n  MIME preparation: " + mimePreparation
				+ "\n  Submission: " + submission
				+ "\n  Cleanup: " + cleanup
				+ renderConnectionHistory() + renderSubmissionHistory();
	}

	private String renderSubmissionHistory() {
		final List<MailSendSubmissionStep> history = getSubmissionHistory();
		if (history.isEmpty() && omittedSubmissionSteps == 0) {
			return "";
		}
		return "\n  Submission history:" + history.stream().map(step -> "\n    - " + step).collect(Collectors.joining())
				+ (omittedSubmissionSteps == 0 ? "" : "\n    Intermediate submissions omitted: " + omittedSubmissionSteps);
	}

	private String renderConnectionHistory() {
		final List<MailSendConnectionStep> history = getConnectionHistory();
		if (history.isEmpty() && omittedConnectionSteps == 0) {
			return "";
		}
		final StringBuilder report = new StringBuilder("\n  Connection history:");
		history.forEach(step -> report.append("\n    - ").append(step));
		if (omittedConnectionSteps > 0) {
			report.append("\n    Intermediate steps omitted: ").append(omittedConnectionSteps);
		}
		return report.toString();
	}

	@NotNull
	private static List<MailSendSubmissionStep> immutableSubmissionHistory(@NotNull final List<MailSendSubmissionStep> submissionHistory,
			final long omittedSubmissionSteps) {
		if (requireNonNull(submissionHistory, "submissionHistory").size() > 64 || omittedSubmissionSteps < 0) {
			throw new IllegalArgumentException("Retain at most 64 submission steps and a non-negative omitted-step count.");
		}
		submissionHistory.forEach(step -> requireNonNull(step, "submission step"));
		return Collections.unmodifiableList(new ArrayList<>(submissionHistory));
	}

	@NotNull
	private static List<MailSendConnectionStep> immutableConnectionHistory(@NotNull final List<MailSendConnectionStep> connectionHistory,
			final long omittedConnectionSteps) {
		if (requireNonNull(connectionHistory, "connectionHistory").size() > 64 || omittedConnectionSteps < 0) {
			throw new IllegalArgumentException("Retain at most 64 connection steps and a non-negative omitted-step count.");
		}
		connectionHistory.forEach(step -> requireNonNull(step, "connection step"));
		return Collections.unmodifiableList(new ArrayList<>(connectionHistory));
	}
}
