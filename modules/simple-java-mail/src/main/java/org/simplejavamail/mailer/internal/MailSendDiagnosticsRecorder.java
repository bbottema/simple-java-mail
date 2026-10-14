package org.simplejavamail.mailer.internal;

import jakarta.mail.Transport;
import jakarta.mail.URLName;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailSendDiagnostics;
import org.simplejavamail.api.mailer.MailSendMeasurement;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.MailSendConnectionStep;
import org.simplejavamail.api.mailer.MailSendSubmissionStep;
import org.simplejavamail.api.mailer.MailSubmissionStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Owns the measurements of one email attempt. Passed explicitly through send helpers; never attached to a Session or shared transport.
 * Admission hands it to execution or terminal completion through the operation's existing boundaries; they do not mutate it concurrently.
 */
public final class MailSendDiagnosticsRecorder {

	private static final MailSendDiagnosticsRecorder UNOBSERVED = new MailSendDiagnosticsRecorder(null);
	private static final String NOT_REACHED = "This part of the send was not reached.";
	private static final String SHARED_CONNECTION = "The shared connection is opened and closed outside this email's attempt.";
	/** Each history independently retains its first 63 steps and latest step; this limits reporting, never routing or retries. */
	private static final int MAX_RETAINED_HISTORY_STEPS = 64;

	@Nullable private final LongSupplier clock;
	private final long requestedAt;
	private final Measurement preparation = new Measurement(NOT_REACHED);
	private final Measurement scheduling = new Measurement("This email has no separate asynchronous scheduling step.");
	private final Measurement rateLimitWait = new Measurement(NOT_REACHED);
	private final Measurement connectionAcquisition = new Measurement(NOT_REACHED);
	private final Measurement mimePreparation = new Measurement(NOT_REACHED);
	private final Measurement submission = new Measurement(NOT_REACHED);
	private final Measurement cleanup = new Measurement(NOT_REACHED);
	private final Measurement retryWait = new Measurement("Automatic retries are not enabled for this send.");
	@Nullable private List<MailSendSubmissionStep> submissionHistory;
	private long omittedSubmissionSteps;
	private long submissionStepStarted;
	private long submissionStepElapsed;
	@Nullable private Measurement activeMeasurement;
	@Nullable private Measurement failureMeasurement;
	@Nullable private MailSubmissionReceipt receipt;
	@Nullable private String smtpHost;
	@Nullable private Integer smtpPort;
	private boolean asynchronous;
	private boolean ownsCleanup;
	private boolean failureObserved;
	@Nullable private List<MailSendConnectionStep> connectionHistory;
	private long omittedConnectionSteps;
	private long connectionStepStarted;
	private boolean hasActiveConnectionStep;
	@Nullable private String connectionConfigurationName;
	@Nullable private String configuredConnectionHost;
	@Nullable private Integer configuredConnectionPort;

	/** Clock injection is internal and exists to test elapsed boundaries without timing-sensitive sleeps. */
	MailSendDiagnosticsRecorder(@Nullable final LongSupplier clock) {
		this.clock = clock;
		requestedAt = clock == null ? 0 : clock.getAsLong();
	}

	@NotNull
	static MailSendDiagnosticsRecorder observed() {
		return new MailSendDiagnosticsRecorder(System::nanoTime);
	}

	boolean isObserved() {
		return clock != null;
	}

	/** Shared stateless fast path: every mutating method returns before reading the clock or writing fields. */
	@NotNull
	public static MailSendDiagnosticsRecorder unobserved() {
		return UNOBSERVED;
	}

	void useAsyncScheduling() {
		if (clock != null) {
			asynchronous = true;
			scheduling.unavailableReason = NOT_REACHED;
		}
	}

	void readyForAdmission() {
		if (asynchronous) {
			start(scheduling);
		} else {
			finishActive();
		}
	}

	void startPreparation() {
		start(preparation);
	}

	void prepared() {
		finishActive();
	}

	boolean hasEnteredSubmission() {
		return submission.reached || receipt != null;
	}

