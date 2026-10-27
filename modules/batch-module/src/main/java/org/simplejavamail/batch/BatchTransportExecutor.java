package org.simplejavamail.batch;

import org.simplejavamail.api.mailer.MailFailureKind;

import jakarta.mail.Session;
import org.simplejavamail.internal.batchsupport.BatchTransportEngine;
import org.simplejavamail.internal.batchsupport.concurrent.NonJvmBlockingThreadPoolExecutor;
import org.simplejavamail.internal.configresolution.ConfigurationChoice;
import org.simplejavamail.internal.configresolution.ConfigurationDeclaration;
import org.simplejavamail.internal.configresolution.ConfigurationOrigin;
import org.simplejavamail.internal.configresolution.ConfigurationResolver;
import org.simplejavamail.internal.configresolution.ConfigurationSetting;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

	private static final ThreadLocal<ExecutionContext> CURRENT_EXECUTION = new ThreadLocal<>();
	private static final List<ConfigurationOrigin.Kind> CLUSTER_BEFORE_DEFAULT = List.of(
			ConfigurationOrigin.Kind.OVERRIDE, ConfigurationOrigin.Kind.DERIVED);

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
	private CompletableFuture<Void> shutdownCompletion;

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
			engine.register(clusterKey, session, resolveClusterConfiguration(clusterKey));
		}
		return this;
	}

	/** Standalone cluster choices replace the complete pool value, including an absent creation-age threshold. */
	private BatchTransportPoolConfiguration resolveClusterConfiguration(final K clusterKey) {
		final ConfigurationSetting<BatchTransportPoolConfiguration> setting = ConfigurationSetting.of("batch.pool", "configuration");
		final BatchTransportPoolConfiguration supplied = clusterConfigurations.get(clusterKey);
		final ConfigurationChoice<BatchTransportPoolConfiguration> clusterChoice = !clusterConfigurations.containsKey(clusterKey)
				? ConfigurationChoice.inherit() : supplied == null ? ConfigurationChoice.remove() : ConfigurationChoice.set(supplied);
		return ConfigurationResolver.resolve(setting, List.of(
				ConfigurationDeclaration.of(setting, clusterChoice,
						ConfigurationOrigin.of(ConfigurationOrigin.Kind.OVERRIDE, "poolConfiguration", "withClusterConfiguration")),
				ConfigurationDeclaration.of(setting, ConfigurationChoice.set(defaultPoolConfiguration),
						ConfigurationOrigin.of(ConfigurationOrigin.Kind.DERIVED, "poolConfiguration", "default pool configuration"))),
				CLUSTER_BEFORE_DEFAULT).valueOrNull();
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
	 * down the module-owned executor. Repeated calls observe the same cleanup operation through fresh, detached futures.
	 * Completing normally or exceptionally, cancelling, or timing out a returned future affects only that caller's view, not resource cleanup,
	 * other views, or {@link #close()}.
	 * <p>
	 * An operation or inline completion callback may initiate shutdown, but must not wait for it: cleanup waits for
	 * that work to finish. Wait from the owning application thread instead.
	 *
	 * @return a new detached view that completes when resource cleanup finishes, retaining any cleanup failure
	 */
	public CompletableFuture<Void> shutdown() {
		return detachedView(beginShutdown(false));
	}

	/**
	 * Escalates shutdown by rejecting further transport claims, invalidating active leases, interrupting module-owned
	 * workers, and cancelling queued facade submissions. A forced call can escalate an existing graceful shutdown.
	 * Each call returns a fresh, detached view of the same cleanup operation, as described by {@link #shutdown()}.
	 * Caller-owned executors remain running, and cleanup still waits for active callbacks to finish.
	 * <p>
	 * An operation or inline completion callback may initiate forced shutdown, but must not wait for it.
	 *
	 * @return a new detached view that completes when resource cleanup finishes, retaining any cleanup failure
	 */
	public CompletableFuture<Void> shutdownNow() {
		return detachedView(beginShutdown(true));
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
	 * credential-safe {@link BatchTransportException}. Mutating a future returned by {@link #shutdown()} or
	 * {@link #shutdownNow()} cannot change this wait.
	 * <p>
	 * Calling this method from this facade's own operation or inline completion callback would wait for itself. Such
	 * calls are rejected before initiating shutdown, including operations on supplied or direct executors. Close from
	 * the owning application thread, or initiate {@link #shutdown()} without waiting inside the callback.
	 *
	 * @throws IllegalStateException when called from this facade's own operation or worker
	 * @throws BatchTransportException when interrupted or resource cleanup fails
	 */
	@Override
	public void close() {
		for (ExecutionContext context = CURRENT_EXECUTION.get(); context != null; context = context.previous) {
			if (context.owner == this) {
				throw new IllegalStateException("A batch transport operation cannot wait for its own shutdown; close the facade from its owning application thread");
			}
		}
		try {
			beginShutdown(false).get();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new BatchTransportException(MailFailureKind.CLEANUP, "Interrupted while shutting down batch transport resources", interrupted);
		} catch (ExecutionException failure) {
			final Throwable cause = failure.getCause();
			if (cause instanceof BatchTransportException) {
				throw (BatchTransportException) cause;
			}
			throw new BatchTransportException(MailFailureKind.CLEANUP, "Unable to shut down batch transport resources", cause);
		}
	}

	private <T, E extends Exception> T executeAccepted(final K clusterKey, final Session stickySession,
			final BatchTransportOperation<T, E> operation) throws E {
		Objects.requireNonNull(clusterKey, "clusterKey");
		Objects.requireNonNull(operation, "operation");
		beginOperation();
		final ExecutionContext previous = enterExecution();
		try {
			return engine.execute(clusterKey, stickySession, operation);
		} finally {
			finishOperation();
			restoreExecution(previous);
		}
	}

	private ExecutionContext enterExecution() {
		final ExecutionContext previous = CURRENT_EXECUTION.get();
		CURRENT_EXECUTION.set(new ExecutionContext(this, previous));
		return previous;
	}

	private static void restoreExecution(final ExecutionContext previous) {
		if (previous == null) {
			CURRENT_EXECUTION.remove();
		} else {
			CURRENT_EXECUTION.set(previous);
		}
	}

	private static final class ExecutionContext {
		private final BatchTransportExecutor<?> owner;
		private final ExecutionContext previous;

		private ExecutionContext(final BatchTransportExecutor<?> owner, final ExecutionContext previous) {
			this.owner = owner;
			this.previous = previous;
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
			submission.cancel(new BatchTransportException(MailFailureKind.CAPACITY, "Unable to schedule batch transport work", schedulingFailure));
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
				shutdownCompletion = new CompletableFuture<>();
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
			final BatchTransportException cancellation = new BatchTransportException(MailFailureKind.CANCELLED, "Batch transport work was cancelled by forced shutdown", null);
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
			return shutdownCompletion;
		}
	}

	private static CompletableFuture<Void> detachedView(final CompletableFuture<Void> completion) {
		final CompletableFuture<Void> view = new CompletableFuture<>();
		completion.whenComplete((result, failure) -> {
			if (failure == null) {
				view.complete(null);
			} else {
				view.completeExceptionally(failure);
			}
		});
		return view;
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
			completeShutdownFuture(new BatchTransportException(MailFailureKind.CLEANUP, "Batch transport shutdown was interrupted", interrupted));
		} catch (ExecutionException failure) {
			completeShutdownFuture(new BatchTransportException(MailFailureKind.CLEANUP, "Unable to close all SMTP connection pools", failure.getCause()));
		} catch (RuntimeException failure) {
			completeShutdownFuture(failure instanceof BatchTransportException
					? failure
					: new BatchTransportException(MailFailureKind.CLEANUP, "Unable to close batch transport resources", failure));
		}
	}

	private void completeShutdownFuture(final Throwable failure) {
		final CompletableFuture<Void> future;
		synchronized (lifecycleMonitor) {
			state = State.CLOSED;
			future = shutdownCompletion;
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
			final ExecutionContext previous = enterExecution();
			try {
				future.complete(engine.execute(clusterKey, stickySession, operation));
			} catch (Throwable failure) {
				future.completeExceptionally(failure);
			} finally {
				finish();
				restoreExecution(previous);
			}
		}

		private void cancel(final Throwable failure) {
			// Cancellation may invoke user continuations inline, before this submission releases its accepted operation.
			final ExecutionContext previous = enterExecution();
			try {
				future.completeExceptionally(failure);
			} finally {
				if (executionClaimed.compareAndSet(false, true)) {
					finish();
				}
				restoreExecution(previous);
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
