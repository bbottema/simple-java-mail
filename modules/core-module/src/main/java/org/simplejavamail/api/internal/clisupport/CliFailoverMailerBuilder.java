package org.simplejavamail.api.internal.clisupport;

import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.mailer.MailerGenericBuilder;

import java.time.Duration;

import static org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType.MAILER;

/** Internal text boundary for generated CLI options. Java callers configure the paired Mailer and policy API instead. */
@Cli.BuilderApiNode(builderApiType = MAILER)
public interface CliFailoverMailerBuilder {
	/**
	 * Selects a named failover destination from the captured configuration, such as {@code secondary} defined by
	 * {@code simplejavamail.mailers.secondary.*}. It is used only after an eligible connection failure and never replays a submitted email.
	 *
	 * @param name Name of the independently configured Mailer definition.
	 */
	@NotNull
	MailerGenericBuilder<?> withFailoverMailerName(@NotNull String name);

	/**
	 * Allows this many connection setup attempts per candidate before moving on. The first attempt is included; default one.
	 *
	 * @param attempts Positive total number of setup attempts, not message retries.
	 */
	@NotNull
	MailerGenericBuilder<?> withFailoverPrimaryConnectionAttempts(int attempts);

	/**
	 * Sets the delay between explicitly enabled additional connection attempts. Default {@code PT0S}.
	 *
	 * @param delay Non-negative ISO-8601 duration, for example {@code PT1S}.
	 */
	@NotNull
	MailerGenericBuilder<?> withFailoverPrimaryRetryDelay(@NotNull Duration delay);

	/**
	 * Sets when a failed primary may be checked again. Working failover sends continue without this delay. Default {@code PT30S}.
	 *
	 * @param interval Positive ISO-8601 duration, for example {@code PT30S}.
	 */
	@NotNull
	MailerGenericBuilder<?> withFailoverPrimaryRecheckInterval(@NotNull Duration interval);

	/**
	 * Increases the primary's reconsideration interval after repeated failures, without slowing a working failover destination.
	 *
	 * @param multiplier Integer greater than one.
	 * @param maximumInterval Positive ISO-8601 maximum at least as long as the initial recheck interval.
	 */
	@NotNull
	MailerGenericBuilder<?> withFailoverPrimaryRecheckBackoff(int multiplier, @NotNull Duration maximumInterval);

	/**
	 * Allows another configured destination if the primary rejects its credentials. Disabled by default.
	 *
	 * @param enabled Whether to permit failover on authentication rejection.
	 */
	@NotNull
	MailerGenericBuilder<?> withFailoverOnAuthenticationFailure(boolean enabled);

	/**
	 * Allows another configured destination after certificate or hostname verification fails, without disabling either destination's TLS checks.
	 *
	 * @param enabled Whether to permit this failure mode, independently of authentication. Default false.
	 */
	@NotNull
	MailerGenericBuilder<?> withFailoverOnCertificateVerificationFailure(boolean enabled);
}
