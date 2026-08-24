package org.simplejavamail.internal.clisupport.daemon;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Authenticated command or lifecycle request with correlation identity and the client's execution context. */
record DaemonRequest(DaemonOperation operation, UUID sessionId, UUID requestId, Instant timestamp,
		Path workingDirectory, List<String> arguments) {
	DaemonRequest {
		arguments = List.copyOf(arguments);
	}
}
