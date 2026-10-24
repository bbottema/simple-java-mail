package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.mailer.MailFailureKind;

import jakarta.mail.Session;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.internal.authenticatedsockssupport.socks5server.AnonymousSocks5Server;
import org.simplejavamail.api.mailer.config.TransportStrategy;

import java.util.concurrent.atomic.AtomicInteger;

/** Keeps the selected destination's authenticated proxy alive until its last ordinary or retained connection scope finishes. */
final class SmtpProxyScope implements AutoCloseable {

	private final AtomicInteger activeScopes;
	@Nullable private final AnonymousSocks5Server proxyServer;
	private final Session selectedSession;
	private boolean scopeClosed;

	SmtpProxyScope(final AtomicInteger activeScopes, @Nullable final AnonymousSocks5Server proxyServer, final Session selectedSession) {
		this.activeScopes = activeScopes;
		this.proxyServer = proxyServer;
		this.selectedSession = selectedSession;
		synchronized (activeScopes) {
			activeScopes.incrementAndGet();
		}
	}

	void start() {
		if (proxyServer == null) {
			return;
		}
		synchronized (proxyServer) {
			if (!proxyServer.isRunning()) {
				proxyServer.start();
			}
			final int localPort = proxyServer.getLocalPort();
			if (localPort < 1) {
				throw new MailerException(MailFailureKind.CONNECTION,
						"Authenticated SOCKS proxy bridge did not bind to a usable loopback port", null);
			}
			final TransportStrategy strategy = MailerSessionSettings.transportStrategy(selectedSession.getProperties());
			selectedSession.getProperties().setProperty((strategy == null ? TransportStrategy.SMTP : strategy).propertyNameSocksPort(), String.valueOf(localPort));
		}
	}

	@Override
	public void close() {
		synchronized (activeScopes) {
			if (scopeClosed) {
				return;
			}
			scopeClosed = true;
			if (activeScopes.decrementAndGet() == 0 && proxyServer != null) {
				synchronized (proxyServer) {
					if (proxyServer.isRunning() && !proxyServer.isStopping()) {
						proxyServer.stop();
					}
				}
			}
		}
	}
}