	void started() {
		finishActive();
	}

	void useSharedConnection() {
		if (clock != null) {
			connectionAcquisition.unavailableReason = SHARED_CONNECTION;
			cleanup.unavailableReason = SHARED_CONNECTION;
		}
	}

	void useLoggingOnly() {
		if (clock != null) {
			rateLimitWait.unavailableReason = "Logging-only mode consumes no sending allowance.";
			connectionAcquisition.unavailableReason = "Logging-only mode does not acquire an SMTP connection.";
			submission.unavailableReason = "Logging-only mode does not submit the email.";
			cleanup.unavailableReason = "Logging-only mode has no sending connection to release.";
			retryWait.unavailableReason = "Logging-only mode makes no SMTP attempts to retry.";
		}
	}

	void useCustomMailer() {
		if (clock != null) {
			connectionAcquisition.unavailableReason = "CustomMailer owns its connection setup inside its submission callback.";
			cleanup.unavailableReason = "CustomMailer owns its connection cleanup inside its submission callback.";
			retryWait.unavailableReason = "CustomMailer owns any retry decisions inside its callback.";
		}
	}

	/** Begins before proxy startup; TransportRunner can call it again without restarting the same measurement. */
	public void startConnectionAcquisition() {
		if (clock != null) {
			ownsCleanup = true;
			start(connectionAcquisition);
		}
	}

	/** Reads the selected transport only. URLName's user/password/path are deliberately never copied or rendered. */
	public void connectionAcquired(@NotNull final Transport transport) {
		if (clock == null) {
			return;
		}
		finishActive();
		try {
			final URLName endpoint = transport.getURLName();
			if (endpoint != null) {
				final String host = endpoint.getHost();
				smtpHost = host == null || host.isBlank() ? null : host;
				final int port = endpoint.getPort();
				smtpPort = port >= 1 && port <= 65535 ? port : null;
			}
		} catch (RuntimeException unavailableEndpoint) {
			// Optional provider metadata must not turn a successful acquisition into a failed send.
			smtpHost = null;
			smtpPort = null;
		}
		finishConnectionStep(true, null);
	}

	void beginConnectionStep(@Nullable final String configurationName, @Nullable final String host, @Nullable final Integer port) {
		if (clock != null) {
			connectionStepStarted = clock.getAsLong();
			hasActiveConnectionStep = true;
			connectionConfigurationName = configurationName;
			configuredConnectionHost = host;
			configuredConnectionPort = port;
		}
	}

	void failedConnectionStep(final String explanation) {
		finishConnectionStep(false, explanation);
	}

	private void finishConnectionStep(final boolean acquired, @Nullable final String explanation) {
		if (clock != null && hasActiveConnectionStep) {
			final String host = acquired && smtpHost != null ? smtpHost : configuredConnectionHost;
			final Integer port = acquired && smtpPort != null ? smtpPort : configuredConnectionPort;
			addConnectionStep(MailSendConnectionStepImpl.builder()
				.configuredName(connectionConfigurationName)
				.smtpHost(host)
				.smtpPort(port)
				.connectionAttempted(true)
				.connectionAcquired(acquired)
				.elapsed(Duration.ofNanos(clock.getAsLong() - connectionStepStarted))
				.explanation(explanation)
				.build());
			hasActiveConnectionStep = false;
			connectionConfigurationName = null;
			configuredConnectionHost = null;
			configuredConnectionPort = null;
		}
	}

	void skippedConnection(final String configurationName, final String host, final Integer port, final String explanation) {
		if (clock != null) {
			addConnectionStep(MailSendConnectionStepImpl.builder()
				.configuredName(configurationName)
				.smtpHost(host)
				.smtpPort(port)
				.connectionAttempted(false)
				.connectionAcquired(false)
				.elapsed(null)
				.explanation(explanation)
				.build());
		}
	}

	/** A remedied acquisition is history, not the failure location of a later terminal error. */
	void continuingAfterConnectionFailure() {
		clearFailureAttribution();
	}

