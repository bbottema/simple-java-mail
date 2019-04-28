package org.simplejavamail.mailer.internal.mailsender;

import org.simplejavamail.api.mailer.AsyncResponse;
import org.simplejavamail.mailer.internal.mailsender.concurrent.NamedRunnable;
import org.slf4j.Logger;

import javax.annotation.Nonnull;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.Executors.newSingleThreadExecutor;
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
	static AsyncResponse executeAsync(final @Nonnull String processName,
									  final @Nonnull Runnable operation) {
		return executeAsync(newSingleThreadExecutor(), processName, operation, true);
	}
	
	/**
	 * Executes using a the given ExecutorService, which is left running after the thread finishes running.
	 *
	 * @see Executors#newSingleThreadExecutor()
	 */
	static AsyncResponse executeAsync(final @Nonnull ExecutorService executorService,
									  final @Nonnull String processName,
									  final @Nonnull Runnable operation) {
		return executeAsync(executorService, processName, operation, false);
	}
	
	private static AsyncResponse executeAsync(final @Nonnull ExecutorService executorService,
											  final @Nonnull String processName,
											  final @Nonnull Runnable operation,
											  final boolean shutDownExecutorService) {
		// atomic reference is needed to be able to smuggle the asyncResponse
		// into the Runnable which is passed itself to the asyncResponse.
		final AtomicReference<AsyncResponseImpl> asyncResponseRef = new AtomicReference<>();
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
