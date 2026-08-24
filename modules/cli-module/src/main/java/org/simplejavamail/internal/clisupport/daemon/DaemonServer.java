package org.simplejavamail.internal.clisupport.daemon;

import org.simplejavamail.internal.clisupport.CliExecutionEnvironment;
import org.simplejavamail.internal.clisupport.CliExecutionResult;
import org.simplejavamail.internal.clisupport.CliExitCode;
import org.simplejavamail.internal.clisupport.CliSupport;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns one foreground daemon instance from exclusive lock acquisition through final state cleanup.
 * Startup establishes private logging, a local authenticated transport, the reusable Mailer environment, and discovery
 * state before accepting requests. Shutdown stops intake first, drains accepted work, retires every cached Mailer, and
 * removes discovery state only when it still belongs to this process session.
 */
final class DaemonServer {
	private static final int CONNECTION_THREADS = 8;
	private static final int APPLICATION_THREADS = 4;
	private static final int QUEUE_SIZE = 32;

	enum State { NEW, LOCKED, BOUND, READY, QUIESCING, STOPPED, FAILED }

	private final DaemonPaths paths;
	private final DaemonStateStore stateStore;
	private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
	private final AtomicBoolean cleanupStarted = new AtomicBoolean();
	private final CountDownLatch stopped = new CountDownLatch(1);
	private final RequestLedger ledger = new RequestLedger();
	private final ThreadPoolExecutor connections = boundedExecutor("connection", CONNECTION_THREADS);
	private final ThreadPoolExecutor applications = boundedExecutor("request", APPLICATION_THREADS);
	private final UUID sessionId = UUID.randomUUID();
	private final byte[] authenticationSecret = randomSecret();
	private final byte[] profileSecret = randomSecret();
	private final Instant startedAt = Instant.now();

	private volatile LocalDaemonTransport.Server transport;
	private volatile DaemonMailerRegistry registry;
	private volatile CliExecutionEnvironment environment;

	DaemonServer(final String instance) {
		paths = DaemonPaths.forInstance(instance);
		stateStore = new DaemonStateStore(paths);
	}

	int run() {
		DaemonStateStore.LockHandle lock = null;
		Thread shutdownHook = null;
		try {
			lock = stateStore.tryLock();
			if (lock == null) {
				System.err.println("The selected Simple Java Mail daemon instance is already running or starting.");
				return CliExitCode.DAEMON_REFUSED.code();
			}
			state.set(State.LOCKED);
			initializeRuntime();
			publishDiscoveryState();
			state.set(State.READY);
			shutdownHook = installShutdownHook();
			acceptConnectionsUntilStopping();
			return CliExitCode.SUCCESS.code();
		} catch (Exception e) {
			state.set(State.FAILED);
			System.err.println("Unable to start the Simple Java Mail daemon: " + safeMessage(e));
			return e instanceof SecurityException || e instanceof DaemonSecurityException
					? CliExitCode.DAEMON_SECURITY.code() : CliExitCode.DAEMON_START_FAILED.code();
		} finally {
			cleanup(lock);
			if (shutdownHook != null && Thread.currentThread() != shutdownHook) {
				try {
					Runtime.getRuntime().removeShutdownHook(shutdownHook);
				} catch (IllegalStateException ignored) {
					// JVM shutdown is already in progress.
				}
			}
		}
	}

	private void initializeRuntime() throws IOException {
		configureDaemonLogging();
		transport = LocalDaemonTransport.openServer(paths);
		state.set(State.BOUND);
		registry = new DaemonMailerRegistry();
		environment = new CliExecutionEnvironment(CliExecutionEnvironment.loadConventionalConfig(), registry);
	}

	private void publishDiscoveryState() throws IOException {
		final ProcessHandle process = ProcessHandle.current();
		final long processStart = process.info().startInstant().orElse(startedAt).toEpochMilli();
		stateStore.write(new DaemonDiscovery(paths.instance(), DaemonVersion.PRODUCT_VERSION,
				DaemonVersion.PROTOCOL_MAJOR, DaemonVersion.PROTOCOL_MINOR, sessionId, process.pid(), processStart,
				transport.endpoint(), authenticationSecret, Instant.now()));
	}

	private Thread installShutdownHook() {
		final Thread shutdownHook = new Thread(this::shutdownFromHook, "sjm-daemon-shutdown");
		Runtime.getRuntime().addShutdownHook(shutdownHook);
		return shutdownHook;
	}

