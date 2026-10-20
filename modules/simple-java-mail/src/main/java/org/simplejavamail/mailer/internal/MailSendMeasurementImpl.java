package org.simplejavamail.mailer.internal;

import lombok.Builder;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailSendMeasurement;
import org.simplejavamail.internal.util.SmtpDiagnosticText;

import java.time.Duration;

/** Library-owned immutable implementation of {@link MailSendMeasurement}. */
@ApiStatus.Internal
public final class MailSendMeasurementImpl implements MailSendMeasurement {

	private static final long serialVersionUID = 1L;

	@Nullable private final Duration elapsed;
	@Nullable private final String unavailableReason;
	private final boolean failureObservedHere;

	/** Copies and validates the supplied observations; it performs no send work. */
	@Builder
	private MailSendMeasurementImpl(@Nullable final Duration elapsed, @Nullable final String unavailableReason, final boolean failureObservedHere) {
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

	/** @see MailSendMeasurement#getElapsed() */
	@Override
	@Nullable
	public Duration getElapsed() {
		return elapsed;
	}

	/** @see MailSendMeasurement#getUnavailableReason() */
	@Override
	@Nullable
	public String getUnavailableReason() {
		return unavailableReason;
	}

	/** @see MailSendMeasurement#isFailureObservedHere() */
	@Override
	public boolean isFailureObservedHere() {
		return failureObservedHere;
	}

	/** @see MailSendMeasurement#toString() */
	@Override
	public String toString() {
		return elapsed == null ? "not measured: " + unavailableReason : elapsed + (failureObservedHere ? " (failure observed here)" : "");
	}
}
