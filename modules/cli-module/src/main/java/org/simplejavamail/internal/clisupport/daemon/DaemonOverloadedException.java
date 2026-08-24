package org.simplejavamail.internal.clisupport.daemon;

/** Signals bounded daemon capacity rejection separately from a command execution failure. */
final class DaemonOverloadedException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	DaemonOverloadedException(final String message) {
		super(message);
	}
}
