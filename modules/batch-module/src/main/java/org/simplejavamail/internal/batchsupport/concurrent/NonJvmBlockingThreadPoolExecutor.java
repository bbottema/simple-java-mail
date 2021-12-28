package org.simplejavamail.internal.batchsupport.concurrent;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static java.lang.String.format;

/**
 * Unbounded ThreadPoolExecutor that won't block the JVM from shutting down,
 * unless the keepAliveTime is explicitly set to zero by user config.
 *
 * @see ThreadPoolExecutor
 * @see LinkedBlockingQueue
 * @see NamedThreadFactory
 */
public class NonJvmBlockingThreadPoolExecutor extends ThreadPoolExecutor {

	private static int counter = 1;

	@SuppressFBWarnings("ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD")
	public NonJvmBlockingThreadPoolExecutor(int threadPoolSize, int threadPoolKeepAliveTime) {
		super(threadPoolSize,
				threadPoolSize,
				threadPoolKeepAliveTime,
				TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<>(),
				new NamedThreadFactory(format("Simple Java Mail async mail sender, executor %s / thread", counter++)));
		// if a timeout is configured, the user wants threads to die off automatically,
		// so they won't block the JVM from shutting down
		if (threadPoolKeepAliveTime > 0) {
			allowCoreThreadTimeOut(true);
		}
	}
}