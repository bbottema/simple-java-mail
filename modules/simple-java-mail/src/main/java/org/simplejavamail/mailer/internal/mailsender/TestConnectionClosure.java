package org.simplejavamail.mailer.internal.mailsender;

import org.simplejavamail.api.internal.authenticatedsockssupport.socks5server.AnonymousSocks5Server;
import org.simplejavamail.mailer.internal.mailsender.util.SessionLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Extra closure for the actual connection test, so this can be called regularly as well as from async thread.
 */
class TestConnectionClosure extends AbstractProxyServerSyncingClosure {

	@Nonnull private final Session session;
	private final boolean async;

	TestConnectionClosure(@Nonnull Session session, @Nullable final AnonymousSocks5Server proxyServer, final boolean async, @Nonnull AtomicInteger smtpConnectionCounter) {
		super(smtpConnectionCounter, proxyServer);
		this.session = session;
		this.async = async;
	}

	@Override
	public void executeClosure() {
		LOGGER.debug("testing connection...");
		try {
			SessionLogger.logSession(session, async, "connection test");

			try (Transport transport = session.getTransport()) {
				transport.connect(); // actual test
				LOGGER.debug("...connection succesful");
			} finally {
				LOGGER.trace("closing transport");
			}
		} catch (final MessagingException e) {
			throw new MailSenderException(MailSenderException.ERROR_CONNECTING_SMTP_SERVER, e);
		} catch (final Exception e) {
			LOGGER.error("Failed to test connection email");
			throw e;
		}
	}
}
