package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.internal.authenticatedsockssupport.socks5server.AnonymousSocks5Server;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicInteger;

import static org.slf4j.LoggerFactory.getLogger;

/** Starts the selected destination's proxy for a synchronous closure and releases it only after transport cleanup. */
public abstract class AbstractProxyServerSyncingClosure implements Runnable {

	protected static final Logger LOGGER = getLogger(AbstractProxyServerSyncingClosure.class);
	private final SmtpProxyScope proxy;

	AbstractProxyServerSyncingClosure(final AtomicInteger smtpConnectionCounter, @Nullable final AnonymousSocks5Server proxyServer, final Session session) {
		proxy = new SmtpProxyScope(smtpConnectionCounter, proxyServer, session);
	}

	@Override
	public final void run() {
		Throwable primaryFailure = null;
		try {
			proxy.start();
			executeClosure();
		} catch (RuntimeException | Error failure) {
			primaryFailure = failure;
			throw failure;
		} finally {
			try {
				proxy.close();
			} catch (RuntimeException | Error failure) {
				if (primaryFailure == null) {
					throw failure;
				}
				primaryFailure.addSuppressed(failure);
			}
		}
	}

	abstract void executeClosure();
}
