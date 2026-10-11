package org.simplejavamail.api.internal.batchsupport;

/** Retains upstream evidence that a claim failed during physical connection setup, not because the pool was busy. */
public final class PoolConnectionSetupException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	public PoolConnectionSetupException(final Throwable cause) {
		super("The selected pool could not establish a usable SMTP connection.", cause);
	}
}
