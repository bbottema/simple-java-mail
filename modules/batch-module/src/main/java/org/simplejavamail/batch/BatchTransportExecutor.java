package org.simplejavamail.batch;

import jakarta.mail.Session;
import org.simplejavamail.internal.batchsupport.BatchTransportEngine;
import org.simplejavamail.internal.batchsupport.concurrent.NonJvmBlockingThreadPoolExecutor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Managed callback, asynchronous-execution, and lifecycle facade over {@code smtp-connection-pool}.
 * <p>
 * This class does not implement a second physical connection pool. It registers caller-owned Jakarta Mail Sessions
 * with the upstream pool, claims one connected Transport for each operation, and keeps the raw lease private. A normal
 * callback result releases the connection; an escaping exception or error invalidates it.
 * <p>
 * The facade owns its default asynchronous executor and shuts it down during close. An executor supplied through
 * {@link BatchTransportExecutorBuilder#withExecutorService(ExecutorService)} remains caller-owned. Graceful shutdown
 * waits for all accepted work, including work queued on a caller-owned executor; that executor therefore must continue
 * making progress. Forced shutdown rejects new claims, invalidates active leases, and cancels not-yet-started facade
 * tasks, but cannot forcibly stop arbitrary callback code that ignores interruption.
 *
 * @param <K> cluster-key type
 */
public final class BatchTransportExecutor<K> implements AutoCloseable {

	/** Session property containing a fixed OAuth2 access token. */
	public static final String OAUTH2_TOKEN_PROPERTY = "simplejavamail.oauth2.token";
	/** Session property containing a {@code Supplier<String>} that resolves an OAuth2 access token on connect/reconnect. */
	public static final String OAUTH2_TOKEN_PROVIDER_PROPERTY = "simplejavamail.oauth2.token.provider";

	private enum State { OPEN, CLOSING, CLOSED }

	private final Object lifecycleMonitor = new Object();
	private final BatchTransportEngine<K> engine;
	private final BatchTransportPoolConfiguration defaultPoolConfiguration;
	private final Map<K, BatchTransportPoolConfiguration> clusterConfigurations;
	private final ExecutorService executorService;
	private final boolean ownsExecutorService;
	private final Set<AsyncSubmission<?>> asyncSubmissions = new HashSet<>();
	private State state = State.OPEN;
	private int acceptedOperations;
	private boolean forceRequested;
	private CompletableFuture<Void> shutdownFuture;

	BatchTransportExecutor(final BatchTransportPoolConfiguration defaultPoolConfiguration,
			final Map<K, BatchTransportPoolConfiguration> clusterConfigurations,
			final ExecutorService suppliedExecutorService, final int threadPoolSize,
			final int threadPoolKeepAliveMillis) {
		this.defaultPoolConfiguration = defaultPoolConfiguration;
		this.clusterConfigurations = clusterConfigurations;
		this.engine = new BatchTransportEngine<>(defaultPoolConfiguration);
		this.ownsExecutorService = suppliedExecutorService == null;
		this.executorService = ownsExecutorService
				? new NonJvmBlockingThreadPoolExecutor(threadPoolSize, threadPoolKeepAliveMillis)
				: suppliedExecutorService;
	}

	/**
	 * Starts a builder for a cluster-key type.
	 *
	 * @param <K> cluster-key type
	 * @return a new builder
	 */
	public static <K> BatchTransportExecutorBuilder<K> builder() {
		return new BatchTransportExecutorBuilder<>();
	}

	/**
	 * Registers a Session under a cluster key. Registration is idempotent for the same key and Session identity.
	 * <p>
	 * The Session remains caller-owned. Its effective default transport must not be {@code smtppool}, because the batch
	 * facade itself already owns the one physical pool. OAuth2 token properties are bridged at registration so whichever
	 * Session the cluster later selects also supplies the credentials used to connect.
	 *
	 * @param clusterKey cluster to register with
	 * @param session caller-owned Jakarta Mail Session
	 * @return this facade
	 * @throws BatchTransportException when shutdown has begun or the Session selects {@code smtppool}
	 */
	public BatchTransportExecutor<K> registerSession(final K clusterKey, final Session session) {
		Objects.requireNonNull(clusterKey, "clusterKey");
		Objects.requireNonNull(session, "session");
		synchronized (lifecycleMonitor) {
			ensureOpen("register a Session");
			final BatchTransportPoolConfiguration configuration = clusterConfigurations.containsKey(clusterKey)
					? clusterConfigurations.get(clusterKey)
					: defaultPoolConfiguration;
			engine.register(clusterKey, session, configuration);
		}
		return this;
	}

	/**
	 * Runs an operation using whichever registered Session the cluster selects.
	 *
	 * @param clusterKey target cluster
	 * @param operation callback-scoped work
	 * @param <T> result type
	 * @param <E> checked callback failure type
	 * @return the callback result
	 * @throws E when the callback fails
	 * @throws BatchTransportException when acquisition or lifecycle management fails
	 */
	public <T, E extends Exception> T execute(final K clusterKey,
			final BatchTransportOperation<T, E> operation) throws E {
		return executeAccepted(clusterKey, null, operation);
	}

	/**
	 * Runs an operation using one exact Session registered under the cluster key.
	 *
	 * @param clusterKey target cluster
	 * @param session exact registered Session to use
	 * @param operation callback-scoped work
	 * @param <T> result type
	 * @param <E> checked callback failure type
	 * @return the callback result
	 * @throws E when the callback fails
	 * @throws BatchTransportException when the Session is not registered or acquisition fails
	 */
	public <T, E extends Exception> T execute(final K clusterKey, final Session session,
			final BatchTransportOperation<T, E> operation) throws E {
		return executeAccepted(clusterKey, Objects.requireNonNull(session, "session"), operation);
	}

	/**
	 * Submits an operation using whichever registered Session the cluster selects.
	 *
	 * @param clusterKey target cluster
	 * @param operation callback-scoped work
	 * @param <T> result type
	 * @param <E> checked callback failure type
	 * @return a future completed with the callback result or original callback failure
	 */
	public <T, E extends Exception> CompletableFuture<T> submit(final K clusterKey,
			final BatchTransportOperation<T, E> operation) {
		return submitAccepted(clusterKey, null, operation);
	}

	/**
	 * Submits an operation using one exact Session registered under the cluster key.
	 *
	 * @param clusterKey target cluster
	 * @param session exact registered Session to use
	 * @param operation callback-scoped work
	 * @param <T> result type
	 * @param <E> checked callback failure type
	 * @return a future completed with the callback result or original callback failure
	 */
	public <T, E extends Exception> CompletableFuture<T> submit(final K clusterKey, final Session session,
			final BatchTransportOperation<T, E> operation) {
		return submitAccepted(clusterKey, Objects.requireNonNull(session, "session"), operation);
	}

	/**
	 * Stops accepting new registration and work, waits for already accepted work, closes all physical pools, and shuts
	 * down the module-owned executor. Repeated calls return the same completion handle.
	 *
	 * @return the shared shutdown completion handle
	 */
	public CompletableFuture<Void> shutdown() {
		return beginShutdown(false);
	}

	/**
	 * Escalates shutdown by rejecting further transport claims, invalidating active leases, interrupting module-owned
	 * workers, and cancelling queued facade submissions. Repeated graceful or forced calls return the same handle.
	 *
	 * @return the shared shutdown completion handle
	 */
	public CompletableFuture<Void> shutdownNow() {
		return beginShutdown(true);
	}

	/**
	 * Reports whether shutdown has begun.
	 *
	 * @return {@code true} once this facade no longer accepts new work
	 */
	public boolean isShutdown() {
		synchronized (lifecycleMonitor) {
			return state != State.OPEN;
		}
	}

	/**
	 * Performs and waits for graceful shutdown. If interrupted, this method restores the interrupt flag and throws a
	 * credential-safe {@link BatchTransportException}.
	 */
	@Override
	public void close() {
		try {
			shutdown().get();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new BatchTransportException("Interrupted while shutting down batch transport resources", interrupted);
		} catch (ExecutionException failure) {
			final Throwable cause = failure.getCause();
			if (cause instanceof BatchTransportException) {
				throw (BatchTransportException) cause;
			}
			throw new BatchTransportException("Unable to shut down batch transport resources", cause);
		}
	}

	private <T, E extends Exception> T executeAccepted(final K clusterKey, final Session stickySession,
			final BatchTransportOperation<T, E> operation) throws E {
		Objects.requireNonNull(clusterKey, "clusterKey");
		Objects.requireNonNull(operation, "operation");
		beginOperation();
		try {
			return engine.execute(clusterKey, stickySession, operation);
		} finally {
			finishOperation();
		}
	}

	private <T, E extends Exception> CompletableFuture<T> submitAccepted(final K clusterKey, final Session stickySession,
			final BatchTransportOperation<T, E> operation) {
		Objects.requireNonNull(clusterKey, "clusterKey");
		Objects.requireNonNull(operation, "operation");
		final AsyncSubmission<T> submission = new AsyncSubmission<>(clusterKey, stickySession, operation);
		synchronized (lifecycleMonitor) {
			ensureOpen("submit work");
			acceptedOperations++;
			asyncSubmissions.add(submission);
		}
		try {
			executorService.execute(submission);
		} catch (RuntimeException schedulingFailure) {
			submission.cancel(new BatchTransportException("Unable to schedule batch transport work", schedulingFailure));
		}
		return submission.future;
	}

	private void beginOperation() {
		synchronized (lifecycleMonitor) {
			ensureOpen("execute work");
			acceptedOperations++;
		}
	}

	private void finishOperation() {
		synchronized (lifecycleMonitor) {
			acceptedOperations--;
			lifecycleMonitor.notifyAll();
		}
	}

	private CompletableFuture<Void> beginShutdown(final boolean force) {
		final boolean startWorker;
		final ArrayList<AsyncSubmission<?>> submissionsToCancel;
		synchronized (lifecycleMonitor) {
			startWorker = state == State.OPEN;
			if (startWorker) {
				state = State.CLOSING;
				shutdownFuture = new CompletableFuture<>();
			}
			if (force && !forceRequested && state != State.CLOSED) {
				forceRequested = true;
				submissionsToCancel = new ArrayList<>(asyncSubmissions);
			} else {
				submissionsToCancel = null;
			}
		}

		if (submissionsToCancel != null) {
			engine.stopClaimsAndInvalidateActiveLeases();
			final BatchTransportException cancellation = new BatchTransportException("Batch transport work was cancelled by forced shutdown");
			for (AsyncSubmission<?> submission : submissionsToCancel) {
				submission.cancel(cancellation);
			}
			if (ownsExecutorService) {
				executorService.shutdownNow();
			}
		}

		if (startWorker) {
			final Thread shutdownThread = new Thread(this::completeShutdown, "Simple Java Mail batch transport shutdown");
			shutdownThread.setDaemon(false);
			shutdownThread.start();
		}
		synchronized (lifecycleMonitor) {
			return shutdownFuture;
		}
	}

	private void completeShutdown() {
		try {
			synchronized (lifecycleMonitor) {
				while (acceptedOperations != 0) {
					lifecycleMonitor.wait();
				}
			}
			if (ownsExecutorService) {
				executorService.shutdown();
				while (!executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {
					// Continue waiting until the module-owned workers have terminated.
				}
			}
			final Future<?> poolShutdown = engine.shutdown();
			poolShutdown.get();
			completeShutdownFuture(null);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			completeShutdownFuture(new BatchTransportException("Batch transport shutdown was interrupted", interrupted));
		} catch (ExecutionException failure) {
			completeShutdownFuture(new BatchTransportException("Unable to close all SMTP connection pools", failure.getCause()));
		} catch (RuntimeException failure) {
			completeShutdownFuture(failure instanceof BatchTransportException
					? failure
					: new BatchTransportException("Unable to close batch transport resources", failure));
		}
	}

	private void completeShutdownFuture(final Throwable failure) {
		final CompletableFuture<Void> future;
		synchronized (lifecycleMonitor) {
			state = State.CLOSED;
			future = shutdownFuture;
			lifecycleMonitor.notifyAll();
		}
		if (failure == null) {
			future.complete(null);
		} else {
			future.completeExceptionally(failure);
		}
	}

	private void ensureOpen(final String operation) {
		if (state != State.OPEN) {
			throw new BatchTransportException("Cannot " + operation + " after shutdown has begun");
		}
	}

	private final class AsyncSubmission<T> implements Runnable {
		private final K clusterKey;
		private final Session stickySession;
		private final BatchTransportOperation<T, ? extends Exception> operation;
		private final CompletableFuture<T> future = new CompletableFuture<>();
		private final AtomicBoolean executionClaimed = new AtomicBoolean();
		private final AtomicBoolean operationFinished = new AtomicBoolean();

		private AsyncSubmission(final K clusterKey, final Session stickySession,
				final BatchTransportOperation<T, ? extends Exception> operation) {
			this.clusterKey = clusterKey;
			this.stickySession = stickySession;
			this.operation = operation;
		}

		@Override
		public void run() {
			if (!executionClaimed.compareAndSet(false, true)) {
				return;
			}
			try {
				future.complete(engine.execute(clusterKey, stickySession, operation));
			} catch (Throwable failure) {
				future.completeExceptionally(failure);
			} finally {
				finish();
			}
		}

		private void cancel(final Throwable failure) {
			future.completeExceptionally(failure);
			if (executionClaimed.compareAndSet(false, true)) {
				finish();
			}
		}

		private void finish() {
			if (operationFinished.compareAndSet(false, true)) {
				synchronized (lifecycleMonitor) {
					asyncSubmissions.remove(this);
					acceptedOperations--;
					lifecycleMonitor.notifyAll();
				}
			}
		}
	}
}
