package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.internal.util.SmtpDiagnosticText;

import java.io.Serializable;
import java.time.Duration;
import java.util.Optional;

/**
 * Time spent in one part of sending an email, or an explanation of why that part was not measured.
 * A measured zero is a real duration; it does not mean that the work was skipped.
 */
public final class MailSendMeasurement implements Serializable {

	private static final long serialVersionUID = 1L;

	@Nullable private final Duration elapsed;
	@Nullable private final String unavailableReason;
	private final boolean failureObservedHere;

	/**
	 * Creates a measurement. Applications normally read these from {@link MailSendDiagnostics}.
	 *
	 * @param elapsed Non-negative elapsed time, or null when this part was not measured.
	 * @param unavailableReason Explanation when elapsed is null; otherwise null. This is display text, not a machine-readable status.
	 * @param failureObservedHere Whether the send failed during this step. See {@link #isFailureObservedHere()} for an example.
	 */
	public MailSendMeasurement(@Nullable final Duration elapsed, @Nullable final String unavailableReason, final boolean failureObservedHere) {
		if ((elapsed == null) == (unavailableReason == null)) {
			throw new IllegalArgumentException("Supply either an elapsed duration or an explanation of why it was not measured, not both");
		}
		if (elapsed != null && elapsed.isNegative()) {
			throw new IllegalArgumentException("An elapsed mail-send duration cannot be negative");
		}
		if (unavailableReason != null && unavailableReason.isBlank()) {
			throw new IllegalArgumentException("Explain why this part of the send was not measured; an empty explanation is not useful");
		}
		if (failureObservedHere && elapsed == null) {
			throw new IllegalArgumentException("A failure location needs a measurement, including the time spent before it failed");
		}
		this.elapsed = elapsed;
		this.unavailableReason = unavailableReason == null ? null : SmtpDiagnosticText.display(unavailableReason);
		this.failureObservedHere = failureObservedHere;
	}

	/** @return Elapsed time, including time until failure, or empty when this part was not measured. This is not CPU time. */
	@NotNull
	public Optional<Duration> getElapsed() {
		return Optional.ofNullable(elapsed);
	}

	/** @return A human-readable absence explanation, or empty for a measured duration. Do not parse this text as a status code. */
	@NotNull
	public Optional<String> getUnavailableReason() {
		return Optional.ofNullable(unavailableReason);
	}

	/**
	 * @return Whether the send failed during this step.
	 * For example, if submission fails and closing the connection also fails,
	 * only the submission measurement returns true.
	 * This identifies the failed step, not the underlying cause.
	 */
	public boolean isFailureObservedHere() {
		return failureObservedHere;
	}

	/** @return A duration or absence explanation, without exception messages or email content. */
	@Override
	public String toString() {
		return elapsed == null ? "not measured: " + unavailableReason : elapsed + (failureObservedHere ? " (failure observed here)" : "");
	}
}
