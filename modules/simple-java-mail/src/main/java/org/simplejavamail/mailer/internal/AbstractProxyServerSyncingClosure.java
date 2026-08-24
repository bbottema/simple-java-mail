package org.simplejavamail.mailer.internal;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.mail.Session;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.internal.authenticatedsockssupport.socks5server.AnonymousSocks5Server;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicInteger;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Starts and stops the authenticated proxy server when needed.
 * <p>
 * Note that this Runnable implementation is <strong>not</strong> thread related, it is just to encapsulate the code to
 * be run directly or from a <em>real</em> Runnable.
 */
public abstract class AbstractProxyServerSyncingClosure implements Runnable {

	protected static final Logger LOGGER = getLogger(AbstractProxyServerSyncingClosure.class);

	@NotNull private final AtomicInteger smtpConnectionCounter;
	@NotNull private final Session session;
	@Nullable private final AnonymousSocks5Server proxyServer;

	AbstractProxyServerSyncingClosure(@NotNull final AtomicInteger smtpConnectionCounter, @Nullable final AnonymousSocks5Server proxyServer,
			@NotNull final Session session) {
		this.smtpConnectionCounter = smtpConnectionCounter;
		this.proxyServer = proxyServer;
		this.session = session;

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
					LOGGER.trace("starting proxy bridge...");
					proxyServer.start();
				}
				final int localPort = proxyServer.getLocalPort();
				if (localPort < 1) {
					throw new IllegalStateException("Authenticated SOCKS proxy bridge did not bind to a usable loopback port");
				}
				final TransportStrategy sessionTransportStrategy = TransportStrategy.findStrategyForSession(session);
				final TransportStrategy proxyPropertyStrategy = sessionTransportStrategy != null ? sessionTransportStrategy : TransportStrategy.SMTP;
				session.getProperties().setProperty(proxyPropertyStrategy.propertyNameSocksPort(), String.valueOf(localPort));
				LOGGER.debug("Authenticated SOCKS proxy bridge is available at loopback:{}", localPort);
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
