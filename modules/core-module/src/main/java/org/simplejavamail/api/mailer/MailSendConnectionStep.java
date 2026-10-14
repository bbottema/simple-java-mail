package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.time.Duration;
import java.util.Optional;

/**
 * One destination considered while obtaining this email's connection. A skipped destination is not a new connection failure or submission attempt.
 * Endpoints are logical configured/provider details, not a claim about the physical peer. No credentials or raw provider failure text are retained.
 */
public interface MailSendConnectionStep extends Serializable {

	/** @return Optional name from the captured configuration; unnamed Java-configured Mailers remain unnamed. */
	@NotNull
	Optional<String> getConfiguredName();

	/** @return Bounded logical SMTP host, when known. Consider infrastructure privacy before exporting it. */
	@NotNull
	Optional<String> getSmtpHost();

	/** @return Explicit logical SMTP port; missing/default-unspecified ports are not guessed. */
	@NotNull
	Optional<Integer> getSmtpPort();

	/** @return Whether this operation tried to obtain a usable connection; acquiring a healthy pooled connection also counts. */
	boolean isConnectionAttempted();

	/** @return Whether acquisition succeeded, not whether the email was submitted or delivered. */
	boolean isConnectionAcquired();

	/** @return Non-negative acquisition duration, when measured. A cached skip has no duration. */
	@NotNull
	Optional<Duration> getElapsed();

	/** @return Human-readable failure/skip explanation; applications should not parse this text as a status code. */
	@NotNull
	Optional<String> getExplanation();

	/** Automatic rendering deliberately excludes destination names and endpoint details. */
	@Override
	String toString();
}
