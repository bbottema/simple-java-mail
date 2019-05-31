package org.simplejavamail.internal.modules;

import org.simplejavamail.api.mailer.AsyncResponse;

import javax.annotation.Nonnull;
import java.util.concurrent.ExecutorService;

/**
 * This interface only serves to hide the Batch implementation behind an easy-to-load-with-reflection class.
 */
public interface BatchModule {
	/**
	 * Executes using a single-execution ExecutorService, which shutdown immediately after the thread finishes.
	 *
	 * @see java.util.concurrent.Executors#newSingleThreadExecutor()
	 */
	@Nonnull
	AsyncResponse executeAsync(@Nonnull String processName, @Nonnull Runnable operation);

	/**
	 * Executes using a the given ExecutorService, which is left running after the thread finishes running.
	 */
	@Nonnull
	AsyncResponse executeAsync(@Nonnull ExecutorService executorService, @Nonnull String processName, @Nonnull Runnable operation);

	/**
	 * @return A NonJvmBlockingThreadPoolExecutor instance that by default doesn't block the JVM from exiting
	 * and produces properly named thread.
	 */
	@Nonnull
	ExecutorService createDefaultExecutorService(final int threadPoolSize, final int keepAliveTime);
}
