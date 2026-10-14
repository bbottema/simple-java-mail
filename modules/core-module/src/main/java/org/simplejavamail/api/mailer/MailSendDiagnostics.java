package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

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
public interface MailSendDiagnostics extends Serializable {

	/** @return Total elapsed time, from attempt entry to the terminal snapshot, excluding this outcome's observer dispatch and callback. */
	@NotNull Duration getElapsed();

	/**
	 * @return Time spent applying the selected Mailer's Email governance and validation, before MIME conversion, including preparation failures.
	 * Connection failover can prepare more than one destination from the original Email; their preparation durations accumulate here.
	 */
	@NotNull MailSendMeasurement getPreparation();

	/**
	 * @return For an async individual send, time from readiness to execution or unstarted terminal failure, including blocking admission and worker waiting.
	 * Synchronous and already-running batch/open-connection email attempts have no separate scheduling measurement. This is not pure queue residence.
	 */
	@NotNull MailSendMeasurement getScheduling();

	/**
	 * @return Time awaiting the selected configuration's local sending allowance. This is neither executor waiting nor connection acquisition.
	 * The total send timeout includes this wait. Shared-connection sends retain their transport while waiting; ordinary sends do not borrow one yet.
	 * Unavailable when limits are disabled, the gate was not reached.
	 */
	@NotNull MailSendMeasurement getRateLimitWait();

	/**
	 * @return Time obtaining a usable connection, including local proxy startup, direct connect or connected-pool acquisition/validation.
	 * It does not distinguish pool waiting from DNS, TLS or authentication. Shared-scope setup and application-owned connections are outside this measurement.
	 */
	@NotNull MailSendMeasurement getConnectionAcquisition();

	/** @return Time converting/protecting MIME on the selected Session. Data-source reads deferred to the provider belong to submission instead. */
	@NotNull MailSendMeasurement getMimePreparation();

	/**
	 * @return Time in the provider/custom-mailer invocation through result capture, including preflight, serialization, writes and replies.
	 * This is neither pure network time nor server-processing time. Logging-only sends have no submission measurement.
	 */
	@NotNull MailSendMeasurement getSubmission();

	/**
	 * @return Time releasing, invalidating/disposing or closing ordinary-send resources and tearing down the proxy within the attempt.
	 * Releasing a healthy lease need not close its socket. Shared-scope final cleanup occurs later, outside each email's report.
	 */
	@NotNull MailSendMeasurement getCleanup();

	/**
	 * @return Bounded, escaped logical host reported by the selected transport, not necessarily the invoking Mailer's configured server or the physical peer.
	 * Empty when unknown, including CustomMailer/logging-only paths. Consider infrastructure privacy before exporting this value.
	 */
	@NotNull Optional<String> getSmtpHost();

	/** @return Selected logical SMTP port, or empty when the transport did not provide one. No protocol-default port is guessed. */
	@NotNull Optional<Integer> getSmtpPort();

	/**
	 * @return Immutable connection acquisition/skip history for this attempt, never submission retries. Retains the first 63 steps and the latest;
	 * the routing operation itself is not limited by this reporting budget. Shared-scope setup has no per-email connection history.
	 */
	@NotNull
	List<MailSendConnectionStep> getConnectionHistory();

	/** @return Number of intermediate connection steps omitted from this report. */
	long getOmittedConnectionSteps();

	/** Backoff plus waiting until resumed execution begins; initial admission/scheduling is measured separately. */
	@NotNull MailSendMeasurement getRetryWait();

	/** Immutable actual-invocation history for observed retry-enabled sends; empty for ordinary single-attempt paths. */
	@NotNull List<MailSendSubmissionStep> getSubmissionHistory();

	/** Number of intermediate actual submission steps omitted by the reporting budget, never a limit on retries. */
	long getOmittedSubmissionSteps();

	/**
	 * @return Fixed-order measurements using ISO-8601 durations, followed by any connection/submission histories and omitted-step counts.
	 * Destination names, endpoints, identifiers, raw exception messages and content remain excluded.
	 */
	@Override
	String toString();
}
