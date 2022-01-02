package org.simplejavamail.internal.util.concurrent;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.util.concurrent.CompletableFuture.runAsync;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.simplejavamail.internal.util.Preconditions.assumeTrue;

/**
 * Util that facilitates running a concurrent operation with CompletableFuture support.
 */
@SuppressWarnings("SameParameterValue")
public class AsyncOperationHelper {

	// TODO Lombok
	private AsyncOperationHelper() {
	}
	
	/**
	 * Executes using a single-execution ExecutorService, which is shutdown immediately after the operation finishes.
	 *
	 * @see Executors#newSingleThreadExecutor()
	 */
	public static CompletableFuture<Void> executeAsync(final @NotNull String processName, final @NotNull Runnable operation) {
		final ExecutorService executorService = newSingleThreadExecutor();
		return runAsync(new NamedRunnable(processName, operation), executorService)
				.thenRun(executorService::shutdown);
	}
	
	/**
	 * Executes using the given ExecutorService, which is left running after the thread finishes running.
	 */
	public static CompletableFuture<Void> executeAsync(final @NotNull ExecutorService executorService, final @NotNull String processName, final @NotNull Runnable operation) {
		assumeTrue(!executorService.isShutdown(), "cannot send async email, executor service is already shut down!");
		return runAsync(new NamedRunnable(processName, operation), executorService);
	}
}