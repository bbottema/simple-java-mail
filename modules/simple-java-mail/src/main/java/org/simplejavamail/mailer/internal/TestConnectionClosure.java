package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.internal.authenticatedsockssupport.socks5server.AnonymousSocks5Server;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.mailer.internal.util.SessionLogger;
import org.simplejavamail.mailer.internal.util.TransportRunner;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.mail.MessagingException;
import javax.mail.Session;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Extra closure for the actual connection test, so this can be called regularly as well as from async thread.
 */
class TestConnectionClosure extends AbstractProxyServerSyncingClosure {

	@Nonnull private final OperationalConfig operationalConfig;
	@Nonnull private final Session session;
	private final boolean async;

	TestConnectionClosure(@Nonnull OperationalConfig operationalConfig, @Nonnull Session session, @Nullable final AnonymousSocks5Server proxyServer, final boolean async, @Nonnull AtomicInteger smtpConnectionCounter) {
		super(smtpConnectionCounter, proxyServer);
		this.operationalConfig = operationalConfig;
		this.session = session;
		this.async = async;
	}

	@Override
	public void executeClosure() {
		LOGGER.debug("testing connection...");
		try {
			SessionLogger.logSession(session, async, "connection test");

			if (operationalConfig.getCustomMailer() != null) {
				operationalConfig.getCustomMailer().testConnection(operationalConfig, session);
			} else {
				TransportRunner.connect(operationalConfig.getClusterKey(), session);
			}
		} catch (final MessagingException e) {
			throw new MailerException(MailerException.ERROR_CONNECTING_SMTP_SERVER, e);
		} catch (final Exception e) {
			LOGGER.error("Failed to test connection email");
			throw e;
		}
	}
}
