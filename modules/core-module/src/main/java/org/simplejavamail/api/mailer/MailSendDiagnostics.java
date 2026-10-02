package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.internal.util.SmtpDiagnosticText;

import java.io.Serializable;
import java.time.Duration;
import java.util.Optional;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

/**
 * Explains where elapsed time went during one observed email attempt, using the actual send rather than a separate connection probe.
 * <p>
 * Durations use a monotonic clock and end before observer dispatch. They are not CPU measurements or the remaining send-timeout budget.
 * The total includes orchestration overhead, so the individual measurements need not add up to it. Shared batch/open-connection setup and teardown
 * lie outside each email's attempt and are reported as unavailable rather than zero. No additional message reads or SMTP commands are needed.
 * <p>
 * Read SMTP acceptance from {@link MailSendOutcome#getSubmissionReceipt()}, not from timings or failure locations. These are portable boundaries,
 * not separate DNS, TLS, authentication or SMTP-command timings. Selected server details are available explicitly, but omitted from {@link #toString()}.
 * At most one measurement marks the primary failure. When that failure was observed outside a measured boundary, none is marked.
 */
public final class MailSendDiagnostics implements Serializable {

	private static final long serialVersionUID = 1L;
	private static final MailSendMeasurement RATE_WAIT_NOT_RECORDED =
			new MailSendMeasurement(null, "Sending-limit waiting was not recorded in this snapshot.", false);

	@NotNull private final Duration elapsed;
	@NotNull private final MailSendMeasurement preparation;
	@NotNull private final MailSendMeasurement scheduling;
	@Nullable private final MailSendMeasurement rateLimitWait;
	@NotNull private final MailSendMeasurement connectionAcquisition;
	@NotNull private final MailSendMeasurement mimePreparation;
	@NotNull private final MailSendMeasurement submission;
	@NotNull private final MailSendMeasurement cleanup;
	@Nullable private final String smtpHost;
	@Nullable private final Integer smtpPort;

	/**
	 * Creates a supplied-data snapshot. Applications normally receive one through {@link MailSendOutcome#getDiagnostics()}.
	 *
	 * @param elapsed Total non-negative attempt duration before observer dispatch.
	 * @param preparation Governance/validation measurement.
	 * @param scheduling Async admission/worker-wait measurement, or an absence explanation.
	 * @param connectionAcquisition Measurement for obtaining a usable connection, or an absence explanation.
	 * @param mimePreparation MIME conversion/protection measurement.
	 * @param submission Provider/custom-mailer invocation measurement.
	 * @param cleanup In-attempt resource cleanup measurement.
	 * @param smtpHost Logical host reported by the selected transport, or null if unknown. Escaped and bounded for display.
	 * @param smtpPort Known selected port between 1 and 65535, or null if unknown. Never inferred from defaults.
	 */
	public MailSendDiagnostics(@NotNull final Duration elapsed, @NotNull final MailSendMeasurement preparation,
			@NotNull final MailSendMeasurement scheduling, @NotNull final MailSendMeasurement connectionAcquisition,
			@NotNull final MailSendMeasurement mimePreparation, @NotNull final MailSendMeasurement submission,
			@NotNull final MailSendMeasurement cleanup, @Nullable final String smtpHost, @Nullable final Integer smtpPort) {
		this(elapsed, preparation, scheduling, connectionAcquisition, mimePreparation, submission, cleanup, smtpHost, smtpPort, RATE_WAIT_NOT_RECORDED);
	}

	/**
	 * Creates a snapshot including sending-limit waiting. Other parameters follow the original constructor's contract.
	 *
	 * @param rateLimitWait Time awaiting local sending allowance, or an absence explanation.
	 * @see #MailSendDiagnostics(Duration, MailSendMeasurement, MailSendMeasurement, MailSendMeasurement, MailSendMeasurement,
	 * MailSendMeasurement, MailSendMeasurement, String, Integer)
	 */
	public MailSendDiagnostics(@NotNull final Duration elapsed, @NotNull final MailSendMeasurement preparation,
			@NotNull final MailSendMeasurement scheduling, @NotNull final MailSendMeasurement connectionAcquisition,
			@NotNull final MailSendMeasurement mimePreparation, @NotNull final MailSendMeasurement submission,
			@NotNull final MailSendMeasurement cleanup, @Nullable final String smtpHost, @Nullable final Integer smtpPort,
			@NotNull final MailSendMeasurement rateLimitWait) {
		this.elapsed = requireNonNull(elapsed, "elapsed");
		this.preparation = requireNonNull(preparation, "preparation");
		this.scheduling = requireNonNull(scheduling, "scheduling");
		this.rateLimitWait = requireNonNull(rateLimitWait, "rateLimitWait");
		this.connectionAcquisition = requireNonNull(connectionAcquisition, "connectionAcquisition");
		this.mimePreparation = requireNonNull(mimePreparation, "mimePreparation");
		this.submission = requireNonNull(submission, "submission");
		this.cleanup = requireNonNull(cleanup, "cleanup");
		if (elapsed.isNegative()) {
			throw new IllegalArgumentException("The total elapsed mail-send duration cannot be negative");
		}
		if (Stream.of(preparation, scheduling, rateLimitWait, connectionAcquisition, mimePreparation, submission, cleanup)
				.filter(MailSendMeasurement::isFailureObservedHere).count() > 1) {
			throw new IllegalArgumentException("Only one measurement can identify where the primary send failure was observed");
		}
		if (smtpPort != null && (smtpPort < 1 || smtpPort > 65535)) {
			throw new IllegalArgumentException("Supply the selected SMTP port between 1 and 65535, or null when it is unknown");
		}
		this.smtpHost = smtpHost == null ? null : SmtpDiagnosticText.display(smtpHost);
		this.smtpPort = smtpPort;
	}

