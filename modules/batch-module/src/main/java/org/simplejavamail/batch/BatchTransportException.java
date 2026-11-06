package org.simplejavamail.batch;

import org.simplejavamail.MailException;
import org.simplejavamail.api.mailer.MailFailureKind;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;

/**
 * Reports configuration, transport acquisition, scheduling, or shutdown failures from the batch facade.
 * <p>
 * Messages deliberately identify only the failed operation. They never render a Jakarta Mail {@code Session}
 * or its properties, because those properties can contain passwords and OAuth2 access tokens. The original cause
 * remains available through {@link #getCause()}.
 */
public class BatchTransportException extends MailException {

	private static final long serialVersionUID = 1L;

	/**
	 * Creates a batch transport exception.
	 *
	 * @param message a credential-safe description of the failed operation
	 */
	public BatchTransportException(final String message) {
		this(MailFailureKind.CONFIGURATION, message, null);
	}

	/**
	 * Creates a batch transport exception retaining the original cause.
	 *
	 * @param message a credential-safe description of the failed operation
	 * @param cause the original failure
	 */
	public BatchTransportException(final String message, final Throwable cause) {
		this(MailFailureKind.CONFIGURATION, message, cause);
	}

	/** Creates a classified batch failure while retaining its original cause and any captured submission facts. */
	public BatchTransportException(final MailFailureKind kind, final String message, final Throwable cause) {
		super(kind, message, cause, cause instanceof MailException ? ((MailException) cause).getSubmissionReceipt().orElse(null) : null);
	}

	/**
	 * Internal exception assembly for an acquisition failure after an earlier submission.
	 * @hidden
	 */
	@org.jetbrains.annotations.ApiStatus.Internal
	protected BatchTransportException(final BatchTransportException failure, final MailSubmissionReceipt receipt) {
		super(failure.getKind(), failure.getMessage(), failure.getCause(), receipt);
		setStackTrace(failure.getStackTrace());
		for (final Throwable suppressed : failure.getSuppressed()) { addSuppressed(suppressed); }
	}
}
