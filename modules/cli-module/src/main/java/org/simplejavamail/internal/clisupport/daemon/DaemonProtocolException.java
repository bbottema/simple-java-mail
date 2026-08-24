package org.simplejavamail.internal.clisupport.daemon;

import java.io.IOException;

/** Categorizes rejected wire input so callers can distinguish incompatibility from local security failures. */
final class DaemonProtocolException extends IOException {
	private static final long serialVersionUID = 1L;

	enum Kind { MALFORMED, AUTHENTICATION, INCOMPATIBLE, STALE }

	private final Kind kind;

	DaemonProtocolException(final Kind kind, final String message) {
		super(message);
		this.kind = kind;
	}

	Kind kind() {
		return kind;
	}
}
