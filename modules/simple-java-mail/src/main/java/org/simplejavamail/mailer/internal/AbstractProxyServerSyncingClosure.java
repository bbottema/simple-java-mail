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
	private final MailSendDiagnosticsRecorder diagnostics;

	AbstractProxyServerSyncingClosure(final AtomicInteger smtpConnectionCounter, @Nullable final AnonymousSocks5Server proxyServer, final Session session) {
		this(smtpConnectionCounter, proxyServer, session, MailSendDiagnosticsRecorder.unobserved());
	}

	AbstractProxyServerSyncingClosure(final AtomicInteger smtpConnectionCounter, @Nullable final AnonymousSocks5Server proxyServer, final Session session,
			final MailSendDiagnosticsRecorder diagnostics) {
		proxy = new SmtpProxyScope(smtpConnectionCounter, proxyServer, session);
		this.diagnostics = diagnostics;
	}

	@Override
	public final void run() {
		Throwable primaryFailure = null;
		try {
			proxy.start();
			executeClosure();
		} catch (RuntimeException | Error failure) {
			primaryFailure = failure;
			diagnostics.failed();
			throw failure;
		} finally {
			diagnostics.startCleanup();
			try {
				proxy.close();
			} catch (RuntimeException | Error failure) {
				diagnostics.failed();
				if (primaryFailure == null) {
					throw failure;
				}
				primaryFailure.addSuppressed(failure);
			} finally {
				diagnostics.cleanupCompleted();
			}
		}
	}

	abstract void executeClosure();
}
