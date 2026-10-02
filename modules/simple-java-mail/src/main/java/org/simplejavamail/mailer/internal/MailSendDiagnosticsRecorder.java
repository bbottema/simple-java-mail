package org.simplejavamail.mailer.internal;

import jakarta.mail.Transport;
import jakarta.mail.URLName;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailSendDiagnostics;
import org.simplejavamail.api.mailer.MailSendMeasurement;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * Owns the measurements of one email attempt. Passed explicitly through send helpers; never attached to a Session or shared transport.
 * Caller preparation hands it to execution or terminal completion through the operation's existing boundaries; they do not mutate it concurrently.
 */
public final class MailSendDiagnosticsRecorder {

	private static final MailSendDiagnosticsRecorder UNOBSERVED = new MailSendDiagnosticsRecorder(null);
	private static final String NOT_REACHED = "This part of the send was not reached.";
	private static final String SHARED_CONNECTION = "The shared connection is opened and closed outside this email's attempt.";

	@Nullable private final LongSupplier clock;
	private final long requestedAt;
	private final Measurement preparation = new Measurement(NOT_REACHED);
	private final Measurement scheduling = new Measurement("This email has no separate asynchronous scheduling step.");
	private final Measurement rateLimitWait = new Measurement(NOT_REACHED);
	private final Measurement connectionAcquisition = new Measurement(NOT_REACHED);
	private final Measurement mimePreparation = new Measurement(NOT_REACHED);
	private final Measurement submission = new Measurement(NOT_REACHED);
	private final Measurement cleanup = new Measurement(NOT_REACHED);
	@Nullable private Measurement active;
	@Nullable private Measurement primaryFailure;
	@Nullable private MailSubmissionReceipt receipt;
	@Nullable private String smtpHost;
	@Nullable private Integer smtpPort;
	private boolean asynchronous;
	private boolean ownsCleanup;
	private boolean failureObserved;

	/** Clock injection is internal and exists to test elapsed boundaries without timing-sensitive sleeps. */
	MailSendDiagnosticsRecorder(@Nullable final LongSupplier clock) {
		this.clock = clock;
		requestedAt = clock == null ? 0 : clock.getAsLong();
		if (clock != null) {
			preparation.start(requestedAt);
			active = preparation;
		}
	}

	@NotNull
	static MailSendDiagnosticsRecorder observed() {
		return new MailSendDiagnosticsRecorder(System::nanoTime);
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

	void prepared() {
		if (asynchronous) {
			start(scheduling);
		} else {
			finishActive();
		}
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
		}
	}

	void useCustomMailer() {
		if (clock != null) {
			connectionAcquisition.unavailableReason = "CustomMailer owns its connection setup inside its submission callback.";
			cleanup.unavailableReason = "CustomMailer owns its connection cleanup inside its submission callback.";
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

	/** Saves the exact result before a release/close failure can prevent it from returning through the call stack. */
	public void receiptProduced(@NotNull final MailSubmissionReceipt receipt) {
		if (clock != null) {
			this.receipt = receipt;
			finishActive();
		}
	}

	/** Freeze the first observed failure before switching to cleanup, which can fail independently. */
	public void failed() {
		if (clock != null && !failureObserved) {
			failureObserved = true;
			primaryFailure = active;
		}
	}

	/** Includes ordinary transport cleanup and subsequent proxy teardown; shared-scope cleanup is deliberately not measured here. */
	public void startCleanup() {
		if (ownsCleanup) {
			start(cleanup);
		}
	}

	void cleanupCompleted() {
		if (active == cleanup) {
			finishActive();
		}
	}

	@Nullable
	MailSubmissionReceipt capturedReceipt() {
		return receipt;
	}

	@Nullable
	MailSendDiagnostics finish(final boolean successful) {
		if (clock == null) {
			return null;
		}
		if (!successful) {
			failed();
		}
		final long completedAt = clock.getAsLong();
		finishActive(completedAt);
		final Measurement failurePoint = successful ? null : primaryFailure;
		return new MailSendDiagnostics(Duration.ofNanos(completedAt - requestedAt), preparation.snapshot(failurePoint), scheduling.snapshot(failurePoint),
				connectionAcquisition.snapshot(failurePoint), mimePreparation.snapshot(failurePoint), submission.snapshot(failurePoint),
				cleanup.snapshot(failurePoint), smtpHost, smtpPort, rateLimitWait.snapshot(failurePoint));
	}

	void releaseReceipt() {
		if (clock != null) {
			receipt = null;
		}
	}

	private void start(final Measurement measurement) {
		if (clock == null || active == measurement) {
			return;
		}
		final long now = clock.getAsLong();
		finishActive(now);
		measurement.start(now);
		active = measurement;
	}

	private void finishActive() {
		if (clock != null && active != null) {
			finishActive(clock.getAsLong());
		}
	}

	private void finishActive(final long now) {
		if (active != null) {
			active.elapsed += now - active.startedAt;
			active = null;
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
			return new MailSendMeasurement(reached ? Duration.ofNanos(elapsed) : null, reached ? null : unavailableReason, this == failurePoint);
		}
	}
}
