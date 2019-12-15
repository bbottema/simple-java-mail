/*
 * Copyright (C) 2009 Benny Bottema (benny@bennybottema.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
				new LinkedBlockingQueue<Runnable>(),
				new NamedThreadFactory(format("Simple Java Mail async mail sender, executor %s / thread", counter++)));
		// if a timeout is configured, the user wants threads to die off automatically
		// so they won't block the JVM from shutting down
		if (threadPoolKeepAliveTime > 0) {
			allowCoreThreadTimeOut(true);
		}
	}
}