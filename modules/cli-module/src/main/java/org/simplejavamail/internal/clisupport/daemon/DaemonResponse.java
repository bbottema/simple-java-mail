package org.simplejavamail.internal.clisupport.daemon;

import org.simplejavamail.internal.clisupport.CliExecutionResult;
import org.simplejavamail.internal.clisupport.CliExitCode;

import java.time.Instant;
import java.util.UUID;

/** Terminal execution result correlated to the authenticated daemon session and client request. */
record DaemonResponse(UUID sessionId, UUID requestId, Instant timestamp, CliExitCode category,
		String stdout, String stderr, String daemonVersion) {
	static DaemonResponse from(final UUID sessionId, final UUID requestId, final CliExecutionResult result) {
		return new DaemonResponse(sessionId, requestId, Instant.now(), result.category(), result.stdout(), result.stderr(),
				DaemonVersion.PRODUCT_VERSION);
	}

	CliExecutionResult executionResult() {
		return new CliExecutionResult(category, stdout, stderr);
	}
}
