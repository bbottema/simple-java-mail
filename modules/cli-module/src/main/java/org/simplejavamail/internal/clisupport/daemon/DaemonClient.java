package org.simplejavamail.internal.clisupport.daemon;

import org.simplejavamail.internal.clisupport.CliExecutionResult;
import org.simplejavamail.internal.clisupport.CliExitCode;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

/**
 * Discovers, authenticates, and invokes one selected per-user daemon instance.
 * Before connecting it verifies the private state record, derived endpoint, compatible version, and PID/start-time
 * identity. Once an application request may have been submitted, transport failure is reported as ambiguous and is
 * never hidden by a one-shot fallback or automatic retry.
 */
final class DaemonClient {
	private final DaemonPaths paths;
	private final DaemonStateStore stateStore;

	DaemonClient(final String instance) {
		paths = DaemonPaths.forInstance(instance);
		stateStore = new DaemonStateStore(paths);
	}

	CliExecutionResult ready() {
		return request(DaemonOperation.READY, Path.of(""), new String[0], UUID.randomUUID());
	}

	CliExecutionResult status() {
		return request(DaemonOperation.STATUS, Path.of(""), new String[0], UUID.randomUUID());
	}

	CliExecutionResult stop() {
		final DiscoveryResult discoveryResult = discover();
		if (discoveryResult.failed()) {
			return discoveryResult.failure();
		}
		final CliExecutionResult result = sendAuthenticatedRequest(discoveryResult.discovery(), DaemonOperation.STOP, Path.of(""),
				new String[0], UUID.randomUUID());
		if (result.category() == CliExitCode.SUCCESS) {
			return awaitTermination(discoveryResult.discovery(), result);
		}
		return result;
	}

	CliExecutionResult execute(final Path workingDirectory, final String[] arguments) {
		return request(DaemonOperation.EXECUTE, workingDirectory, arguments, UUID.randomUUID());
	}

	private CliExecutionResult request(final DaemonOperation operation, final Path workingDirectory,
			final String[] arguments, final UUID requestId) {
		final DiscoveryResult discoveryResult = discover();
		return discoveryResult.failed() ? discoveryResult.failure()
				: sendAuthenticatedRequest(discoveryResult.discovery(), operation, workingDirectory, arguments, requestId);
	}

	@SuppressWarnings("try")
	private CliExecutionResult sendAuthenticatedRequest(final DaemonDiscovery discovery, final DaemonOperation operation,
			final Path workingDirectory, final String[] arguments, final UUID requestId) {
		boolean submitted = false;
		try (SocketChannel channel = LocalDaemonTransport.connect(discovery.endpoint());
			 DaemonIoDeadline ignored = DaemonIoDeadline.after(channel,
					 operation == DaemonOperation.EXECUTE ? Duration.ofMinutes(10) : Duration.ofSeconds(10))) {
			final DaemonRequest request = new DaemonRequest(operation, discovery.sessionId(), requestId, Instant.now(),
					workingDirectory.toAbsolutePath().normalize(), Arrays.asList(arguments.clone()));
			DaemonProtocol.writeRequest(channel, request, discovery.authenticationSecret());
			submitted = true;
			final DaemonResponse response = DaemonProtocol.readResponse(channel, discovery.sessionId(), requestId,
					discovery.authenticationSecret());
			if (!response.daemonVersion().equals(discovery.productVersion())) {
				return failure(CliExitCode.DAEMON_INCOMPATIBLE, "Daemon version changed; restart the selected instance.");
			}
			return response.executionResult();
		} catch (DaemonProtocolException e) {
			return failure(switch (e.kind()) {
				case INCOMPATIBLE -> CliExitCode.DAEMON_INCOMPATIBLE;
				case AUTHENTICATION, MALFORMED, STALE -> CliExitCode.DAEMON_SECURITY;
			}, e.getMessage());
		} catch (IOException | RuntimeException e) {
			if (submitted && operation == DaemonOperation.EXECUTE) {
				return failure(CliExitCode.DAEMON_AMBIGUOUS,
						"The daemon accepted the request connection but its outcome is unknown; it was not retried.");
			}
			if (submitted) {
				return failure(CliExitCode.DAEMON_SECURITY, "The daemon did not return an authenticated response.");
			}
			return failure(operation == DaemonOperation.EXECUTE ? CliExitCode.DAEMON_ABSENT : CliExitCode.DAEMON_REFUSED,
					"Unable to contact the selected daemon.");
		}
	}