	private void configureDaemonLogging() throws IOException {
		if (!Files.exists(paths.logFile())) {
			Files.write(paths.logFile(), new byte[0], StandardOpenOption.CREATE_NEW);
		}
		DaemonStateSecurity.ensurePrivateFile(paths.logFile());
		System.setProperty("simplejavamail.cli.daemon.log-file", paths.logFile().toString());
		if (System.getProperty("log4j.configurationFile") == null) {
			System.setProperty("log4j.configurationFile", "classpath:log4j2-daemon.xml");
		}
	}

	private void shutdownFromHook() {
		requestStop();
		try {
			stopped.await(75, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void acceptConnectionsUntilStopping() throws IOException {
		while (state.get() == State.READY) {
			final SocketChannel channel;
			try {
				channel = transport.accept();
			} catch (IOException e) {
				if (state.get() == State.QUIESCING) {
					return;
				}
				throw e;
			}
			try {
				connections.execute(new ConnectionTask(channel));
			} catch (RejectedExecutionException e) {
				channel.close();
			}
		}
	}

	@SuppressWarnings("try")
	private void serveConnection(final SocketChannel channel) {
		try (channel) {
			final DaemonRequest request;
			try (DaemonIoDeadline ignored = DaemonIoDeadline.after(channel, Duration.ofSeconds(10))) {
				request = DaemonProtocol.readRequest(channel, sessionId, authenticationSecret);
			}
			final CliExecutionResult result = switch (request.operation()) {
				case READY -> readyResult();
				case STATUS -> statusResult();
				case STOP -> stopResult();
				case EXECUTE -> executeApplicationRequest(request);
			};
			try (DaemonIoDeadline ignored = DaemonIoDeadline.after(channel, Duration.ofSeconds(10))) {
				DaemonProtocol.writeResponse(channel, DaemonResponse.from(sessionId, request.requestId(), result),
						authenticationSecret);
			}
		} catch (DaemonProtocolException ignored) {
			// Invalid or unauthenticated frames intentionally receive no oracle response.
		} catch (IOException ignored) {
			// Accepted application work continues through its ledger after a client disconnects.
		} catch (RuntimeException ignored) {
			// Authenticated but invalid input must not terminate a connection worker.
		}
	}

	private CliExecutionResult executeApplicationRequest(final DaemonRequest request) {
		if (state.get() != State.READY) {
			return result(CliExitCode.DAEMON_BUSY, "The daemon is shutting down.");
		}
		final RequestLedger.Registration registration;
		try {
			registration = ledger.register(request);
		} catch (DaemonOverloadedException e) {
			return result(CliExitCode.DAEMON_BUSY, e.getMessage());
		} catch (IllegalArgumentException e) {
			return result(CliExitCode.DAEMON_REFUSED, e.getMessage());
		}
		if (registration.newlyRegistered()) {
			submitApplicationRequest(request, registration);
		}
		return registration.result().join();
	}

	private void submitApplicationRequest(final DaemonRequest request, final RequestLedger.Registration registration) {
		try {
			applications.execute(() -> completeApplicationRequest(request, registration));
		} catch (RejectedExecutionException e) {
			registration.result().complete(result(CliExitCode.DAEMON_BUSY, "The daemon request queue is full."));
		}
	}

	private void completeApplicationRequest(final DaemonRequest request,
			final RequestLedger.Registration registration) {
		try {
			registration.result().complete(CliSupport.execute(request.arguments().toArray(new String[0]),
					request.workingDirectory(), request.requestId(), environment, profileSecret));
		} catch (RuntimeException e) {
			registration.result().complete(result(CliExitCode.COMMAND_FAILED, safeMessage(e)));
		}
	}

	private CliExecutionResult readyResult() {
		return state.get() == State.READY
				? new CliExecutionResult(CliExitCode.SUCCESS, "ready" + System.lineSeparator(), "")
				: result(CliExitCode.DAEMON_BUSY, "The daemon is not ready.");
	}

	private CliExecutionResult statusResult() {
		final long uptime = Math.max(0, Duration.between(startedAt, Instant.now()).toMillis());
		final String output = "instance=" + paths.instance() + '\n'
				+ "state=" + state.get() + '\n'
				+ "pid=" + ProcessHandle.current().pid() + '\n'
				+ "uptimeMillis=" + uptime + '\n'
				+ "version=" + DaemonVersion.PRODUCT_VERSION + '\n'
				+ "protocol=" + DaemonVersion.PROTOCOL_MAJOR + "." + DaemonVersion.PROTOCOL_MINOR + '\n'
				+ "transport=" + transport.endpoint().kind() + '\n'
				+ "transportReason=" + transport.endpoint().selectionReason() + '\n'
				+ "activeRequests=" + applications.getActiveCount() + '\n'
				+ "queuedRequests=" + applications.getQueue().size() + '\n'
				+ "mailers=" + registry.entryCount() + '\n'
				+ "activeMailerLeases=" + registry.activeLeaseCount() + '\n'
				+ "mailerCloseFailures=" + registry.closeFailureCount() + '\n';
		final String ledgerStatus = "recentRequests=" + ledger.pruneExpiredEntriesAndCount() + '\n'
				+ "retainedResultBytes=" + ledger.retainedResultBytes() + '\n';
		return new CliExecutionResult(CliExitCode.SUCCESS, output + ledgerStatus, "");
	}

	private CliExecutionResult stopResult() {
		requestStop();
		return new CliExecutionResult(CliExitCode.SUCCESS, "Daemon stopping." + System.lineSeparator(), "");
	}

	private void requestStop() {
		final State current = state.get();
		if ((current == State.READY || current == State.BOUND) && state.compareAndSet(current, State.QUIESCING)) {
			closeTransportQuietly();
		}
	}

	private void cleanup(final DaemonStateStore.LockHandle lock) {
		if (!cleanupStarted.compareAndSet(false, true)) {
			return;
		}
		stopAcceptingConnections();
		drainApplicationRequests();
		drainConnectionWorkers();
		retireMailerRegistry();
		removeOwnedDiscoveryState();
		closeInstanceLock(lock);
		state.set(State.STOPPED);
		stopped.countDown();
	}

	private void stopAcceptingConnections() {
		state.compareAndSet(State.READY, State.QUIESCING);
		closeTransportQuietly();
	}

	private void closeTransportQuietly() {
		try {
			if (transport != null) {
				transport.close();
			}
		} catch (IOException ignored) {
			// Final cleanup still drains accepted work and removes state owned by this session.
		}
	}

	private void drainApplicationRequests() {
		applications.shutdown();
		if (!awaitTerminationOrCancelQueuedWork(applications, 30)) {
			ledger.completeIncomplete(result(CliExitCode.DAEMON_AMBIGUOUS,
					"The daemon stopped waiting for an accepted command; its terminal outcome is unknown."));
		}
	}

	private void drainConnectionWorkers() {
		connections.shutdown();
		awaitTerminationOrCancelQueuedWork(connections, 30);
	}

	private void retireMailerRegistry() {
		if (registry != null) {
			registry.closeAfterExecutorDrain();
		}
	}

	private void removeOwnedDiscoveryState() {
		try {
			stateStore.removeIfOwned(sessionId);
		} catch (IOException ignored) {
			// Shutdown must still release the instance lock; clients reject any stale process identity left behind.
		}
	}

	private static void closeInstanceLock(final DaemonStateStore.LockHandle lock) {
		if (lock != null) {
			try {
				lock.close();
			} catch (IOException ignored) {
				// There is no recoverable lock action once process shutdown has reached this point.
			}
		}
	}

	private static ThreadPoolExecutor boundedExecutor(final String role, final int threads) {
		return new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(QUEUE_SIZE),
				runnable -> {
					final Thread thread = new Thread(runnable, "sjm-daemon-" + role);
					thread.setDaemon(false);
					return thread;
				}, new ThreadPoolExecutor.AbortPolicy());
	}

	private static boolean awaitTerminationOrCancelQueuedWork(final ThreadPoolExecutor executor, final int seconds) {
		try {
			if (!executor.awaitTermination(seconds, TimeUnit.SECONDS)) {
				closeAbandonedConnections(executor.shutdownNow());
				return false;
			}
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			closeAbandonedConnections(executor.shutdownNow());
			return false;
		}
	}

	private static void closeAbandonedConnections(final Iterable<Runnable> abandoned) {
		for (final Runnable task : abandoned) {
			if (task instanceof ConnectionTask connectionTask) {
				connectionTask.close();
			}
		}
	}

	private static byte[] randomSecret() {
		final byte[] secret = new byte[32];
		new SecureRandom().nextBytes(secret);
		return secret;
	}

	private static CliExecutionResult result(final CliExitCode category, final String message) {
		return new CliExecutionResult(category, "", message + System.lineSeparator());
	}

	private static String safeMessage(final Throwable throwable) {
		return throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
	}

	/** Retains an accepted channel so forced executor shutdown can close work that never started. */
	private final class ConnectionTask implements Runnable {
		private final SocketChannel channel;

		private ConnectionTask(final SocketChannel channel) {
			this.channel = channel;
		}

		@Override
		public void run() {
			serveConnection(channel);
		}

		private void close() {
			try {
				channel.close();
			} catch (IOException ignored) {
				// Forced shutdown also reaches channels already closed by their connection worker.
			}
		}
	}
}
