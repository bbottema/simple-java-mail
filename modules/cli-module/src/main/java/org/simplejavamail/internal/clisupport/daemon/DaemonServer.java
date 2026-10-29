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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

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
	private static final int REPLY_CAPACITY = CONNECTION_THREADS + QUEUE_SIZE;

	enum State { NEW, LOCKED, BOUND, READY, QUIESCING, STOPPED, FAILED }

	private final DaemonPaths paths;
	private final DaemonStateStore stateStore;
	private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
	private final AtomicBoolean cleanupStarted = new AtomicBoolean();
	private final CountDownLatch stopped = new CountDownLatch(1);
	private final RequestLedger ledger = new RequestLedger();
	private final Object admissionGate = new Object();
	private final Semaphore replyCapacity = new Semaphore(REPLY_CAPACITY);
	// Keep channels registered across intake-to-reply handoff, without sharing admission permits with controls.
	private final Set<SocketChannel> openConnections = ConcurrentHashMap.newKeySet();
	// The admission gate also owns each NEW task's applicationSubmitted flag.
	private final Set<ExecutionReplyTask> replyOwners = new HashSet<>();
	private final ThreadPoolExecutor connections = boundedExecutor("connection", CONNECTION_THREADS);
	private final ThreadPoolExecutor executionReplies = boundedExecutor("execution-reply", CONNECTION_THREADS, REPLY_CAPACITY);
	private final ThreadPoolExecutor applications = boundedExecutor("request", APPLICATION_THREADS);
	private final Function<DaemonRequest, CliExecutionResult> commandExecutor;
	private final UUID sessionId = UUID.randomUUID();
	private final byte[] authenticationSecret = randomSecret();
	private final byte[] profileSecret = randomSecret();
	private final Instant startedAt = Instant.now();

	private volatile LocalDaemonTransport.Server transport;
	private volatile DaemonMailerRegistry registry;
	private volatile CliExecutionEnvironment environment;

	DaemonServer(final String instance) {
		this(instance, null);
	}

	/** Keeps the command boundary separate from authenticated request and channel ownership. */
	DaemonServer(final String instance, final Function<DaemonRequest, CliExecutionResult> commandExecutor) {
		paths = DaemonPaths.forInstance(instance);
		stateStore = new DaemonStateStore(paths);
		this.commandExecutor = commandExecutor == null ? this::executeCliCommand : commandExecutor;
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
			synchronized (admissionGate) {
				state.set(State.FAILED);
				refuseUnadmittedRequests();
			}
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
			openConnections.add(channel);
			try {
				connections.execute(new ConnectionTask(channel));
			} catch (RejectedExecutionException e) {
				closeConnection(channel);
			}
		}
	}

	@SuppressWarnings("try")
	private void serveConnection(final SocketChannel channel) {
		boolean transferred = false;
		try {
			final DaemonRequest request;
			try (DaemonIoDeadline ignored = DaemonIoDeadline.after(channel, Duration.ofSeconds(10))) {
				request = DaemonProtocol.readRequest(channel, sessionId, authenticationSecret);
			}
			if (request.operation() == DaemonOperation.EXECUTE) {
				final CliExecutionResult refusal = transferExecutionRequest(channel, request);
				if (refusal == null) {
					transferred = true;
					return;
				}
				writeResponse(channel, request, refusal);
				return;
			}
			final CliExecutionResult result = switch (request.operation()) {
				case READY -> readyResult();
				case STATUS -> statusResult();
				case STOP -> stopResult();
				case EXECUTE -> throw new IllegalStateException("Execution requests require reply ownership");
			};
			writeResponse(channel, request, result);
		} catch (DaemonProtocolException ignored) {
			// Invalid or unauthenticated frames intentionally receive no oracle response.
		} catch (IOException ignored) {
			// Accepted application work continues through its ledger after a client disconnects.
		} catch (RuntimeException ignored) {
			// Authenticated but invalid input must not terminate a connection worker.
		} finally {
			if (!transferred) {
				closeConnection(channel);
			}
		}
	}

	@SuppressWarnings("try")
	private void writeResponse(final SocketChannel channel, final DaemonRequest request, final CliExecutionResult result)
			throws IOException {
		try (DaemonIoDeadline ignored = DaemonIoDeadline.after(channel, Duration.ofSeconds(10))) {
			DaemonProtocol.writeResponse(channel, DaemonResponse.from(sessionId, request.requestId(), result), authenticationSecret);
		}
	}

	/** A null refusal transfers channel ownership; capacity is reserved before the ledger can retain a new identity. */
	private CliExecutionResult transferExecutionRequest(final SocketChannel channel, final DaemonRequest request) {
		synchronized (admissionGate) {
			if (state.get() != State.READY) {
				return shuttingDown();
			}
			if (!replyCapacity.tryAcquire()) {
				return result(CliExitCode.DAEMON_BUSY, "Daemon execution reply capacity is full.");
			}
			final RequestLedger.Registration registration;
			try {
				registration = ledger.register(request);
			} catch (DaemonOverloadedException e) {
				replyCapacity.release();
				return result(CliExitCode.DAEMON_BUSY, e.getMessage());
			} catch (IllegalArgumentException e) {
				replyCapacity.release();
				return result(CliExitCode.DAEMON_REFUSED, e.getMessage());
			} catch (RuntimeException | Error failure) {
				replyCapacity.release();
				throw failure;
			}
			final ExecutionReplyTask owner = new ExecutionReplyTask(channel, request, registration);
			replyOwners.add(owner);
			try {
				// The backing queue has room for every reserved owner, including a worker just finishing its prior task.
				executionReplies.execute(owner);
				return null;
			} catch (RejectedExecutionException e) {
				owner.refuseIfUnadmitted();
				owner.releaseOwnership();
				return shuttingDown();
			} catch (RuntimeException | Error failure) {
				owner.refuseIfUnadmitted();
				owner.releaseOwnership();
				throw failure;
			}
		}
	}

	private void admitApplicationRequest(final ExecutionReplyTask owner) {
		synchronized (admissionGate) {
			if (!owner.registration.newlyRegistered() || owner.registration.result().isDone()) {
				return;
			}
			if (state.get() != State.READY) {
				owner.refuseIfUnadmitted();
				return;
			}
			try {
				applications.execute(() -> completeApplicationRequest(owner.request, owner.registration));
				owner.applicationSubmitted = true;
			} catch (RejectedExecutionException e) {
				owner.registration.result().complete(result(CliExitCode.DAEMON_BUSY, "The daemon request queue is full."));
			}
		}
	}

	private void completeApplicationRequest(final DaemonRequest request,
			final RequestLedger.Registration registration) {
		try {
			registration.result().complete(commandExecutor.apply(request));
		} catch (RuntimeException e) {
			registration.result().complete(result(CliExitCode.COMMAND_FAILED, safeMessage(e)));
		}
	}

	private CliExecutionResult executeCliCommand(final DaemonRequest request) {
		return CliSupport.executeExpandedArguments(request.arguments().toArray(new String[0]),
				request.workingDirectory(), request.requestId(), environment, profileSecret);
	}

	private static CliExecutionResult awaitApplicationResult(final RequestLedger.Registration registration) {
		try {
			return registration.result().get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return result(CliExitCode.DAEMON_AMBIGUOUS, "The daemon stopped waiting for this command; its outcome is unknown.");
		} catch (ExecutionException e) {
			return result(CliExitCode.COMMAND_FAILED, "The daemon command failed before producing a result.");
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
				+ "activeReplies=" + executionReplies.getActiveCount() + '\n'
				+ "queuedReplies=" + executionReplies.getQueue().size() + '\n'
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
		final boolean changed;
		synchronized (admissionGate) {
			final State current = state.get();
			changed = (current == State.READY || current == State.BOUND) && state.compareAndSet(current, State.QUIESCING);
			if (changed) {
				refuseUnadmittedRequests();
			}
		}
		if (changed) {
			closeTransportQuietly();
		}
	}

	/** Only the NEW owner's undispatched registration may be refused; duplicate observers never change its shared result. */
	private void refuseUnadmittedRequests() {
		for (ExecutionReplyTask owner : replyOwners) {
			owner.refuseIfUnadmitted();
		}
	}

	private static CliExecutionResult shuttingDown() {
		return result(CliExitCode.DAEMON_BUSY, "The daemon is shutting down.");
	}

	private void cleanup(final DaemonStateStore.LockHandle lock) {
		if (!cleanupStarted.compareAndSet(false, true)) {
			return;
		}
		stopAcceptingConnections();
		stopNetworkIntake();
		drainApplicationRequests();
		drainNetworkWorkers();
		closeOpenConnections();
		retireMailerRegistry();
		removeOwnedDiscoveryState();
		closeInstanceLock(lock);
		state.set(State.STOPPED);
		stopped.countDown();
	}

	private void stopAcceptingConnections() {
		requestStop();
		closeTransportQuietly();
	}

	private void stopNetworkIntake() {
		synchronized (admissionGate) {
			connections.shutdown();
			executionReplies.shutdown();
		}
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
		awaitTerminationOrCancelQueuedWork(applications, 30);
		// A task that ended without producing a result must not strand its reply observer, even after a normal executor drain.
		ledger.completeIncomplete(result(CliExitCode.DAEMON_AMBIGUOUS,
				"The daemon stopped waiting for an accepted command; its terminal outcome is unknown."));
	}

	private void drainNetworkWorkers() {
		final long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
		final boolean intakeStopped = awaitTerminationUntil(connections, deadline);
		final boolean repliesStopped = awaitTerminationUntil(executionReplies, deadline);
		if (!intakeStopped || !repliesStopped) {
			closeOpenConnections();
			closeAbandonedConnections(connections.shutdownNow());
			closeAbandonedConnections(executionReplies.shutdownNow());
		}
	}

	private void closeOpenConnections() {
		for (SocketChannel channel : openConnections) {
			closeConnection(channel);
		}
	}

	private void closeConnection(final SocketChannel channel) {
		if (openConnections.remove(channel)) {
			try {
				channel.close();
			} catch (IOException ignored) {
				// Closure is terminal for this observer; accepted work remains owned by its ledger registration.
			}
		}
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
		return boundedExecutor(role, threads, QUEUE_SIZE);
	}

	private static ThreadPoolExecutor boundedExecutor(final String role, final int threads, final int queueCapacity) {
		return new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(queueCapacity),
				runnable -> {
					final Thread thread = new Thread(runnable, "sjm-daemon-" + role);
					thread.setDaemon(false);
					return thread;
				}, new ThreadPoolExecutor.AbortPolicy());
	}

	private static boolean awaitTerminationOrCancelQueuedWork(final ThreadPoolExecutor executor, final int seconds) {
		if (!awaitTerminationUntil(executor, System.nanoTime() + Duration.ofSeconds(seconds).toNanos())) {
			closeAbandonedConnections(executor.shutdownNow());
			return false;
		}
		return true;
	}

	private static boolean awaitTerminationUntil(final ThreadPoolExecutor executor, final long deadline) {
		try {
			return executor.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private static void closeAbandonedConnections(final Iterable<Runnable> abandoned) {
		for (final Runnable task : abandoned) {
			if (task instanceof ConnectionTask connectionTask) {
				connectionTask.close();
			} else if (task instanceof ExecutionReplyTask executionReplyTask) {
				executionReplyTask.close();
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
			closeConnection(channel);
		}
	}

	/** Owns one reserved reply slot and channel; application admission remains distinct from observing its result. */
	private final class ExecutionReplyTask implements Runnable {
		private final SocketChannel channel;
		private final DaemonRequest request;
		private final RequestLedger.Registration registration;
		private final AtomicBoolean released = new AtomicBoolean();
		private boolean applicationSubmitted;

		private ExecutionReplyTask(final SocketChannel channel, final DaemonRequest request,
				final RequestLedger.Registration registration) {
			this.channel = channel;
			this.request = request;
			this.registration = registration;
		}

		@Override
		public void run() {
			try {
				admitApplicationRequest(this);
				writeResponse(channel, request, awaitApplicationResult(registration));
			} catch (IOException | RuntimeException ignored) {
				// The reply observer may leave; it neither cancels nor resubmits accepted application work.
			} finally {
				close();
			}
		}

		private void refuseIfUnadmitted() {
			if (registration.newlyRegistered() && !applicationSubmitted) {
				registration.result().complete(shuttingDown());
			}
		}

		private void releaseOwnership() {
			if (released.compareAndSet(false, true)) {
				synchronized (admissionGate) {
					replyOwners.remove(this);
				}
				replyCapacity.release();
			}
		}

		private void close() {
			synchronized (admissionGate) {
				refuseIfUnadmitted();
			}
			closeConnection(channel);
			releaseOwnership();
		}
	}
}