	private static CliExecutionResult awaitTermination(final DaemonDiscovery discovery,
			final CliExecutionResult successfulResponse) {
		final ProcessHandle handle = ProcessHandle.of(discovery.pid()).orElse(null);
		if (handle == null) {
			return successfulResponse;
		}
		try {
			handle.onExit().get(75, java.util.concurrent.TimeUnit.SECONDS);
			return successfulResponse;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return failure(CliExitCode.DAEMON_REFUSED, "Interrupted while waiting for the daemon to stop.");
		} catch (Exception e) {
			return failure(CliExitCode.DAEMON_BUSY, "The daemon accepted stop but has not terminated yet.");
		}
	}

	private DiscoveryResult discover() {
		final DaemonDiscovery discovery;
		try {
			discovery = stateStore.read();
		} catch (IOException | SecurityException e) {
			return DiscoveryResult.failed(CliExitCode.DAEMON_SECURITY,
					"Daemon discovery state is not private or valid.");
		}
		if (discovery == null) {
			return DiscoveryResult.failed(CliExitCode.DAEMON_ABSENT, "No selected daemon is running.");
		}
		if (!discovery.instance().equals(paths.instance()) || discovery.authenticationSecret().length != 32) {
			return DiscoveryResult.failed(CliExitCode.DAEMON_SECURITY, "Daemon discovery identity is invalid.");
		}
		final DiscoveryResult endpointValidation = validateEndpoint(discovery);
		if (endpointValidation.failed()) {
			return endpointValidation;
		}
		if (!discovery.compatible()) {
			return DiscoveryResult.failed(CliExitCode.DAEMON_INCOMPATIBLE,
					"The selected daemon is incompatible; stop it with its matching CLI and restart.");
		}
		return validateProcessIdentity(discovery);
	}

	private DiscoveryResult validateEndpoint(final DaemonDiscovery discovery) {
		if (discovery.endpoint().kind() == DaemonEndpoint.Kind.UNIX) {
			try {
				if (!paths.isDerivedSocket(Path.of(discovery.endpoint().address()))) {
					return DiscoveryResult.failed(CliExitCode.DAEMON_SECURITY,
							"Daemon discovery endpoint is not derived from the selected instance.");
				}
			} catch (RuntimeException e) {
				return DiscoveryResult.failed(CliExitCode.DAEMON_SECURITY,
						"Daemon discovery endpoint is invalid.");
			}
		}
		return DiscoveryResult.found(discovery);
	}

	private static DiscoveryResult validateProcessIdentity(final DaemonDiscovery discovery) {
		final ProcessHandle process = ProcessHandle.of(discovery.pid()).orElse(null);
		if (process == null || !process.isAlive()) {
			return DiscoveryResult.failed(CliExitCode.DAEMON_ABSENT, "The selected daemon state is stale.");
		}
		final Instant processStart = process.info().startInstant().orElse(null);
		if (processStart == null || Math.abs(processStart.toEpochMilli() - discovery.processStartMillis()) > 2000) {
			return DiscoveryResult.failed(CliExitCode.DAEMON_REFUSED,
					"The discovered process cannot be proven to be the selected daemon.");
		}
		return DiscoveryResult.found(discovery);
	}

	DaemonPaths paths() {
		return paths;
	}

	private static CliExecutionResult failure(final CliExitCode category, final String message) {
		return new CliExecutionResult(category, "", message + System.lineSeparator());
	}

	private record DiscoveryResult(DaemonDiscovery discovery, CliExecutionResult failure) {
		private static DiscoveryResult found(final DaemonDiscovery discovery) {
			return new DiscoveryResult(discovery, null);
		}

		private static DiscoveryResult failed(final CliExitCode category, final String message) {
			return new DiscoveryResult(null, DaemonClient.failure(category, message));
		}

		private boolean failed() {
			return failure != null;
		}
	}
}
