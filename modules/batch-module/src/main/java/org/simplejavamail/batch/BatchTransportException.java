package org.simplejavamail.batch;

/**
 * Reports configuration, transport acquisition, scheduling, or shutdown failures from the batch facade.
 * <p>
 * Messages deliberately identify only the failed operation. They never render a Jakarta Mail {@code Session}
 * or its properties, because those properties can contain passwords and OAuth2 access tokens. The original cause
 * remains available through {@link #getCause()}.
 */
public class BatchTransportException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * Creates a batch transport exception.
	 *
	 * @param message a credential-safe description of the failed operation
	 */
	public BatchTransportException(final String message) {
		super(message);
	}

	/**
	 * Creates a batch transport exception retaining the original cause.
	 *
	 * @param message a credential-safe description of the failed operation
	 * @param cause the original failure
	 */
	public BatchTransportException(final String message, final Throwable cause) {
		super(message, cause);
	}
}
