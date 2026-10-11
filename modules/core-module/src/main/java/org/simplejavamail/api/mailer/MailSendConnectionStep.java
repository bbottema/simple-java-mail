package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.internal.util.SmtpDiagnosticText;

import java.io.Serializable;
import java.time.Duration;
import java.util.Optional;

/**
 * One destination considered while obtaining this email's connection. A skipped destination is not a new connection failure or submission attempt.
 * Endpoints are logical configured/provider details, not a claim about the physical peer. No credentials or raw provider failure text are retained.
 */
public final class MailSendConnectionStep implements Serializable {
	private static final long serialVersionUID = 1L;
	@Nullable private final String configuredName;
	@Nullable private final String smtpHost;
	@Nullable private final Integer smtpPort;
	private final boolean connectionAttempted;
	private final boolean connectionAcquired;
	@Nullable private final Duration elapsed;
	@Nullable private final String explanation;

	/**
	 * Constructs a supplied-data snapshot. Skipped destinations have neither an acquired connection nor an elapsed acquisition duration.
	 * An attempted acquisition may have no duration when its provider could not measure it. Text is escaped and bounded for safe display.
	 */
	public MailSendConnectionStep(@Nullable final String configuredName, @Nullable final String smtpHost, @Nullable final Integer smtpPort,
			final boolean connectionAttempted, final boolean connectionAcquired, @Nullable final Duration elapsed, @Nullable final String explanation) {
		validateConnectionState(connectionAttempted, connectionAcquired, elapsed, explanation);
		validateSmtpPort(smtpPort);

		this.configuredName = toSafeDisplayText(configuredName);
		this.smtpHost = toSafeDisplayText(smtpHost);
		this.smtpPort = smtpPort;
		this.connectionAttempted = connectionAttempted;
		this.connectionAcquired = connectionAcquired;
		this.elapsed = elapsed;
		this.explanation = toSafeDisplayText(explanation);
	}

	/** @return Optional name from the captured configuration; unnamed Java-configured Mailers remain unnamed. */
	@NotNull
	public Optional<String> getConfiguredName() {
		return Optional.ofNullable(configuredName);
	}
	/** @return Bounded logical SMTP host, when known. Consider infrastructure privacy before exporting it. */
	@NotNull
	public Optional<String> getSmtpHost() {
		return Optional.ofNullable(smtpHost);
	}
	/** @return Explicit logical SMTP port; missing/default-unspecified ports are not guessed. */
	@NotNull
	public Optional<Integer> getSmtpPort() {
		return Optional.ofNullable(smtpPort);
	}
	/** @return Whether this operation tried to obtain a usable connection; acquiring a healthy pooled connection also counts. */
	public boolean isConnectionAttempted() {
		return connectionAttempted;
	}
	/** @return Whether acquisition succeeded, not whether the email was submitted or delivered. */
	public boolean isConnectionAcquired() {
		return connectionAcquired;
	}
	/** @return Non-negative acquisition duration, when measured. A cached skip has no duration. */
	@NotNull
	public Optional<Duration> getElapsed() {
		return Optional.ofNullable(elapsed);
	}
	/** @return Human-readable failure/skip explanation; applications should not parse this text as a status code. */
	@NotNull
	public Optional<String> getExplanation() {
		return Optional.ofNullable(explanation);
	}

	/** Automatic rendering deliberately excludes destination names and endpoint details. */
	@Override
	public String toString() {
		final String connectionResult = connectionAcquired ? "Connection acquired"
				: connectionAttempted ? "Connection failed" : "Destination skipped";
		final String elapsedDescription = elapsed == null ? "" : " in " + elapsed;
		final String explanationDescription = explanation == null ? "" : ": " + explanation;
		return connectionResult + elapsedDescription + explanationDescription;
	}

	@Nullable
	private static String toSafeDisplayText(@Nullable final String value) {
		return value == null ? null : SmtpDiagnosticText.display(value);
	}

	private static void validateConnectionState(final boolean connectionAttempted, final boolean connectionAcquired,
			@Nullable final Duration elapsed, @Nullable final String explanation) {
		final boolean skippedDestinationHasConnectionFacts = !connectionAttempted && (connectionAcquired || elapsed != null);
		final boolean elapsedIsNegative = elapsed != null && elapsed.isNegative();
		if (skippedDestinationHasConnectionFacts || elapsedIsNegative) {
			throw new IllegalArgumentException("A skipped destination has no acquisition duration or acquired connection; measured durations cannot be negative.");
		}
		if (!connectionAcquired && (explanation == null || explanation.isBlank())) {
			throw new IllegalArgumentException("Explain why the connection failed or why this destination was skipped.");
		}
	}

	private static void validateSmtpPort(@Nullable final Integer smtpPort) {
		if (smtpPort != null && (smtpPort < 1 || smtpPort > 65535)) {
			throw new IllegalArgumentException("Supply a logical SMTP port between 1 and 65535, or null when unknown.");
		}
	}

	private Object readResolve() {
		return new MailSendConnectionStep(configuredName, smtpHost, smtpPort, connectionAttempted, connectionAcquired, elapsed, explanation);
	}
}
