package org.simplejavamail.internal.clisupport.daemon;

import java.util.Objects;
import java.util.Set;

/** A validated local endpoint plus the bounded reason explaining why that transport was selected. */
record DaemonEndpoint(Kind kind, String address, String selectionReason) {
	private static final Set<String> SELECTION_REASONS = Set.of("unix-preferred", "tcp-forced",
			"tcp-unix-unsupported", "tcp-unix-path-limit", "tcp-unix-unavailable", "tcp-unspecified");

	DaemonEndpoint {
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(address, "address");
		Objects.requireNonNull(selectionReason, "selectionReason");
		if (address.isBlank() || address.length() > 4096 || address.indexOf('\0') >= 0
				|| address.indexOf('\r') >= 0 || address.indexOf('\n') >= 0) {
			throw new IllegalArgumentException("Invalid daemon endpoint address");
		}
		if (kind == Kind.TCP) {
			final int separator = address.lastIndexOf(':');
			final int port = parseTcpPort(address, separator);
			if (!"127.0.0.1".equals(address.substring(0, Math.max(0, separator))) || port < 1 || port > 65535) {
				throw new IllegalArgumentException("Invalid daemon TCP endpoint");
			}
		}
		if (!SELECTION_REASONS.contains(selectionReason)) {
			throw new IllegalArgumentException("Invalid daemon transport-selection reason");
		}
	}

	private static int parseTcpPort(final String address, final int separator) {
		try {
			return separator < 1 ? -1 : Integer.parseInt(address.substring(separator + 1));
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Invalid daemon TCP endpoint", e);
		}
	}

	DaemonEndpoint(final Kind kind, final String address) {
		this(kind, address, kind == Kind.UNIX ? "unix-preferred" : "tcp-unspecified");
	}

	/** The only transport families permitted in daemon discovery state. */
	enum Kind { UNIX, TCP }
}
