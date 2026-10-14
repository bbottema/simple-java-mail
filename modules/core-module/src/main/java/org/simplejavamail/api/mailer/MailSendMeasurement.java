package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.time.Duration;
import java.util.Optional;

/**
 * Time spent in one part of sending an email, or an explanation of why that part was not measured.
 * A measured zero is a real duration; it does not mean that the work was skipped.
 */
public interface MailSendMeasurement extends Serializable {

	/** @return Elapsed time, including time until failure, or empty when this part was not measured. This is not CPU time. */
	@NotNull
	Optional<Duration> getElapsed();

	/** @return A human-readable absence explanation, or empty for a measured duration. Do not parse this text as a status code. */
	@NotNull
	Optional<String> getUnavailableReason();

	/**
	 * @return Whether the send failed during this step.
	 * For example, if submission fails and closing the connection also fails,
	 * only the submission measurement returns true.
	 * This identifies the failed step, not the underlying cause.
	 */
	boolean isFailureObservedHere();

	/** @return A duration or absence explanation, without exception messages or email content. */
	@Override
	String toString();
}
