package org.simplejavamail.api.mailer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable terminal facts for one logical email send attempt.
 * <p>
 * This type describes the complete Simple Java Mail operation, including preparation and scheduling. When SMTP submission was reached, the optional
 * {@link MailSubmissionReceipt} provides the transport-neutral acceptance facts. It does not describe final mailbox delivery.
 * <p>
 * Applications normally receive instances through a configured {@link MailSendResultObserver}.
 * <p>
 * Outgoing operation results expose unavailable scalar facts as {@code null}, including optional timestamps, identifiers and measurements.
 * Required facts remain non-null, while optional whole sections such as receipts, diagnostics and failures use non-null {@link Optional} values.
 * Collections are non-null with their documented empty meaning. No zero, false or empty-string value is substituted for an unknown fact.
 */
public interface MailSendOutcome extends Serializable {

	/**
	 * Lets you match this result to its progress updates, including updates from failover or retries of the same send.
	 * When telemetry is registered, the logical-send span carries the same ID, so you can also match it to this result.
	 * Simple Java Mail generates a new ID for each observed send but never transmits it with the email or uses it as a metric label.
	 * It is separate from the Message-ID, ENVID and telemetry trace ID.
	 *
	 * @return The observation ID, or {@code null} in older serialized outcomes that did not include one.
	 */
	@Nullable
	UUID getObservationId();

	/**
	 * @return The Message-ID present when the individual attempt began, or {@code null} when none was fixed by the caller.
	 */
	@Nullable
	String getInitialMessageId();

	/**
	 * @return The effective Message-ID after preparation and MIME conversion, or {@code null} when none was produced.
	 */
	@Nullable
	String getEffectiveMessageId();

	/**
	 * @return The time at which this individual email attempt began.
	 */
	@NotNull
	Instant getRequestedAt();

	/**
	 * @return The time the attempt became ready for execution admission, before destination-specific preparation. Returns {@code null} if admission readiness was not reached.
	 */
	@Nullable
	Instant getReadyAt();

	/**
	 * @return The time execution began, before destination-specific preparation. Returns {@code null} for rejected or unstarted cancelled/expired work.
	 */
	@Nullable
	Instant getStartedAt();

	/**
	 * @return The time at which the attempt reached its terminal result, before observer handoff or execution.
	 */
	@NotNull
	Instant getCompletedAt();

	/**
	 * @return Whether the configured send operation completed without throwing.
	 */
	boolean isSuccessful();

	/**
	 * @return Whether transport logging-only mode processed this email instead of invoking a sending transport.
	 */
	boolean isLoggingOnly();

	/**
	 * @return The transport-neutral receipt when this attempt reached a receipt-producing path. Preparation and scheduling failures have no receipt.
	 * A later cleanup failure does not erase a captured receipt: inspect SMTP acceptance separately from {@link #isSuccessful()}.
	 */
	@NotNull
	Optional<MailSubmissionReceipt> getSubmissionReceipt();

	/**
	 * @return The exact failure exposed to the caller (after normal future unwrapping), or empty for a successful attempt.
	 * Library-owned operational failures share {@link org.simplejavamail.MailException}; application extension failures retain their documented identity.
	 */
	@NotNull
	Optional<Throwable> getFailure();

	/**
	 * @return Actual-send timing and selected-endpoint diagnostics, captured automatically for observed attempts before observer dispatch.
	 * Empty when no measurements accompanied this outcome. This is not a connection probe or proof of delivery.
	 */
	@NotNull
	Optional<MailSendDiagnostics> getDiagnostics();
}
