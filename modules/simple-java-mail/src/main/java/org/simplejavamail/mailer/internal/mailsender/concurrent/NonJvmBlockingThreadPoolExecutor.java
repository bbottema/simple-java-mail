package org.simplejavamail.mailer.internal.mailsender.concurrent;

import org.simplejavamail.api.mailer.config.OperationalConfig;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Unbounded ThreadPoolExecutor that won't block the JVM from shutting down,
 * unless the keepAliveTime is explicitly set to zero by user config.
 *
 * @see ThreadPoolExecutor
 * @see LinkedBlockingQueue
 * @see NamedThreadFactory
 */
public class NonJvmBlockingThreadPoolExecutor extends ThreadPoolExecutor {
	public NonJvmBlockingThreadPoolExecutor(OperationalConfig operationalConfig, String threadNamePrefix) {
		super(operationalConfig.getThreadPoolSize(),
				operationalConfig.getThreadPoolSize(),
				operationalConfig.getThreadPoolKeepAliveTime(),
				TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<Runnable>(),
				new NamedThreadFactory(threadNamePrefix));
		// if a timeout is configured, the user wants threads to die off automatically
		// so they won't block the JVM from shutting down
		if (operationalConfig.getThreadPoolKeepAliveTime() > 0) {
			allowCoreThreadTimeOut(true);
		}
	}
}