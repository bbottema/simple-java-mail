package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.time.Duration;
import java.util.Optional;

/**
 * Safe diagnostic facts about one actual provider invocation in a retry-enabled logical send. It is not a receipt
 * or permission to retry: recipient facts and the final exception remain on the terminal outcome.
 */
public interface MailSendSubmissionStep extends Serializable {

	/** Number of this actual provider invocation, including the initial invocation as one. */
	int getAttemptNumber();

	/** Submission knowledge for this invocation, not final mailbox delivery. */
	@NotNull MailSubmissionStatus getSubmissionStatus();

	/** Genuine primary SMTP code when available; excludes raw reply text. */
	@NotNull Optional<Integer> getSmtpReplyCode();

	/** Time inside the provider invocation through result capture, not pure network or server-processing time. */
	@NotNull Duration getElapsed();

	/** Selected delay before another invocation; not a promise of exact resumption time. Absent when no retry was scheduled. */
	@NotNull Optional<Duration> getRetryDelay();

	/** Human-readable bounded explanation, not a value applications must parse. */
	@NotNull Optional<String> getExplanation();

	@Override String toString();
}
