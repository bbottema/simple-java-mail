package org.simplejavamail.internal.clisupport.daemon;

/** Stable wire-level operations supported by the local daemon protocol. */
enum DaemonOperation {
	READY(1),
	STATUS(2),
	STOP(3),
	EXECUTE(4);

	private final int code;

	DaemonOperation(final int code) {
		this.code = code;
	}

	int code() {
		return code;
	}

	static DaemonOperation fromCode(final int code) throws DaemonProtocolException {
		for (final DaemonOperation operation : values()) {
			if (operation.code == code) {
				return operation;
			}
		}
		throw new DaemonProtocolException(DaemonProtocolException.Kind.MALFORMED, "Unknown daemon operation");
	}
}
