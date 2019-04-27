package org.simplejavamail.mailer.internal.mailsender.concurrent;

import org.simplejavamail.api.mailer.config.OperationalConfig;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @see ThreadPoolExecutor
 * @see LinkedBlockingQueue
 * @see NamedThreadFactory
 */
public class NonJvmBlockingThreadPoolExecutor extends ThreadPoolExecutor {
	public NonJvmBlockingThreadPoolExecutor(OperationalConfig operationalConfig, String threadNamePrefix) {
		super(operationalConfig.getThreadPoolSize(),
				operationalConfig.getThreadPoolSize(),
				operationalConfig.getThreadPoolTimeout(),
				TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<Runnable>(),
				new NamedThreadFactory(threadNamePrefix));
		if (operationalConfig.getThreadPoolTimeout() > 0) {
			allowCoreThreadTimeOut(true);
		}
	}
}