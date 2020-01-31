package org.simplejavamail.internal.batchsupport;

import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.mailer.AsyncResponse;
import org.simplejavamail.internal.batchsupport.concurrent.NamedRunnable;
import org.slf4j.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.simplejavamail.internal.util.Preconditions.assumeTrue;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Util that facilitates running a concurrent operation while supporting {@link AsyncResponse}.
 */
@SuppressWarnings("SameParameterValue")
class AsyncOperationHelper {

	private static final Logger LOGGER = getLogger(AsyncOperationHelper.class);
	
	private AsyncOperationHelper() {
	}
	
	/**
	 * Executes using a single-execution ExecutorService, which shutdown immediately after the thread finishes.
	 *
	 * @see Executors#newSingleThreadExecutor()
	 */
	static AsyncResponse executeAsync(final @NotNull String processName,
									  final @NotNull Runnable operation) {
		return executeAsync(newSingleThreadExecutor(), processName, operation, true);
	}
	
	/**
	 * Executes using a the given ExecutorService, which is left running after the thread finishes running.
	 *
	 * @see Executors#newSingleThreadExecutor()
	 */
	static AsyncResponse executeAsync(final @NotNull ExecutorService executorService,
									  final @NotNull String processName,
									  final @NotNull Runnable operation) {
		return executeAsync(executorService, processName, operation, false);
	}
	
	private static AsyncResponse executeAsync(final @NotNull ExecutorService executorService,
											  final @NotNull String processName,
											  final @NotNull Runnable operation,
											  final boolean shutDownExecutorService) {
		// atomic reference is needed to be able to smuggle the asyncResponse
		// into the Runnable which is passed itself to the asyncResponse.
		final AtomicReference<AsyncResponseImpl> asyncResponseRef = new AtomicReference<>();
		assumeTrue(!executorService.isShutdown(), "cannot send async email, executor service is already shut down!");
		asyncResponseRef.set(new AsyncResponseImpl(executorService.submit(new NamedRunnable(processName) {
			@Override
			public void run() {
				// by the time the code reaches here, the user would have configured the appropriate handlers
				try {
					operation.run();
					asyncResponseRef.get().delegateSuccessHandling();
				} catch (Exception e) {
					LOGGER.error("Failed to run " + processName, e);
					asyncResponseRef.get().delegateExceptionHandling(e);
					throw e; // trigger the returned Future's exception handle
				} finally {
					if (shutDownExecutorService) {
						executorService.shutdown();
					}
				}
			}

		})));
		return asyncResponseRef.get();
	}
}