	private void clearFailureAttribution() {
		if (clock != null) {
			failureMeasurement = null;
			failureObserved = false;
		}
	}

	private void addConnectionStep(final MailSendConnectionStep step) {
		if (connectionHistory == null) {
			connectionHistory = new ArrayList<>();
		}
		if (connectionHistory.size() < MAX_RETAINED_HISTORY_STEPS) {
			connectionHistory.add(step);
		} else {
			connectionHistory.set(MAX_RETAINED_HISTORY_STEPS - 1, step);
			omittedConnectionSteps++;
		}
	}

	public void startMimePreparation() {
		start(mimePreparation);
	}

	void useUnlimitedSending() {
		if (clock != null) {
			rateLimitWait.unavailableReason = "No sending limit is configured for this destination.";
		}
	}

	void startRateLimitWait() {
		start(rateLimitWait);
	}

	void rateLimitWaitCompleted() {
		finishActive();
	}

	public void startSubmission() {
		start(submission);
	}

	/** History measures the invocation itself; resolver checks and rate commitment still belong to the broader submission measurement. */
	public void submissionInvoked() {
		if (clock != null && submissionHistory != null) { submissionStepStarted = clock.getAsLong(); }
	}

	void enableSubmissionHistory() {
		if (clock != null) {
			submissionHistory = new ArrayList<>();
			retryWait.unavailableReason = "This email did not need to wait for a retry.";
		}
	}

	void submissionStep(final int attemptNumber, @Nullable final MailSubmissionReceipt receipt, @Nullable final Duration delay, final boolean failed) {
		if (clock == null || submissionHistory == null) { return; }
		appendSubmissionStep(createSubmissionStep(attemptNumber, receipt, delay));
		if (failed) {
			failureObserved = true;
			failureMeasurement = submission;
		}
	}

	private MailSendSubmissionStep createSubmissionStep(final int attemptNumber, @Nullable final MailSubmissionReceipt receipt,
			@Nullable final Duration delay) {
		final MailSubmissionStatus status = receipt == null ? MailSubmissionStatus.UNKNOWN : receipt.getStatus();
		final Integer code = receipt == null ? null : receipt.getSmtpResponse().map(response -> response.getReturnCode())
				.filter(value -> value >= 100 && value <= 599).orElse(null);
		return MailSendSubmissionStepImpl.builder()
			.attemptNumber(attemptNumber)
			.submissionStatus(status)
			.smtpReplyCode(code)
			.elapsed(Duration.ofNanos(receipt == null ? clock.getAsLong() - submissionStepStarted : submissionStepElapsed))
			.retryDelay(delay)
			.explanation(delay == null ? null : "Temporary refusal; the entire email can be submitted again safely.")
			.build();
	}

	private void appendSubmissionStep(final MailSendSubmissionStep step) {
		if (submissionHistory.size() < MAX_RETAINED_HISTORY_STEPS) { submissionHistory.add(step); }
		else { submissionHistory.set(MAX_RETAINED_HISTORY_STEPS - 1, step); omittedSubmissionSteps++; }
	}

	/** Keep intermediate receipt/history facts, but clear terminal failure attribution so a recovered send has no stale failure marker. */
	void continuingAfterSubmissionRefusal() {
		clearFailureAttribution();
	}

	/** Record a delay only after cleanup and bounded retry admission allow it, not merely because the SMTP refusal looked eligible. */
	void retryScheduled(final Duration delay) {
		if (clock == null || submissionHistory == null || submissionHistory.isEmpty()) { return; }
		final int latestIndex = submissionHistory.size() - 1;
		final MailSendSubmissionStep latest = submissionHistory.get(latestIndex);
		submissionHistory.set(latestIndex, MailSendSubmissionStepImpl.builder()
			.attemptNumber(latest.getAttemptNumber())
			.submissionStatus(latest.getSubmissionStatus())
			.smtpReplyCode(latest.getSmtpReplyCode().orElse(null))
			.elapsed(latest.getElapsed())
			.retryDelay(delay)
			.explanation("Temporary refusal; the entire email can be submitted again safely.")
			.build());
	}