	/** @return Total elapsed time, from attempt entry to the terminal snapshot, excluding this outcome's observer dispatch and callback. */
	@NotNull public Duration getElapsed() { return elapsed; }

	/** @return Time spent applying Email governance and validation, before MIME conversion, including elapsed time until preparation failure. */
	@NotNull public MailSendMeasurement getPreparation() { return preparation; }

	/**
	 * @return For an async individual send, time from readiness to execution or unstarted terminal failure, including blocking admission and worker waiting.
	 * Synchronous and already-running batch/open-connection email attempts have no separate scheduling measurement. This is not pure queue residence.
	 */
	@NotNull public MailSendMeasurement getScheduling() { return scheduling; }

	/**
	 * @return Time awaiting the selected configuration's local sending allowance. This is neither executor waiting nor connection acquisition.
	 * The total send timeout includes this wait. Shared-connection sends retain their transport while waiting; ordinary sends do not borrow one yet.
	 * Unavailable when limits are disabled, the gate was not reached or this is an older serialized snapshot.
	 */
	@NotNull public MailSendMeasurement getRateLimitWait() { return rateLimitWait == null ? RATE_WAIT_NOT_RECORDED : rateLimitWait; }

	/**
	 * @return Time obtaining a usable connection, including local proxy startup, direct connect or connected-pool acquisition/validation.
	 * It does not distinguish pool waiting from DNS, TLS or authentication. Shared-scope setup and application-owned connections are outside this measurement.
	 */
	@NotNull public MailSendMeasurement getConnectionAcquisition() { return connectionAcquisition; }

	/** @return Time converting/protecting MIME on the selected Session. Data-source reads deferred to the provider belong to submission instead. */
	@NotNull public MailSendMeasurement getMimePreparation() { return mimePreparation; }

	/**
	 * @return Time in the provider/custom-mailer invocation through result capture, including preflight, serialization, writes and replies.
	 * This is neither pure network time nor server-processing time. Logging-only sends have no submission measurement.
	 */
	@NotNull public MailSendMeasurement getSubmission() { return submission; }

	/**
	 * @return Time releasing, invalidating/disposing or closing ordinary-send resources and tearing down the proxy within the attempt.
	 * Releasing a healthy lease need not close its socket. Shared-scope final cleanup occurs later, outside each email's report.
	 */
	@NotNull public MailSendMeasurement getCleanup() { return cleanup; }

	/**
	 * @return Bounded, escaped logical host reported by the selected transport, not necessarily the invoking Mailer's configured server or the physical peer.
	 * Empty when unknown, including CustomMailer/logging-only paths. Consider infrastructure privacy before exporting this value.
	 */
	@NotNull public Optional<String> getSmtpHost() { return Optional.ofNullable(smtpHost); }

	/** @return Selected logical SMTP port, or empty when the transport did not provide one. No protocol-default port is guessed. */
	@NotNull public Optional<Integer> getSmtpPort() { return Optional.ofNullable(smtpPort); }

	/** @return Fixed-order measurements using ISO-8601 durations, without endpoints, identifiers, exception messages or content. */
	@Override
	public String toString() {
		return "Mail send timings:\n  Total: " + elapsed
				+ "\n  Preparation: " + preparation
				+ "\n  Scheduling: " + scheduling
				+ "\n  Sending-limit wait: " + getRateLimitWait()
				+ "\n  Connection acquisition: " + connectionAcquisition
				+ "\n  MIME preparation: " + mimePreparation
				+ "\n  Submission: " + submission
				+ "\n  Cleanup: " + cleanup;
	}
}
