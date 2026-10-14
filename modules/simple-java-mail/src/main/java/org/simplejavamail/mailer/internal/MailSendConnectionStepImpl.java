package org.simplejavamail.mailer.internal;

import lombok.Builder;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailSendConnectionStep;
import org.simplejavamail.internal.util.SmtpDiagnosticText;

import java.time.Duration;
import java.util.Optional;

/** Library-owned immutable implementation of {@link MailSendConnectionStep}. */
@ApiStatus.Internal
public final class MailSendConnectionStepImpl implements MailSendConnectionStep {
	private static final long serialVersionUID = 1L;
	@Nullable private final String configuredName;
	@Nullable private final String smtpHost;
	@Nullable private final Integer smtpPort;
	private final boolean connectionAttempted;
	private final boolean connectionAcquired;
	@Nullable private final Duration elapsed;
	@Nullable private final String explanation;

	/** Copies and validates the supplied observations; it performs no send work. */
	@Builder
	private MailSendConnectionStepImpl(@Nullable final String configuredName, @Nullable final String smtpHost, @Nullable final Integer smtpPort,
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

	/** @see MailSendConnectionStep#getConfiguredName() */
	@Override
	@NotNull
	public Optional<String> getConfiguredName() {
		return Optional.ofNullable(configuredName);
	}
	/** @see MailSendConnectionStep#getSmtpHost() */
	@Override
	@NotNull
	public Optional<String> getSmtpHost() {
		return Optional.ofNullable(smtpHost);
	}
	/** @see MailSendConnectionStep#getSmtpPort() */
	@Override
	@NotNull
	public Optional<Integer> getSmtpPort() {
		return Optional.ofNullable(smtpPort);
	}
	/** @see MailSendConnectionStep#isConnectionAttempted() */
	@Override
	public boolean isConnectionAttempted() {
		return connectionAttempted;
	}
	/** @see MailSendConnectionStep#isConnectionAcquired() */
	@Override
	public boolean isConnectionAcquired() {
		return connectionAcquired;
	}
	/** @see MailSendConnectionStep#getElapsed() */
	@Override
	@NotNull
	public Optional<Duration> getElapsed() {
		return Optional.ofNullable(elapsed);
	}
	/** @see MailSendConnectionStep#getExplanation() */
	@Override
	@NotNull
	public Optional<String> getExplanation() {
		return Optional.ofNullable(explanation);
	}

	/** @see MailSendConnectionStep#toString() */
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
}