	void startRetryWait() { start(retryWait); }
	void retryWaitCompleted() {
		if (activeMeasurement == retryWait) { finishActive(); }
	}

	/** Saves the exact result before a release/close failure can prevent it from returning through the call stack. */
	public void receiptProduced(@NotNull final MailSubmissionReceipt receipt) {
		if (clock != null) {
			this.receipt = receipt;
			if (submissionHistory != null) { submissionStepElapsed = clock.getAsLong() - submissionStepStarted; }
			finishActive();
		}
	}

	/** Freeze the first observed failure before switching to cleanup, which can fail independently. */
	public void failed() {
		if (clock != null && !failureObserved) {
			failureObserved = true;
			failureMeasurement = activeMeasurement;
		}
	}

	/** Includes ordinary transport cleanup and subsequent proxy teardown; shared-scope cleanup is deliberately not measured here. */
	public void startCleanup() {
		if (ownsCleanup) {
			start(cleanup);
		}
	}

	void cleanupCompleted() {
		if (activeMeasurement == cleanup) {
			finishActive();
		}
	}

	@Nullable
	MailSubmissionReceipt capturedReceipt() {
		return receipt;
	}

	@Nullable
	MailSendDiagnostics finish(final boolean successful) {
		if (!successful) {
			failedConnectionStep("Connection acquisition did not complete; see the outcome's failure for details.");
		}
		if (clock == null) {
			return null;
		}
		if (!successful) {
			failed();
		}
		final long completedAt = clock.getAsLong();
		finishActive(completedAt);
		final Measurement failurePoint = successful ? null : failureMeasurement;
		return MailSendDiagnosticsImpl.builder()
			.elapsed(Duration.ofNanos(completedAt - requestedAt))
			.preparation(preparation.snapshot(failurePoint))
			.scheduling(scheduling.snapshot(failurePoint))
			.connectionAcquisition(connectionAcquisition.snapshot(failurePoint))
			.mimePreparation(mimePreparation.snapshot(failurePoint))
			.submission(submission.snapshot(failurePoint))
			.cleanup(cleanup.snapshot(failurePoint))
			.smtpHost(smtpHost)
			.smtpPort(smtpPort)
			.rateLimitWait(rateLimitWait.snapshot(failurePoint))
			.connectionHistory(connectionHistory == null ? Collections.emptyList() : connectionHistory)
			.omittedConnectionSteps(omittedConnectionSteps)
			.retryWait(retryWait.snapshot(failurePoint))
			.submissionHistory(submissionHistory == null ? Collections.emptyList() : submissionHistory)
			.omittedSubmissionSteps(omittedSubmissionSteps)
			.build();
	}

	void releaseReceipt() {
		if (clock != null) {
			receipt = null;
		}
	}

	private void start(final Measurement measurement) {
		if (clock == null || activeMeasurement == measurement) {
			return;
		}
		final long now = clock.getAsLong();
		finishActive(now);
		measurement.start(now);
		activeMeasurement = measurement;
	}

	private void finishActive() {
		if (clock != null && activeMeasurement != null) {
			finishActive(clock.getAsLong());
		}
	}

	private void finishActive(final long now) {
		if (activeMeasurement != null) {
			activeMeasurement.elapsed += now - activeMeasurement.startedAt;
			activeMeasurement = null;
		}
	}

	/** A fixed recorder field, not a configurable step or a second operation state machine. */
	private static final class Measurement {
		private String unavailableReason;
		private boolean reached;
		private long startedAt;
		private long elapsed;

		private Measurement(final String unavailableReason) {
			this.unavailableReason = unavailableReason;
		}

		private void start(final long now) {
			reached = true;
			startedAt = now;
		}

		private MailSendMeasurement snapshot(@Nullable final Measurement failurePoint) {
			return MailSendMeasurementImpl.builder()
				.elapsed(reached ? Duration.ofNanos(elapsed) : null)
				.unavailableReason(reached ? null : unavailableReason)
				.failureObservedHere(this == failurePoint)
				.build();
		}
	}
}
