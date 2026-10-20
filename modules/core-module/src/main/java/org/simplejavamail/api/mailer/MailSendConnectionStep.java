package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.time.Duration;

/**
 * One destination considered while obtaining this email's connection. A skipped destination is not a new connection failure or submission attempt.
 * Endpoints are logical configured/provider details, not a claim about the physical peer. No credentials or raw provider failure text are retained.
 */
public interface MailSendConnectionStep extends Serializable {

	/** @return Name from the captured configuration, or {@code null}; unnamed Java-configured Mailers remain unnamed. */
	@Nullable
	String getConfiguredName();

	/** @return Bounded logical SMTP host, or {@code null} when unknown. Consider infrastructure privacy before exporting it. */
	@Nullable
	String getSmtpHost();

	/** @return Explicit logical SMTP port, or {@code null}; missing/default-unspecified ports are not guessed. */
	@Nullable
	Integer getSmtpPort();

	/** @return Whether this operation tried to obtain a usable connection; acquiring a healthy pooled connection also counts. */
	boolean isConnectionAttempted();

	/** @return Whether acquisition succeeded, not whether the email was submitted or delivered. */
	boolean isConnectionAcquired();

	/** @return Non-negative acquisition duration, or {@code null} when unmeasured. A cached skip has no duration. */
	@Nullable
	Duration getElapsed();

	/** @return Human-readable failure/skip explanation, or {@code null} when none applies; applications should not parse this text as a status code. */
	@Nullable
	String getExplanation();

	/** Automatic rendering deliberately excludes destination names and endpoint details. */
	@Override
	String toString();
}
