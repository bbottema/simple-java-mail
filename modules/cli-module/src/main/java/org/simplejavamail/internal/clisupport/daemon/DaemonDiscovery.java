package org.simplejavamail.internal.clisupport.daemon;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Private discovery record through which a client proves which process, endpoint, protocol, and session it reached.
 * The authentication secret is defensively copied so reading state cannot grant mutable access to server identity.
 */
record DaemonDiscovery(String instance, String productVersion, short protocolMajor, short protocolMinor,
		UUID sessionId, long pid, long processStartMillis, DaemonEndpoint endpoint, byte[] authenticationSecret,
		Instant readyAt) {
	DaemonDiscovery {
		Objects.requireNonNull(instance, "instance");
		Objects.requireNonNull(productVersion, "productVersion");
		Objects.requireNonNull(sessionId, "sessionId");
		Objects.requireNonNull(endpoint, "endpoint");
		Objects.requireNonNull(authenticationSecret, "authenticationSecret");
		Objects.requireNonNull(readyAt, "readyAt");
		if (authenticationSecret.length != 32) {
			throw new IllegalArgumentException("Daemon authentication secrets must contain 32 bytes");
		}
		authenticationSecret = authenticationSecret.clone();
	}

	@Override
	public byte[] authenticationSecret() {
		return authenticationSecret.clone();
	}

	boolean compatible() {
		return protocolMajor == DaemonVersion.PROTOCOL_MAJOR && protocolMinor == DaemonVersion.PROTOCOL_MINOR
				&& productVersion.equals(DaemonVersion.PRODUCT_VERSION);
	}
}
