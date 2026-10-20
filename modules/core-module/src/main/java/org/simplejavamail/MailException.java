package org.simplejavamail;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailFailureKind;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Common root for Simple Java Mail's operational failures during creation, conversion, validation and sending.
 * Specific exception types remain available for more focused catches; asynchronous completion exposes the same failure under
 * Java's normal future wrappers, and a terminal send observer receives the same caller-facing instance.
 * <p>
 * {@link #getKind()} identifies the problem, not retry safety. {@link #getSubmissionReceipt()} retains any known submission facts,
 * including acceptance before a later timeout, cancellation or cleanup failure. Missing facts never prove non-acceptance.
 * SMTP submission acceptance is not final mailbox delivery.
 * <p>
 * Invalid API arguments and programming errors are not converted to this root. Application extension callbacks retain their
 * documented propagation or isolation; arbitrary callback RuntimeExceptions and JVM Errors are not blanket-wrapped as library failures.
 */
@SuppressWarnings("serial")
public abstract class MailException extends RuntimeException {

	@NotNull private final MailFailureKind kind;
	@Nullable private final MailSubmissionReceipt submissionReceipt;

	/** Retains the existing subclass construction contract; unspecified application-defined failures have kind OTHER. */
	protected MailException(final String message) {
		this(MailFailureKind.OTHER, message, null, null);
	}

	/** Retains the existing subclass construction contract and original cause; unspecified failures have kind OTHER. */
	protected MailException(final String message, final Throwable cause) {
		this(MailFailureKind.OTHER, message, cause, null);
	}

	/** Initializes one classified failure with its original cause and any immutable facts captured for that operation. */
	protected MailException(@NotNull final MailFailureKind kind, final String message, @Nullable final Throwable cause,
			@Nullable final MailSubmissionReceipt submissionReceipt) {
		super(message, cause);
		this.kind = requireNonNull(kind, "kind");
		this.submissionReceipt = submissionReceipt;
	}

	/** Returns the reported problem category. It does not determine whether retrying is safe or any recipient was accepted. */
	@NotNull
	public final MailFailureKind getKind() { return kind == null ? MailFailureKind.OTHER : kind; }

	/**
	 * Returns captured submission facts, including acceptance before a later cleanup failure. Empty means unavailable, not proof of non-acceptance.
	 * Inspect retry guidance before resending; cancellation, timeouts and cleanup failures do not undo SMTP acceptance.
	 */
	@NotNull
	public final Optional<MailSubmissionReceipt> getSubmissionReceipt() { return Optional.ofNullable(submissionReceipt); }
}
