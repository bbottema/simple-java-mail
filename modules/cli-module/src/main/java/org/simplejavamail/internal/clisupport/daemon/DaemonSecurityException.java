package org.simplejavamail.internal.clisupport.daemon;

import java.io.IOException;

/** A private-state permission or ownership contract could not be established. */
final class DaemonSecurityException extends IOException {
	private static final long serialVersionUID = 1L;

	DaemonSecurityException(final String message) {
		super(message);
	}

	DaemonSecurityException(final String message, final IOException cause) {
		super(message, cause);
	}
}
