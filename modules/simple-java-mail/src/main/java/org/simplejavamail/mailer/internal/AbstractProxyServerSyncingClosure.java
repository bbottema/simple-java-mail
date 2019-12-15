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
package org.simplejavamail.mailer.internal;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.internal.authenticatedsockssupport.socks5server.AnonymousSocks5Server;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicInteger;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Starts and stops the authenticated proxy server when needed.
 */
public abstract class AbstractProxyServerSyncingClosure implements Runnable {

	protected static final Logger LOGGER = getLogger(AbstractProxyServerSyncingClosure.class);

	@NotNull private final AtomicInteger smtpConnectionCounter;
	@Nullable private final AnonymousSocks5Server proxyServer;

	AbstractProxyServerSyncingClosure(@NotNull final AtomicInteger smtpConnectionCounter, @Nullable final AnonymousSocks5Server proxyServer) {
		this.smtpConnectionCounter = smtpConnectionCounter;
		this.proxyServer = proxyServer;

		increaseSmtpConnectionCounter();
	}

	@SuppressFBWarnings(value = "JLM_JSR166_UTILCONCURRENT_MONITORENTER", justification = "Not sure why we needed this anymore, but it doesn't do any harm either")
	private void increaseSmtpConnectionCounter() {
		synchronized (smtpConnectionCounter) {
			smtpConnectionCounter.incrementAndGet();
		}
	}

	@Override
	public final void run() {
		try {
			startProxyServerIfNeeded();
			executeClosure();
		} finally {
			shutDownProxyServerIfRunningAndCurrentBatchCompleted();
		}
	}

	abstract void executeClosure();

	private void startProxyServerIfNeeded() {
		if (proxyServer != null) {
			synchronized (proxyServer) {
				if (!proxyServer.isRunning()) {
					LOGGER.trace("starting proxy bridge");
					proxyServer.start();
				}
			}
		}
	}

	@SuppressFBWarnings(value = "JLM_JSR166_UTILCONCURRENT_MONITORENTER", justification = "Not sure why we needed this anymore, but it doesn't do any harm either")
	private void shutDownProxyServerIfRunningAndCurrentBatchCompleted() {
		synchronized (smtpConnectionCounter) {
			if (smtpConnectionCounter.decrementAndGet() == 0) {
				LOGGER.trace("all threads have finished processing");
				if (proxyServer != null) {
					synchronized (proxyServer) {
						if (proxyServer.isRunning() && !proxyServer.isStopping()) {
							LOGGER.trace("stopping proxy bridge...");
							proxyServer.stop();
						}
					}
				}
			} else {
				LOGGER.trace("SMTP request threads left: {}", smtpConnectionCounter.get());
			}
		}
	}
}
